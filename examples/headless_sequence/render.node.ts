// Headless motion sequence for node-webgpu — runs on Node 24 and on Bun, from
// the repo root after `bun run examples:build`. Needs `ffmpeg` on the PATH.
//
//   node examples/headless_sequence/render.node.ts
//
// Writes to examples/headless_sequence/assets/:
//   sequence.mp4   PixelFrames, 48 frames at 24 fps (2 s), h264
//   sequence.gif   the same frames as a looping gif
//   preview.png    a smaller still, rendered at its own size (atSize + showTo)

import { mkdirSync, writeFileSync } from 'node:fs'
import { create } from 'webgpu'
import { encodeVideo } from '../example_helpers/ffmpeg.ts'
import { encodePng } from '../example_helpers/png.ts'
import { render } from '../out/headless_sequence.js'
import type { PixelFrames, PixelImage } from '../example_helpers/pixels.d.ts'

const width = 256
const height = 144
const fps = 24
const frameCount = 48
const outDir = 'examples/headless_sequence/assets'
mkdirSync(outDir, { recursive: true })

const out: PixelFrames = { width, height, format: 'rgba8unorm', frames: [] }
const preview: PixelImage = {
  width: 128,
  height: 72,
  format: 'rgba8unorm',
  data: new Uint8Array(128 * 72 * 4),
}
await render(create([]), out, preview, fps, frameCount)

const frames = out.frames as Uint8Array[]
await encodeVideo(frames, width, height, fps, `${outDir}/sequence.mp4`)
await encodeVideo(frames, width, height, fps, `${outDir}/sequence.gif`)
writeFileSync(`${outDir}/preview.png`, encodePng(preview.width, preview.height, preview.data, 8))

console.log(`wrote ${frames.length} frames to ${outDir}/sequence.mp4 and sequence.gif, and preview.png`)
