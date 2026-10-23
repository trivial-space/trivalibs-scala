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
// Writes to examples/headless_triangle/out/:
//   triangle.png      rgba8unorm PixelImage → 8-bit PNG
//   triangle-16.png   rgba16float PixelImage, intensity 4 (HDR) → 16-bit PNG,
//                     clamped to [0, 1]
// and checks a 3-frame PixelFrames sequence.

import { mkdirSync, writeFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { create } from 'webgpu'
import { render } from '../out/headless_triangle.js'
import type { PixelFrames, PixelImage } from './pixels.d.ts'

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
  const outDir = 'examples/headless_triangle/out'
  mkdirSync(outDir, { recursive: true })

  const img8: PixelImage = {
    width: size,
    height: size,
    format: 'rgba8unorm',
    data: new Uint8Array(size * size * 4),
  }
  await render(gpu, img8, 1, 1, software, requireName)
  writeFileSync(`${outDir}/triangle.png`, encodePng(size, size, img8.data, 8))

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
  writeFileSync(`${outDir}/triangle-16.png`, encodePng(size, size, img16.data, 16))

  const seq: PixelFrames = { width: size, height: size, format: 'rgba8unorm', frames: [] }
  await render(gpu, seq, 3, 1, software, requireName)
  const center = (f: Uint8Array) => f[((size / 2) * size + size / 2) * 4]
  console.log(
    `PixelFrames: ${seq.frames.length} frames, center red per frame:`,
    seq.frames.map((f) => center(f as Uint8Array)).join(', '),
  )
  console.log(`wrote ${outDir}/triangle.png and triangle-16.png`)
}

// ---- minimal PNG encoder: RGBA, 8 or 16 bit, no filtering ----

function encodePng(w: number, h: number, data: ArrayLike<number>, depth: 8 | 16): Uint8Array {
  const bpc = depth / 8
  const stride = w * 4 * bpc + 1
  const raw = new Uint8Array(stride * h)
  for (let y = 0; y < h; y++) {
    for (let i = 0; i < w * 4; i++) {
      const v = data[y * w * 4 + i]
      const o = y * stride + 1 + i * bpc
      if (depth === 8) raw[o] = v
      else {
        const q = Math.round(Math.min(1, Math.max(0, v)) * 65535)
        raw[o] = q >> 8
        raw[o + 1] = q & 255
      }
    }
  }
  const ihdr = new Uint8Array(13)
  const dv = new DataView(ihdr.buffer)
  dv.setUint32(0, w)
  dv.setUint32(4, h)
  ihdr.set([depth, 6, 0, 0, 0], 8)
  return concat([
    new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', new Uint8Array(0)),
  ])
}

function chunk(type: string, body: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + body.length)
  const dv = new DataView(out.buffer)
  dv.setUint32(0, body.length)
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i)
  out.set(body, 8)
  dv.setUint32(8 + body.length, crc32(out.subarray(4, 8 + body.length)))
  return out
}

// A function, not a const: the top-level awaits above run before any const
// declared down here is initialized.
function crc32(bytes: Uint8Array): number {
  const table = Array.from({ length: 256 }, (_, n) => {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    return c >>> 0
  })
  let c = 0xffffffff
  for (const b of bytes) c = table[(c ^ b) & 255] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let o = 0
  for (const p of parts) {
    out.set(p, o)
    o += p.length
  }
  return out
}
