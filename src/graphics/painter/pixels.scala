package trivalibs.graphics.painter

import trivalibs.utils.js.*

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

/** ES2025 `Float16Array`: elements read and write as plain JS numbers, stored
  * as IEEE half floats. Baseline since 2025 (Chrome 135, Firefox 129, Safari
  * 18.2), Node 24, Bun 1.4.
  */
@js.native
@JSGlobal
class Float16Array(length: Int) extends TypedArray[Double, Float16Array]:
  /** Creates a Float16Array view on the given ArrayBuffer. */
  def this(buffer: ArrayBuffer, byteOffset: Int, length: Int) = this(0)

/** Pixel format of a [[PixelFrames]] / [[PixelImage]] target — exactly three,
  * each matching one native typed array (see [[PixelData]]):
  *   - `Rgba8Unorm` — `Uint8Array`, 4 bytes / px: display frames, 8-bit PNG,
  *     video.
  *   - `Rgba16Float` — `Float16Array`, 8 bytes / px: HDR stills and sequences.
  *   - `Rgba32Float` — `Float32Array`, 16 bytes / px: data bakes, numeric
  *     processing. Needs the `float32-filterable` device feature.
  */
opaque type PixelFormat = String
object PixelFormat:
  val Rgba8Unorm: PixelFormat = "rgba8unorm"
  val Rgba16Float: PixelFormat = "rgba16float"
  val Rgba32Float: PixelFormat = "rgba32float"

  extension (f: PixelFormat)
    inline def toJs: js.Any = f.asInstanceOf[js.Any]
    private[painter] inline def asString: String = f
    private[painter] inline def textureFormat: TextureFormat =
      TextureFormat.of(f)

    /** 4, 8 or 16; 0 for a string that is not one of the three formats. */
    private[painter] def bytesPerPixel: Int =
      if f == Rgba8Unorm then 4
      else if f == Rgba16Float then 8
      else if f == Rgba32Float then 16
      else 0

/** The typed array of one image, matching its [[PixelFormat]]: rows tightly
  * packed, top row first, RGBA, straight alpha, length `width · height · 4`.
  */
type PixelData = Uint8Array | Float16Array | Float32Array

object PixelData:
  /** Channels per pixel. Every [[PixelFormat]] is RGBA, so the array type alone
    * determines the format.
    */
  inline val Channels = 4

  /** Zeroed data for `pixelCount` pixels of `format`. */
  private[painter] def alloc(format: PixelFormat, pixelCount: Int): PixelData =
    val length = pixelCount * Channels
    if format == PixelFormat.Rgba32Float then new Float32Array(length)
    else if format == PixelFormat.Rgba16Float then new Float16Array(length)
    else new Uint8Array(length)

  /** Element count of `data` — channels, not pixels. */
  private[painter] def length(data: PixelData): Int =
    data.asInstanceOf[js.Dynamic].length.asInstanceOf[Int]

  /** Fills `dst` (`width` × `height` pixels of `format`) from `src`, whose rows
    * start every `bytesPerRow` bytes — a mapped readback buffer, rows padded to
    * 256 bytes. One bulk copy when the rows are not padded.
    */
  private[painter] def copyRows(
      src: ArrayBuffer,
      dst: PixelData,
      format: PixelFormat,
      width: Int,
      height: Int,
      bytesPerRow: Int,
  ): Unit =
    import PixelFormat.bytesPerPixel
    val target = dst.asInstanceOf[js.Dynamic]
    if bytesPerRow == width * format.bytesPerPixel then
      target.set(view(format, src, 0, width * height))
    else
      var y = 0
      while y < height do
        target.set(
          view(format, src, y * bytesPerRow, width),
          y * width * Channels,
        )
        y += 1

  // `pixelCount` pixels of `format`, starting `byteOffset` into `buffer`.
  private def view(
      format: PixelFormat,
      buffer: ArrayBuffer,
      byteOffset: Int,
      pixelCount: Int,
  ): PixelData =
    val length = pixelCount * Channels
    if format == PixelFormat.Rgba32Float then
      new Float32Array(buffer, byteOffset, length)
    else if format == PixelFormat.Rgba16Float then
      new Float16Array(buffer, byteOffset, length)
    else new Uint8Array(buffer, byteOffset, length)

  /** Whether `data` is the array type `format` uses. Checks `Uint8Array` and
    * `Float32Array` first, so `Float16Array` is only referenced for the half
    * float format.
    */
  private[painter] def matches(format: PixelFormat, data: PixelData): Boolean =
    if format == PixelFormat.Rgba8Unorm then data.isInstanceOf[Uint8Array]
    else if format == PixelFormat.Rgba32Float then
      data.isInstanceOf[Float32Array]
    else
      // `typeof` on the bare global: a plain read of a missing one throws.
      js.typeOf(js.Dynamic.global.Float16Array) != "undefined" &&
      js.special.instanceof(data, js.Dynamic.global.Float16Array)

/** A sequence of frames — a pixel target passed to [[Painter.init]] in place of
  * a canvas. Every `show` appends one frame to `frames`, each `width` ×
  * `height` in `format`. A plain object literal of this shape works as well
  * (e.g. created in TypeScript); the factory only builds one.
  *
  * The frames are complete only once [[Painter.awaitReadback]] resolves.
  */
trait PixelFrames extends js.Object:
  val width: Int
  val height: Int
  val format: PixelFormat
  val frames: Arr[PixelData]

object PixelFrames:
  def apply(
      width: Int,
      height: Int,
      format: PixelFormat = PixelFormat.Rgba8Unorm,
  ): PixelFrames =
    Obj
      .literal(
        width = width,
        height = height,
        format = format.toJs,
        frames = Arr[PixelData](),
      )
      .asInstanceOf[PixelFrames]

  extension (sequence: PixelFrames)
    /** Frame `index` as a [[PixelImage]] of the sequence's size and format,
      * sharing its data — e.g. to set a decoded video frame as a panel
      * `source`. Throws for an index outside `frames`.
      */
    def pixelsAt(index: Int): PixelImage =
      if index < 0 || index >= sequence.frames.length then
        throw jsError(
          s"PixelFrames.pixelsAt: index $index outside 0 until ${sequence.frames.length}",
        )
      PixelImage.literal(
        sequence.width,
        sequence.height,
        sequence.format,
        sequence.frames(index),
      )

/** A single image. As a pixel target passed to [[Painter.init]], every `show`
  * overwrites `data` in place, so the reference stays valid and a host buffer
  * (e.g. pngjs's `png.data`) can be the target directly. `data` must have the
  * array type of `format` and length `width · height · 4`. A plain object
  * literal of this shape works as well.
  *
  * The image is complete only once [[Painter.awaitReadback]] resolves.
  */
trait PixelImage extends js.Object:
  val width: Int
  val height: Int
  val format: PixelFormat
  val data: PixelData

object PixelImage:
  /** Zeroed `data` of the right type and length — the usual way to make an
    * output target.
    */
  def apply(
      width: Int,
      height: Int,
      format: PixelFormat = PixelFormat.Rgba8Unorm,
  ): PixelImage =
    literal(width, height, format, PixelData.alloc(format, width * height))

  /** Wraps existing data; the format follows the array type. Throws if the
    * length is not `width · height · 4`.
    */
  def apply(width: Int, height: Int, data: PixelData): PixelImage =
    val format =
      if data.isInstanceOf[Uint8Array] then PixelFormat.Rgba8Unorm
      else if data.isInstanceOf[Float32Array] then PixelFormat.Rgba32Float
      else PixelFormat.Rgba16Float
    val length = PixelData.length(data)
    if length != width * height * PixelData.Channels then
      throw jsError(
        s"PixelImage: data length $length is not width · height · 4 = ${width * height * PixelData.Channels}",
      )
    literal(width, height, format, data)

  private[painter] def literal(
      width: Int,
      height: Int,
      format: PixelFormat,
      data: PixelData,
  ): PixelImage =
    Obj
      .literal(
        width = width,
        height = height,
        format = format.toJs,
        data = data.asInstanceOf[js.Any],
      )
      .asInstanceOf[PixelImage]

/** What an offscreen painter renders into, and what [[Painter.showTo]] writes:
  * [[PixelFrames]] (append per `show`) or [[PixelImage]] (overwrite per
  * `show`).
  */
type PixelTarget = PixelFrames | PixelImage

object PixelTarget:
  /** Whether `x` has the shape of a pixel target: a `frames` or a `data` field.
    * Validity is [[validate]]'s job.
    */
  private[painter] def isPixelTarget(x: Any): Boolean =
    val obj = x.asInstanceOf[js.Object]
    js.special.in("frames", obj) || js.special.in("data", obj)

  /** Throws, prefixed with `where`, unless `target` is well-formed: one of the
    * three formats, a positive size, and an array `frames` or a `data` array of
    * the format's type and length `width · height · 4`. The painter runs it on
    * every pixel target it gets; call it yourself on targets built or received
    * elsewhere (from JS, a worker, a file) before handing them on.
    */
  def validate(
      target: PixelTarget,
      where: String = "PixelTarget.validate",
  ): Unit =
    import PixelFormat.{bytesPerPixel, toJs}
    val image = target.asInstanceOf[PixelImage]
    val width = image.width
    val height = image.height
    val format = image.format
    if format.bytesPerPixel == 0 then
      throw jsError(
        s"$where: unsupported pixel target format '${format.toJs}'; " +
          "use rgba8unorm, rgba16float or rgba32float",
      )
    if !(width > 0 && height > 0) then
      throw jsError(s"$where: invalid pixel target size ${width}×$height")
    if js.special.in("frames", target) then
      if !js.Array.isArray(target.asInstanceOf[PixelFrames].frames) then
        throw jsError(s"$where: PixelFrames.frames must be an array")
    else
      val data = image.data
      if js.isUndefined(data) || !PixelData.matches(format, data) then
        throw jsError(
          s"$where: PixelImage.data must be the array type of '${format.toJs}' " +
            "(Uint8Array / Float16Array / Float32Array)",
        )
      val length = PixelData.length(data)
      if length != width * height * PixelData.Channels then
        throw jsError(
          s"$where: PixelImage.data length $length is not width · height · 4 = ${width * height * PixelData.Channels}",
        )
