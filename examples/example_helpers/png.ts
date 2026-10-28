// Minimal PNG encoder and decoder for the headless example drivers, over
// `node:zlib` — keeps `webgpu` the drivers' only dependency. The encoder writes
// RGBA, 8 or 16 bit, no filtering; 16-bit input is float data, clamped to
// [0, 1]. The decoder reads 8-bit RGBA, non-interlaced.

import { deflateSync, inflateSync } from 'node:zlib'

export function encodePng(w: number, h: number, data: ArrayLike<number>, depth: 8 | 16): Uint8Array {
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

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})

function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff
  for (const b of bytes) c = crcTable[(c ^ b) & 255] ^ (c >>> 8)
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

export function decodePng(png: Uint8Array): { width: number; height: number; data: Uint8Array } {
  const dv = new DataView(png.buffer, png.byteOffset, png.byteLength)
  let width = 0
  let height = 0
  const idat: Uint8Array[] = []
  let o = 8
  while (o < png.length) {
    const length = dv.getUint32(o)
    const type = String.fromCharCode(...png.subarray(o + 4, o + 8))
    const body = png.subarray(o + 8, o + 8 + length)
    if (type === 'IHDR') {
      width = dv.getUint32(o + 8)
      height = dv.getUint32(o + 12)
      if (body[8] !== 8 || body[9] !== 6 || body[12] !== 0)
        throw new Error('decodePng: only 8-bit RGBA, non-interlaced')
    } else if (type === 'IDAT') idat.push(body)
    o += 12 + length
  }
  const raw = inflateSync(concat(idat))
  const stride = width * 4
  const data = new Uint8Array(stride * height)
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1))
    for (let i = 0; i < stride; i++) {
      const a = i >= 4 ? data[y * stride + i - 4] : 0
      const b = y > 0 ? data[(y - 1) * stride + i] : 0
      const c = i >= 4 && y > 0 ? data[(y - 1) * stride + i - 4] : 0
      const predicted =
        filter === 0 ? 0
        : filter === 1 ? a
        : filter === 2 ? b
        : filter === 3 ? (a + b) >> 1
        : paeth(a, b, c)
      data[y * stride + i] = (line[i] + predicted) & 255
    }
  }
  return { width, height, data }
}

function paeth(a: number, b: number, c: number): number {
  const p = a + b - c
  const pa = Math.abs(p - a)
  const pb = Math.abs(p - b)
  const pc = Math.abs(p - c)
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c
}
