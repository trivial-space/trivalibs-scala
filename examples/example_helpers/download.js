// Browser host glue: saves an rgba8unorm PixelImage as a PNG download.
// `data` is wrapped, not copied, into the 2D canvas; `toBlob(cb, 'image/png')`
// is the PNG encoder — PNG is the one type every browser must support there.

export function downloadPng(image, filename) {
	const { width, height, data } = image

	const pixels = new Uint8ClampedArray(
		data.buffer,
		data.byteOffset,
		data.length,
	)

	const canvas = document.createElement('canvas')
	canvas.width = width
	canvas.height = height
	canvas
		.getContext('2d')
		.putImageData(new ImageData(pixels, width, height), 0, 0)

	canvas.toBlob((blob) => {
		const link = document.createElement('a')
		link.href = URL.createObjectURL(blob)
		link.download = filename
		link.click()
		URL.revokeObjectURL(link.href)
	}, 'image/png')
}
