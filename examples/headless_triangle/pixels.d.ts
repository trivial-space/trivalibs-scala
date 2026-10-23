// The painter's data boundary, as a TS host declares it. Plain object
// literals of these shapes are valid painter targets; nothing needs importing
// from the Scala side to create them.

export type PixelFormat = 'rgba8unorm' | 'rgba16float' | 'rgba32float'

/** Matches `format`: rows tightly packed, top row first, RGBA, length w·h·4. */
export type PixelData = Uint8Array | Float16Array | Float32Array

/** A sequence: the painter appends one frame per `show()`. */
export interface PixelFrames {
  width: number
  height: number
  format: PixelFormat
  frames: PixelData[]
}

/** A single image: the painter overwrites `data` in place per `show()`. */
export interface PixelImage {
  width: number
  height: number
  format: PixelFormat
  data: PixelData
}
