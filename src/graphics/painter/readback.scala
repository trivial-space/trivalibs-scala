package trivalibs.graphics.painter

import trivalibs.utils.js.*

import scala.scalajs.js

/** GPU → JS readback into [[PixelTarget]]s. Owns the staging buffers: pooled
  * per size for reads that repeat every frame, or destroyed after a one-off
  * read. Every read is eager: it submits right away and lands in its target
  * once `mapAsync` resolves.
  */
private[painter] final class Readback(device: GPUDevice):
  private val stagingPool = Dict[Arr[GPUBuffer]]()
  private val pending = Arr[js.Promise[Unit]]()
  private var submitted = 0
  // Last submission index written per PixelImage, so out-of-order completions
  // never overwrite a newer result. Weak, so it never holds a target alive.
  private val imageWrites = js.Dynamic
    .newInstance(js.Dynamic.global.WeakMap)()
    .asInstanceOf[WeakIntMap]

  /** Append the copy of `texture` to `encoder`, submit it, and land the pixels
    * in `target`: one frame appended (frames) or `data` overwritten (image).
    * `pooled` keeps the staging buffer for the next read of the same size;
    * otherwise it is destroyed once the pixels have landed.
    */
  def read(
      encoder: GPUCommandEncoder,
      texture: GPUTexture,
      target: PixelTarget,
      pooled: Boolean,
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
          val data = PixelData.alloc(format, width * height)
          PixelData.copyRows(mapped, data, format, width, height, bytesPerRow)
          frames.get(frameSlot) = data
        else
          val last = imageWrites.get(image)
          if js.isUndefined(last) || last.asInstanceOf[Int] < index then
            PixelData.copyRows(mapped, image.data, format, width, height, bytesPerRow)
            imageWrites.set(image, index)
        buffer.unmap()
        if pooled then release(size, buffer)
        else buffer.destroy()
    pending.push(done)
    val forget: js.Function1[Any, Unit] = _ =>
      val i = pending.indexOf(done)
      if i >= 0 then pending.splice(i, 1)
    done.`then`[Unit](forget, forget)
    done

  /** Destroys every pooled staging buffer. Reads in flight hold theirs outside
    * the pool, so they are unaffected; later reads allocate anew.
    */
  def freeStaging(): Unit =
    val keys = stagingPool.keys
    var k = 0
    while k < keys.length do
      val pool = stagingPool.at(keys(k))
      var i = 0
      while i < pool.length do
        pool(i).destroy()
        i += 1
      stagingPool.delete(keys(k))
      k += 1

  /** Resolves once every read submitted so far — and any submitted while
    * waiting — has landed.
    */
  def awaitAll(): js.Promise[Unit] =
    if pending.length == 0 then js.Promise.resolve[Unit](())
    else js.Promise.all(Arr.from(pending)).`then`[Unit](_ => awaitAll())

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
