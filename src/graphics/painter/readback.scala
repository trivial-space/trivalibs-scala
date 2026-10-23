package trivalibs.graphics.painter

import trivalibs.utils.js.*

import scala.scalajs.js
import scala.scalajs.js.typedarray.ArrayBuffer

/** GPU → JS readback into [[PixelTarget]] containers. Owns the capture
  * textures (one per size and format) and a pool of staging buffers. Every
  * read is eager: it submits right away and lands in its container once
  * `mapAsync` resolves.
  */
private[painter] final class Readback(device: GPUDevice):
  private val captureTextures = Dict[GPUTexture]()
  private val stagingPool = Dict[Arr[GPUBuffer]]()
  private val pending = Arr[js.Promise[Unit]]()
  private var submitted = 0
  // Last submission index written per PixelImage, so out-of-order completions
  // never overwrite a newer result. Weak, so it never holds a container alive.
  private val imageWrites = js.Dynamic
    .newInstance(js.Dynamic.global.WeakMap)()
    .asInstanceOf[WeakIntMap]

  /** The scratch render target for a container of this size and format. */
  def captureTexture(width: Int, height: Int, format: PixelFormat): GPUTexture =
    val key = s"$width|$height|${format.toJs}"
    val cached = captureTextures.at(key)
    if !js.isUndefined(cached.asInstanceOf[js.Any]) then cached
    else
      val tex = device.createTexture(
        Obj.literal(
          size = Obj.literal(width = width, height = height),
          format = format.toJs,
          usage = GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.COPY_SRC,
        ),
      )
      captureTextures.set(key, tex)
      tex

  /** Append the copy of `texture` to `encoder`, submit it, and land the pixels
    * in `target`: one frame appended (frames) or `data` overwritten (image).
    */
  def read(
      encoder: GPUCommandEncoder,
      texture: GPUTexture,
      target: PixelTarget,
  ): js.Promise[Unit] =
    val image = target.asInstanceOf[PixelImage]
    val width = image.width
    val height = image.height
    val format = image.format
    val rowBytes = width * format.bytesPerPixel
    val bytesPerRow = (rowBytes + 255) & ~255
    val size = bytesPerRow * height
    val buffer = acquire(size)

    encoder.copyTextureToBuffer(
      Obj.literal(texture = texture),
      Obj.literal(
        buffer = buffer,
        bytesPerRow = bytesPerRow,
        rowsPerImage = height,
      ),
      Obj.literal(width = width, height = height),
    )
    device.queue.submit(Arr(encoder.finish()))

    submitted += 1
    val index = submitted
    val frames: Opt[Arr[PixelData]] =
      if js.Object.hasProperty(target.asInstanceOf[js.Object], "frames") then
        target.asInstanceOf[PixelFrames].frames
      else null
    // Reserve the frame's slot now, so frames land in `show` order even when
    // completions don't.
    val frameSlot =
      if frames.notNull then frames.get.push(null.asInstanceOf[PixelData]) - 1
      else -1

    val done = buffer
      .mapAsync(GPUMapMode.READ)
      .map: _ =>
        val mapped = buffer.getMappedRange()
        if frames.notNull then
          val data = PixelData.alloc(format, width * height * 4)
          copyRows(mapped, data, format, width, height, bytesPerRow)
          frames.get(frameSlot) = data
        else
          val last = imageWrites.get(image)
          if js.isUndefined(last) || last.asInstanceOf[Int] < index then
            copyRows(mapped, image.data, format, width, height, bytesPerRow)
            imageWrites.set(image, index)
        buffer.unmap()
        release(size, buffer)
    pending.push(done)
    val forget: js.Function1[Any, Unit] = _ =>
      val i = pending.indexOf(done)
      if i >= 0 then pending.splice(i, 1)
    done.`then`[Unit](forget, forget)
    done

  /** Resolves once every read submitted so far — and any submitted while
    * waiting — has landed.
    */
  def awaitAll(): js.Promise[Unit] =
    if pending.length == 0 then js.Promise.resolve[Unit](())
    else js.Promise.all(Arr.from(pending)).`then`[Unit](_ => awaitAll())

  // Unpad the 256-aligned rows into the tightly packed `dst`. `getMappedRange`
  // is detached on `unmap`, so this copy is required either way.
  private def copyRows(
      mapped: ArrayBuffer,
      dst: PixelData,
      format: PixelFormat,
      width: Int,
      height: Int,
      bytesPerRow: Int,
  ): Unit =
    val rowElems = width * 4
    val target = dst.asInstanceOf[js.Dynamic]
    if bytesPerRow == width * format.bytesPerPixel then
      target.set(PixelData.view(format, mapped, 0, rowElems * height))
    else
      var y = 0
      while y < height do
        target.set(
          PixelData.view(format, mapped, y * bytesPerRow, rowElems),
          y * rowElems,
        )
        y += 1

  private def acquire(size: Int): GPUBuffer =
    val pool = stagingPool.at(size.toString)
    if !js.isUndefined(pool.asInstanceOf[js.Any]) && pool.length > 0 then
      pool.pop()
    else
      device.createBuffer(
        Obj.literal(
          size = size,
          usage = GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST,
        ),
      )

  private def release(size: Int, buffer: GPUBuffer): Unit =
    val key = size.toString
    val pool = stagingPool.at(key)
    if js.isUndefined(pool.asInstanceOf[js.Any]) then stagingPool.set(key, Arr(buffer))
    else pool.push(buffer)

// A JS `WeakMap` from objects to ints.
@js.native
private[painter] trait WeakIntMap extends js.Object:
  def get(key: js.Object): js.UndefOr[Int] = js.native
  def set(key: js.Object, value: Int): Unit = js.native

/** The surface of a painter created on a [[PixelTarget]] container. Not
  * user-facing: [[Painter.create]] wraps the container in it. `show` renders
  * into a capture texture of the container's size and format, and `present`
  * reads it back into the container.
  */
private[painter] final class CaptureSurface(
    val target: PixelTarget,
    readback: Readback,
) extends Surface:
  private val image = target.asInstanceOf[PixelImage]
  val width: Int = image.width
  val height: Int = image.height
  val pixelFormat: PixelFormat = image.format
  val format: TextureFormat = pixelFormat.textureFormat

  if pixelFormat.bytesPerPixel == 0 then
    throw jsError(
      s"Painter.create: unsupported container format '${pixelFormat.toJs}'; " +
        "use rgba8unorm, rgba16float or rgba32float",
    )
  if !(width > 0 && height > 0) then
    throw jsError(s"Painter.create: invalid container size ${width}×$height")
  if js.Object.hasProperty(target.asInstanceOf[js.Object], "frames") then
    if !js.Array.isArray(target.asInstanceOf[PixelFrames].frames) then
      throw jsError("Painter.create: PixelFrames.frames must be an array")
  else
    val data = image.data
    if js.isUndefined(data) || !PixelData.matches(pixelFormat, data) then
      throw jsError(
        s"Painter.create: PixelImage.data must be the array type of '${pixelFormat.toJs}' " +
          "(Uint8Array / Float16Array / Float32Array)",
      )
    val length = data.asInstanceOf[js.Dynamic].length.asInstanceOf[Int]
    if length != width * height * 4 then
      throw jsError(
        s"Painter.create: PixelImage.data length $length is not width · height · 4 = ${width * height * 4}",
      )

  def configure(device: GPUDevice): Unit =
    if pixelFormat == PixelFormat.Rgba32Float &&
      !device.features.has("float32-filterable")
    then
      throw jsError(
        "Painter.create: an rgba32float container needs the device feature " +
          "'float32-filterable'. Pass requiredFeatures = " +
          "Arr(\"float32-filterable\") to Painter.create, or use rgba16float.",
      )

  def currentTexture(): GPUTexture =
    readback.captureTexture(width, height, pixelFormat)

  def present(encoder: GPUCommandEncoder): Unit =
    readback.read(encoder, currentTexture(), target)
    ()

  def onResize(cb: (Int, Int) => Unit): Unit = ()
