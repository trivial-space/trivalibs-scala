// Encodes rgba8unorm frames to mp4 or gif with an `ffmpeg` subprocess, raw
// frames on stdin. The container follows the file extension; gif gets a
// per-file palette in one pass. Needs `ffmpeg` on the PATH.

import { spawn } from 'node:child_process'
import { once } from 'node:events'

export async function encodeVideo(
  frames: Uint8Array[],
  width: number,
  height: number,
  fps: number,
  path: string,
): Promise<void> {
  const output = path.endsWith('.gif')
    ? ['-filter_complex', 'split[a][b];[a]palettegen[p];[b][p]paletteuse=dither=bayer']
    : ['-c:v', 'libx264', '-pix_fmt', 'yuv420p']
  const ff = spawn(
    'ffmpeg',
    [
      '-y',
      '-loglevel',
      'error',
      '-f',
      'rawvideo',
      '-pix_fmt',
      'rgba',
      '-s',
      `${width}x${height}`,
      '-r',
      `${fps}`,
      '-i',
      '-',
      ...output,
      path,
    ],
    { stdio: ['pipe', 'inherit', 'inherit'] },
  )
  const closed = once(ff, 'close')
  for (const f of frames) {
    if (!ff.stdin.write(f)) await once(ff.stdin, 'drain')
  }
  ff.stdin.end()
  const [code] = await closed
  if (code !== 0) throw new Error(`ffmpeg exited with ${code} for ${path}`)
}
