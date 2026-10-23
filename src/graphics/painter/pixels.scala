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

/** Pixel format of a [[PixelFrames]] / [[PixelImage]] container — exactly
  * three, each matching one native typed array (see [[PixelData]]):
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
    private[painter] inline def textureFormat: TextureFormat = TextureFormat.of(f)
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
  /** A zeroed array of the type `format` uses. */
  private[painter] def alloc(format: PixelFormat, length: Int): PixelData =
    if format == PixelFormat.Rgba8Unorm then new Uint8Array(length)
    else if format == PixelFormat.Rgba32Float then new Float32Array(length)
    else new Float16Array(length)

  /** A view of `length` elements onto `buffer`, typed for `format`. */
  private[painter] def view(
      format: PixelFormat,
      buffer: ArrayBuffer,
      byteOffset: Int,
      length: Int,
  ): PixelData =
    if format == PixelFormat.Rgba8Unorm then
      new Uint8Array(buffer, byteOffset, length)
    else if format == PixelFormat.Rgba32Float then
      new Float32Array(buffer, byteOffset, length)
    else new Float16Array(buffer, byteOffset, length)

  /** Whether `data` is the array type `format` uses. Checks `Uint8Array` and
    * `Float32Array` first, so `Float16Array` is only referenced for the half
    * float format.
    */
  private[painter] def matches(format: PixelFormat, data: PixelData): Boolean =
    if format == PixelFormat.Rgba8Unorm then data.isInstanceOf[Uint8Array]
    else if format == PixelFormat.Rgba32Float then data.isInstanceOf[Float32Array]
    else
      // `typeof` on the bare global: a plain read of a missing one throws.
      js.typeOf(js.Dynamic.global.Float16Array) != "undefined" &&
      js.special.instanceof(data, js.Dynamic.global.Float16Array)

/** A sequence of frames — an output container passed to [[Painter.create]] in
  * place of a canvas. Every `show` appends one frame to `frames`, each
  * `width` × `height` in `format`. A plain object literal of this shape works
  * as well (e.g. created in TypeScript); the factory only builds one.
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

/** A single image. As an output container passed to [[Painter.create]], every
  * `show` overwrites `data` in place, so the reference stays valid and a host
  * buffer (e.g. pngjs's `png.data`) can be the target directly. `data` must
  * have the array type of `format` and length `width · height · 4`. A plain
  * object literal of this shape works as well.
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
    literal(width, height, format, PixelData.alloc(format, width * height * 4))

  /** Wraps existing data; the format follows the array type. Throws if the
    * length is not `width · height · 4`.
    */
  def apply(width: Int, height: Int, data: PixelData): PixelImage =
    val format =
      if data.isInstanceOf[Uint8Array] then PixelFormat.Rgba8Unorm
      else if data.isInstanceOf[Float32Array] then PixelFormat.Rgba32Float
      else PixelFormat.Rgba16Float
    val length = data.asInstanceOf[js.Dynamic].length.asInstanceOf[Int]
    if length != width * height * 4 then
      throw jsError(
        s"PixelImage: data length $length is not width · height · 4 = ${width * height * 4}",
      )
    literal(width, height, format, data)

  private def literal(
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

/** An output container: [[PixelFrames]] (append per `show`) or [[PixelImage]]
  * (overwrite per `show`).
  */
type PixelTarget = PixelFrames | PixelImage
