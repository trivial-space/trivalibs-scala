// Browser host glue: saves an rgba8unorm PixelImage as a PNG download. The
// browser encodes it (2D canvas → toBlob); `data` is wrapped, not copied.

export function downloadPng(image, filename) {
  const { width, height, data } = image
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const pixels = new Uint8ClampedArray(data.buffer, data.byteOffset, data.length)
  canvas.getContext('2d').putImageData(new ImageData(pixels, width, height), 0, 0)
  canvas.toBlob((blob) => {
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = filename
    link.click()
    URL.revokeObjectURL(link.href)
  }, 'image/png')
}
