package trivalibs.graphics.painter

import org.scalajs.dom.HTMLCanvasElement
import trivalibs.utils.js.*

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

// =============================================================================
// Core WebGPU Types
// =============================================================================

@js.native
trait GPU extends js.Object:
  def requestAdapter(): js.Promise[GPUAdapter | Null] = js.native
  def requestAdapter(
      options: js.Dynamic,
  ): js.Promise[GPUAdapter | Null] = js.native
  def getPreferredCanvasFormat(): String = js.native

@js.native
trait GPUAdapter extends js.Object:
  /** What the runtime picked: vendor, architecture, device, description. */
  val info: GPUAdapterInfo = js.native
  val features: GPUSupportedFeatures = js.native
  val limits: js.Dynamic = js.native
  def requestDevice(): js.Promise[GPUDevice] = js.native
  def requestDevice(descriptor: js.Dynamic): js.Promise[GPUDevice] = js.native

/** Description of a [[GPUAdapter]]. Fields may be empty strings: browsers
  * report only what their privacy rules allow.
  */
@js.native
trait GPUAdapterInfo extends js.Object:
  val vendor: String = js.native
  val architecture: String = js.native
  val device: String = js.native
  val description: String = js.native

/** Set-like list of feature names (`"float32-filterable"`, …). */
@js.native
trait GPUSupportedFeatures extends js.Object:
  def has(name: String): Boolean = js.native

@js.native
trait GPUDevice extends js.Object:
  val queue: GPUQueue = js.native
  val features: GPUSupportedFeatures = js.native
  val lost: js.Promise[js.Dynamic] = js.native
  def destroy(): Unit = js.native
  def createShaderModule(descriptor: js.Dynamic): GPUShaderModule = js.native
  def createRenderPipeline(descriptor: js.Dynamic): GPURenderPipeline =
    js.native
  def createCommandEncoder(): GPUCommandEncoder = js.native
  def createCommandEncoder(descriptor: js.Dynamic): GPUCommandEncoder =
    js.native
  def createBuffer(descriptor: js.Dynamic): GPUBuffer = js.native
  def createBindGroup(descriptor: js.Dynamic): GPUBindGroup = js.native
  def createBindGroupLayout(descriptor: js.Dynamic): GPUBindGroupLayout =
    js.native
  def createPipelineLayout(descriptor: js.Dynamic): GPUPipelineLayout =
    js.native
  def createTexture(descriptor: js.Dynamic): GPUTexture = js.native
  def createSampler(): GPUSampler = js.native
  def createSampler(descriptor: js.Dynamic): GPUSampler = js.native

// =============================================================================
// Queue
// =============================================================================

@js.native
trait GPUQueue extends js.Object:
  def submit(commandBuffers: Arr[GPUCommandBuffer]): Unit = js.native
  def writeBuffer(
      buffer: GPUBuffer,
      bufferOffset: Double,
      data: Float32Array | Uint8Array | ArrayBuffer,
  ): Unit = js.native
  def writeBuffer(
      buffer: GPUBuffer,
      bufferOffset: Double,
      data: Float32Array | Uint8Array | ArrayBuffer,
      dataOffset: Double,
  ): Unit = js.native
  def writeBuffer(
      buffer: GPUBuffer,
      bufferOffset: Double,
      data: Float32Array | Uint8Array | ArrayBuffer,
      dataOffset: Double,
      size: Double,
  ): Unit = js.native
  def writeTexture(
      destination: js.Dynamic,
      data: ArrayBufferView | ArrayBuffer,
      dataLayout: js.Dynamic,
      size: js.Dynamic,
  ): Unit = js.native
  def copyExternalImageToTexture(
      source: js.Dynamic,
      destination: js.Dynamic,
      copySize: js.Dynamic,
  ): Unit = js.native
  def onSubmittedWorkDone(): js.Promise[Unit] = js.native

// =============================================================================
// Shader and Pipeline
// =============================================================================

@js.native
trait GPUShaderModule extends js.Object

@js.native
trait GPURenderPipeline extends js.Object:
  def getBindGroupLayout(index: Int): GPUBindGroupLayout = js.native

@js.native
trait GPUPipelineLayout extends js.Object

// =============================================================================
// Command Encoding
// =============================================================================

@js.native
trait GPUCommandEncoder extends js.Object:
  def beginRenderPass(descriptor: js.Dynamic): GPURenderPassEncoder = js.native
  def copyTextureToBuffer(
      source: js.Dynamic,
      destination: js.Dynamic,
      copySize: js.Dynamic,
  ): Unit = js.native
  def copyTextureToTexture(
      source: js.Dynamic,
      destination: js.Dynamic,
      copySize: js.Dynamic,
  ): Unit = js.native
  def finish(): GPUCommandBuffer = js.native
  def finish(descriptor: js.Dynamic): GPUCommandBuffer = js.native

@js.native
trait GPURenderPassEncoder extends js.Object:
  def setPipeline(pipeline: GPURenderPipeline): Unit = js.native
  def setVertexBuffer(slot: Int, buffer: GPUBuffer): Unit = js.native
  def setVertexBuffer(slot: Int, buffer: GPUBuffer, offset: Double): Unit =
    js.native
  def setVertexBuffer(
      slot: Int,
      buffer: GPUBuffer,
      offset: Double,
      size: Double,
  ): Unit = js.native
  def setBindGroup(index: Int, bindGroup: GPUBindGroup): Unit = js.native
  def setBindGroup(
      index: Int,
      bindGroup: GPUBindGroup,
      dynamicOffsets: js.Array[Double],
  ): Unit = js.native
  def draw(vertexCount: Int): Unit = js.native
  def draw(vertexCount: Int, instanceCount: Int): Unit = js.native
  def draw(vertexCount: Int, instanceCount: Int, firstVertex: Int): Unit =
    js.native
  def draw(
      vertexCount: Int,
      instanceCount: Int,
      firstVertex: Int,
      firstInstance: Int,
  ): Unit = js.native
  def setIndexBuffer(buffer: GPUBuffer, indexFormat: String): Unit = js.native
  def setIndexBuffer(
      buffer: GPUBuffer,
      indexFormat: String,
      offset: Double,
      size: Double,
  ): Unit = js.native
  def drawIndexed(indexCount: Int): Unit = js.native
  def drawIndexed(indexCount: Int, instanceCount: Int): Unit = js.native
  def end(): Unit = js.native

@js.native
trait GPUCommandBuffer extends js.Object

// =============================================================================
// Resources
// =============================================================================

@js.native
trait GPUBuffer extends js.Object:
  val size: Double = js.native
  def destroy(): Unit = js.native
  def mapAsync(mode: Int): js.Promise[Unit] = js.native
  /** A temporary view of the mapped memory, detached on [[unmap]]. */
  def getMappedRange(): ArrayBuffer = js.native
  def unmap(): Unit = js.native

@js.native
trait GPUBindGroup extends js.Object

@js.native
trait GPUBindGroupLayout extends js.Object

// =============================================================================
// Canvas and Textures
// =============================================================================

@js.native
trait GPUCanvasContext extends js.Object:
  def configure(config: js.Dynamic): Unit = js.native
  def getCurrentTexture(): GPUTexture = js.native

@js.native
trait GPUTexture extends js.Object:
  val width: Int = js.native
  val height: Int = js.native
  val format: String = js.native
  def createView(): GPUTextureView = js.native
  def createView(descriptor: js.Dynamic): GPUTextureView = js.native
  def destroy(): Unit = js.native

@js.native
trait GPUTextureView extends js.Object

@js.native
trait GPUSampler extends js.Object

// =============================================================================
// Constants
// =============================================================================

object GPUTextureUsage:
  val COPY_SRC = 0x01
  val COPY_DST = 0x02
  val TEXTURE_BINDING = 0x04
  val STORAGE_BINDING = 0x08
  val RENDER_ATTACHMENT = 0x10

object GPUBufferUsage:
  val MAP_READ = 0x0001
  val MAP_WRITE = 0x0002
  val COPY_SRC = 0x0004
  val COPY_DST = 0x0008
  val INDEX = 0x0010
  val VERTEX = 0x0020
  val UNIFORM = 0x0040
  val STORAGE = 0x0080
  val INDIRECT = 0x0100
  val QUERY_RESOLVE = 0x0200

object GPUShaderStage:
  val VERTEX = 0x1
  val FRAGMENT = 0x2
  val COMPUTE = 0x4

object GPUMapMode:
  val READ = 0x1
  val WRITE = 0x2

/** Adapter power hint for [[WebGPU.requestAdapter]] / [[Painter.init]]. */
opaque type PowerPreference = String
object PowerPreference:
  val HighPerformance: PowerPreference = "high-performance"
  val LowPower: PowerPreference = "low-power"
  extension (p: PowerPreference) inline def toJs: js.Any = p.asInstanceOf[js.Any]

// =============================================================================
// Helpers
// =============================================================================

object WebGPU:
  /** `navigator.gpu`: undefined without WebGPU or without a `navigator` (most
    * non-browser hosts, which pass their `GPU` in). Used by
    * `Painter.init(canvas)`.
    */
  def getGPU(): js.UndefOr[GPU] =
    // `typeof` on the bare global: a plain read of a missing one throws.
    if js.typeOf(js.Dynamic.global.navigator) == "undefined" then js.undefined
    else js.Dynamic.global.navigator.gpu.asInstanceOf[js.UndefOr[GPU]]

  /** Get WebGPU context from a canvas element */
  def getContext(canvas: HTMLCanvasElement): GPUCanvasContext =
    canvas.getContext("webgpu").asInstanceOf[GPUCanvasContext]

  /** Request an adapter from `gpu`, then verify it. No lookups: the caller
    * supplies the `GPU`.
    *
    *   - `powerPreference`, `forceFallbackAdapter` — the spec hints;
    *     `forceFallbackAdapter = true` asks for the software adapter.
    *   - `requireAdapter` — rejects the promise unless it returns true for the
    *     adapter's info; the message lists what was found. See [[adapterNamed]].
    *   - `logAdapter` — prints the adapter info once.
    */
  def requestAdapter(
      gpu: GPU,
      powerPreference: Maybe[PowerPreference] = Maybe.Not,
      forceFallbackAdapter: Boolean = false,
      requireAdapter: Maybe[js.Function1[GPUAdapterInfo, Boolean]] = Maybe.Not,
      logAdapter: Boolean = false,
  ): js.Promise[GPUAdapter] =
    val options = Obj.literal()
    powerPreference.foreach(p => options.powerPreference = p.toJs)
    if forceFallbackAdapter then options.forceFallbackAdapter = true
    gpu
      .requestAdapter(options)
      .orError("Failed to get WebGPU adapter")
      .map: adapter =>
        if logAdapter then log("WebGPU adapter: " + describe(adapter.info))
        requireAdapter.foreach: accept =>
          if !accept(adapter.info) then
            throw jsError(
              "WebGPU adapter rejected by requireAdapter: " +
                describe(adapter.info),
            )
        adapter

  /** Request a device from `adapter`, e.g. with
    * `requiredFeatures = Arr("float32-filterable")` for `rgba32float` output.
    */
  def requestDevice(
      adapter: GPUAdapter,
      requiredFeatures: Maybe[Arr[String]] = Maybe.Not,
      requiredLimits: Maybe[js.Object] = Maybe.Not,
  ): js.Promise[GPUDevice] =
    val descriptor = Obj.literal()
    requiredFeatures.foreach(f => descriptor.requiredFeatures = f)
    requiredLimits.foreach(l => descriptor.requiredLimits = l)
    adapter.requestDevice(descriptor)

  /** A `requireAdapter` predicate: true when `name` occurs, ignoring case, in
    * any of the adapter's vendor / architecture / device / description.
    */
  def adapterNamed(name: String): js.Function1[GPUAdapterInfo, Boolean] =
    val needle = name.toLowerCase()
    (info: GPUAdapterInfo) =>
      !js.isUndefined(info) && (
        info.vendor.toLowerCase().indexOf(needle) >= 0 ||
          info.architecture.toLowerCase().indexOf(needle) >= 0 ||
          info.device.toLowerCase().indexOf(needle) >= 0 ||
          info.description.toLowerCase().indexOf(needle) >= 0
      )

  /** `vendor / architecture / device / description`, for logs and errors. */
  def describe(info: GPUAdapterInfo): String =
    if js.isUndefined(info) then "(no adapter info)"
    else
      info.vendor + " / " + info.architecture + " / " + info.device + " / " +
        info.description
