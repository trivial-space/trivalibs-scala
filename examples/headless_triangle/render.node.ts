// Headless render driver for node-webgpu — runs on Node 24 (`node`, native TS
// type stripping) and on Bun (`bun`), from the repo root after
// `bun run examples:build`:
//
//   node examples/headless_triangle/render.node.ts [flags]
//
// Flags:
//   --backend <name>   Dawn backend: vulkan | opengl | opengles | null
//   --adapter <name>   Dawn adapter by exact name (see --list-adapters)
//   --software         request the fallback (software) adapter
//   --require <text>   reject the adapter unless its info contains <text>
//   --list-adapters    print what each adapter request returns, then stop
//
// Writes to examples/headless_triangle/assets/:
//   pre-rendered-triangle.png      rgba8unorm PixelImage → 8-bit PNG
//   pre-rendered-triangle-16.png   rgba16float PixelImage, intensity 4 (HDR)
//                                  → 16-bit PNG, clamped to [0, 1]
// checks a 3-frame PixelFrames sequence, and decodes the 8-bit PNG again to
// feed it back in as a panel source (it must come out byte for byte).

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { create } from 'webgpu'
import { decodePng, encodePng } from '../example_helpers/png.ts'
import { render, roundtrip } from '../out/headless_triangle.js'
import type { PixelFrames, PixelImage } from '../example_helpers/pixels.d.ts'

const args = process.argv.slice(2)
const flag = (name: string) => args.includes(name)
const option = (name: string) => {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : undefined
}

const dawnOptions: string[] = []
const backend = option('--backend')
const adapter = option('--adapter')
if (backend) dawnOptions.push(`backend=${backend}`)
if (adapter) dawnOptions.push(`adapter=${adapter}`)
const software = flag('--software')
const requireName = option('--require') ?? ''

const describe = (a: GPUAdapter | null) =>
  a
    ? [a.info.vendor, a.info.architecture, a.info.device, a.info.description].join(' / ')
    : 'none'

if (flag('--list-adapters')) {
  // Dawn lists its adapters (backend + exact name for --adapter) when asked
  // for one that doesn't exist.
  try {
    console.log('Dawn adapters:', describe(await create(['adapter=?']).requestAdapter()))
  } catch (e) {
    console.log(String(e))
  }
  const gpu = create(dawnOptions)
  const requests: GPURequestAdapterOptions[] = [
    {},
    { powerPreference: 'high-performance' },
    { powerPreference: 'low-power' },
    { forceFallbackAdapter: true },
  ]
  for (const r of requests) {
    console.log(JSON.stringify(r).padEnd(36), describe(await gpu.requestAdapter(r)))
  }
} else {
  const gpu = create(dawnOptions)
  const size = 256
  const outDir = 'examples/headless_triangle/assets'
  mkdirSync(outDir, { recursive: true })

  const img8: PixelImage = {
    width: size,
    height: size,
    format: 'rgba8unorm',
    data: new Uint8Array(size * size * 4),
  }
  await render(gpu, img8, 1, 1, software, requireName)
  writeFileSync(`${outDir}/pre-rendered-triangle.png`, encodePng(size, size, img8.data, 8))

  const img16: PixelImage = {
    width: size,
    height: size,
    format: 'rgba16float',
    data: new Float16Array(size * size * 4),
  }
  await render(gpu, img16, 1, 4, software, requireName)
  let max = 0
  for (const v of img16.data) if (v > max) max = v
  console.log(`rgba16float max channel: ${max} (> 1 survives the readback)`)
  writeFileSync(`${outDir}/pre-rendered-triangle-16.png`, encodePng(size, size, img16.data, 16))

  const seq: PixelFrames = { width: size, height: size, format: 'rgba8unorm', frames: [] }
  await render(gpu, seq, 3, 1, software, requireName)
  const center = (f: Uint8Array) => f[((size / 2) * size + size / 2) * 4]
  console.log(
    `PixelFrames: ${seq.frames.length} frames, center red per frame:`,
    seq.frames.map((f) => center(f as Uint8Array)).join(', '),
  )
  console.log(`wrote ${outDir}/pre-rendered-triangle.png and pre-rendered-triangle-16.png`)

  const decoded = decodePng(readFileSync(`${outDir}/pre-rendered-triangle.png`))
  const source: PixelImage = { ...decoded, format: 'rgba8unorm' }
  const back = await roundtrip(gpu, source)
  const same = back.data.every((v, i) => v === source.data[i])
  console.log(`PNG → PixelImage source → show: ${same ? 'byte for byte' : 'DIFFERS'}`)
}

