package trivalibs.graphics.painter

import org.scalajs.dom
import trivalibs.utils.js.*

import scala.scalajs.js
import scala.scalajs.js.typedarray.ArrayBufferView

/** WebCodecs `VideoFrame`, as a type only: scalajs-dom has none, and the
  * painter never references the global.
  */
@js.native
trait VideoFrame extends js.Object:
  val displayWidth: Int = js.native
  val displayHeight: Int = js.native
  def close(): Unit = js.native

/** Browser-native images a panel can take as its source. Types only: the
  * painter never `instanceof`-checks them, so none of these globals has to
  * exist outside a browser.
  */
type ExternalImage =
  dom.ImageBitmap | dom.ImageData | dom.HTMLImageElement |
    dom.HTMLCanvasElement | dom.OffscreenCanvas | dom.HTMLVideoElement |
    VideoFrame

/** What a panel can show as its `source`: raw pixels as a [[PixelImage]] (the
  * same exchange format the offscreen painter writes), or a browser-native
  * [[ExternalImage]].
  */
type TextureSource = PixelImage | ExternalImage

private[painter] object TextureSource:
  /** A [[PixelImage]] has both `data` and `format`. `ImageData` has no
    * `format`, a `VideoFrame` no `data`, so both take the external path.
    */
  def isPixelImage(source: TextureSource): Boolean =
    val obj = source.asInstanceOf[js.Object]
    js.Object.hasProperty(obj, "data") && js.Object.hasProperty(obj, "format")

  /** The pixel size of an external image, duck-typed in this order: a
    * `<video>` and an `<img>` also have a `width`, but it is their layout
    * size.
    */
  def width(source: ExternalImage): Int =
    size(source, "videoWidth", "naturalWidth", "displayWidth", "width")

  def height(source: ExternalImage): Int =
    size(source, "videoHeight", "naturalHeight", "displayHeight", "height")

  /** Writes `source` into mip 0 of `texture`, which has its size. A
    * [[PixelImage]] must match the texture's format; an external image is
    * converted, with `flipY` / `premultipliedAlpha` applied.
    */
  def upload(
      queue: GPUQueue,
      source: TextureSource,
      texture: GPUTexture,
      width: Int,
      height: Int,
      flipY: Boolean,
      premultipliedAlpha: Boolean,
  ): Unit =
    import PixelFormat.bytesPerPixel
    if isPixelImage(source) then
      val image = source.asInstanceOf[PixelImage]
      // The underlying buffer, not the typed array: a `Float16Array` view is
      // not accepted by every WebGPU implementation yet.
      val data = image.data.asInstanceOf[ArrayBufferView]
      queue.writeTexture(
        Obj.literal(texture = texture),
        data.buffer,
        Obj.literal(
          offset = data.byteOffset,
          bytesPerRow = width * image.format.bytesPerPixel,
          rowsPerImage = height,
        ),
        Obj.literal(width = width, height = height),
      )
    else
      queue.copyExternalImageToTexture(
        Obj.literal(source = source.asInstanceOf[js.Any], flipY = flipY),
        Obj.literal(
          texture = texture,
          premultipliedAlpha = premultipliedAlpha,
        ),
        Obj.literal(width = width, height = height),
      )

  private def size(
      source: ExternalImage,
      a: String,
      b: String,
      c: String,
      d: String,
  ): Int =
    val obj = source.asInstanceOf[js.Dynamic]
    val value =
      if !js.isUndefined(obj.selectDynamic(a)) then obj.selectDynamic(a)
      else if !js.isUndefined(obj.selectDynamic(b)) then obj.selectDynamic(b)
      else if !js.isUndefined(obj.selectDynamic(c)) then obj.selectDynamic(c)
      else obj.selectDynamic(d)
    if js.isUndefined(value) then 0 else value.asInstanceOf[Int]

/** Image loading for browser sketches. */
object WebImages:
  /** Fetches `url` and decodes it into an `ImageBitmap`, ready as a panel
    * `source`. By default the pixels arrive bit-exact — no color-space
    * conversion and straight alpha — as baked data textures need. Pass
    * `colorManaged = true` for a photo that should be converted to the display
    * color space. Rejects on an HTTP error.
    */
  def load(
      url: String,
      colorManaged: Boolean = false,
  ): js.Promise[dom.ImageBitmap] =
    js.Dynamic.global
      .fetch(url)
      .asInstanceOf[js.Promise[js.Dynamic]]
      .flatMap: response =>
        if !response.ok.asInstanceOf[Boolean] then
          js.Promise.reject(
            js.Error(s"WebImages.load: $url → HTTP ${response.status}"),
          )
        else response.blob().asInstanceOf[js.Promise[js.Dynamic]]
      .flatMap: blob =>
        js.Dynamic.global
          .createImageBitmap(
            blob,
            Obj.literal(
              colorSpaceConversion = if colorManaged then "default" else "none",
              premultiplyAlpha = "none",
            ),
          )
          .asInstanceOf[js.Promise[dom.ImageBitmap]]
