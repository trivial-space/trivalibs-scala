package trivalibs.graphics.painter

import org.scalajs.dom.HTMLCanvasElement
import trivalibs.utils.js.*

import scala.scalajs.js

/** What [[Painter.show]] presents to: a browser canvas ([[CanvasSurface]]), or
  * — internally, on an offscreen painter — a [[PixelFrames]] / [[PixelImage]].
  * A painter owns exactly one surface, chosen by its entry point.
  */
trait Surface:
  /** Drawable width in physical pixels. */
  def width: Int

  /** Drawable height in physical pixels. */
  def height: Int

  /** Format of the textures [[currentTexture]] hands out; the painter's
    * `preferredFormat` (panel default and `show` target).
    */
  def format: TextureFormat

  /** Called once with the fully constructed painter, before the first frame. */
  def configure(painter: Painter): Unit

  /** Texture `show` / `draw` render into this frame. */
  def currentTexture(): GPUTexture

  /** Ends one presented frame: finishes and submits `encoder`, which holds the
    * frame's `show` blit. A capture surface appends its readback copy first.
    */
  def present(encoder: GPUCommandEncoder): Unit

  /** Registers a resize listener, called with the new size. Never fires on
    * fixed-size surfaces.
    */
  def onResize(cb: (Int, Int) => Unit): Unit

/** A browser canvas as a [[Surface]]. `configure` sets up the WebGPU context,
  * sizes the canvas to its client rect, and installs a `ResizeObserver` that
  * keeps it there.
  *
  * `format` defaults to the browser's preferred canvas format.
  */
final class CanvasSurface private (
    val canvas: HTMLCanvasElement,
    val format: TextureFormat,
) extends Surface:
  private var queue: Opt[GPUQueue] = null
  private var context: Opt[GPUCanvasContext] = null
  private val resizeCallbacks = Arr[(Int, Int) => Unit]()

  def width: Int = canvas.width
  def height: Int = canvas.height

  def configure(painter: Painter): Unit =
    val device = painter.device
    queue = device.queue
    val canvasContext = WebGPU.getContext(canvas)
    canvasContext.configure(Obj.literal(device = device, format = format.toJs))
    context = canvasContext

    canvas.width = canvas.clientWidth
    canvas.height = canvas.clientHeight

    val observer = js.Dynamic
      .newInstance(js.Dynamic.global.ResizeObserver)(
        ((entries: js.Array[js.Dynamic]) =>
          val entry = entries(0)
          val rw: Double = entry.contentRect.width.asInstanceOf[Double]
          val rh: Double = entry.contentRect.height.asInstanceOf[Double]
          // Writing width/height resets the canvas even to the same value,
          // wiping a frame painted during setup; the observer's initial
          // call always reports the size set above.
          if rw > 0 && rh > 0 then
            val w = rw.toInt
            val h = rh.toInt
            if w != canvas.width || h != canvas.height then
              canvas.width = w
              canvas.height = h
              var k = 0
              while k < resizeCallbacks.length do
                resizeCallbacks(k)(w, h)
                k += 1
        ): js.Function1[js.Array[js.Dynamic], Unit],
      )
    observer.observe(canvas)

  def currentTexture(): GPUTexture = context.get.getCurrentTexture()

  def present(encoder: GPUCommandEncoder): Unit =
    queue.get.submit(Arr(encoder.finish()))

  def onResize(cb: (Int, Int) => Unit): Unit =
    resizeCallbacks.push(cb)

object CanvasSurface:
  def apply(
      canvas: HTMLCanvasElement,
      format: Maybe[TextureFormat] = Maybe.Not,
  ): CanvasSurface =
    new CanvasSurface(canvas, format.orElse(preferredFormat()))

  /** `navigator.gpu.getPreferredCanvasFormat()`, or `bgra8unorm` where the
    * browser lacks it.
    */
  private def preferredFormat(): TextureFormat =
    val gpu = WebGPU.getGPU()
    if gpu.isEmpty then TextureFormat.Bgra8Unorm
    else TextureFormat.of(gpu.safe.getPreferredCanvasFormat())

/** The surface of an offscreen painter, created on a [[PixelTarget]]. Not
  * user-facing: [[Painter.init]] wraps the target in it. `show` renders into
  * one capture texture of the target's size and format, and `present` reads it
  * back into the target.
  */
private[painter] final class CaptureSurface(val target: PixelTarget)
    extends Surface:
  val width: Int = target.asInstanceOf[PixelImage].width
  val height: Int = target.asInstanceOf[PixelImage].height
  val pixelFormat: PixelFormat = target.asInstanceOf[PixelImage].format
  val format: TextureFormat = pixelFormat.textureFormat
  private var readback: Opt[Readback] = null
  private var texture: Opt[GPUTexture] = null

  PixelTarget.validate(target, "Painter.init")

  def configure(painter: Painter): Unit =
    val device = painter.device
    if pixelFormat == PixelFormat.Rgba32Float &&
      !device.features.has("float32-filterable")
    then
      throw jsError(
        "Painter.init: an rgba32float pixel target needs the device feature " +
          "'float32-filterable'. Pass requiredFeatures = " +
          "Arr(\"float32-filterable\") to Painter.init, or use rgba16float.",
      )
    readback = painter.ensureReadback()
    texture = device.createTexture(
      Obj.literal(
        size = Obj.literal(width = width, height = height),
        format = pixelFormat.toJs,
        usage = GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.COPY_SRC,
      ),
    )

  def currentTexture(): GPUTexture = texture.get

  def present(encoder: GPUCommandEncoder): Unit =
    readback.get.read(encoder, texture.get, target, pooled = true)
    ()

  def onResize(cb: (Int, Int) => Unit): Unit = ()
