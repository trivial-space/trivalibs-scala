package examples.buffer_triangle

import org.scalajs.dom
import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.document
import trivalibs.graphics.buffers.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.painter.*
import trivalibs.utils.js.*
import trivalibs.utils.numbers.NumExt.given

import scala.scalajs.js
import scala.scalajs.js.JSNumberOps.*
import scala.scalajs.js.annotation.*

object BufferTriangle:
  @JSExportTopLevel("main", moduleID = "buffer_triangle")
  def main(): Unit =
    val statusEl = document.getElementById("status").asInstanceOf[HTMLElement]

    def setStatus(msg: String, isError: Boolean): Unit =
      statusEl.textContent = msg
      statusEl.setAttribute("class", if isError then "error" else "success")

    val canvas =
      document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]

    val gpu = WebGPU.getGPU()
    if gpu.isEmpty then
      setStatus("WebGPU is not supported in this browser", true)
    else
      setStatus("WebGPU available, initializing...", false)
      initWebGPU(gpu.safe, canvas, setStatus)

  def initWebGPU(
      gpu: GPU,
      canvas: HTMLCanvasElement,
      setStatus: (String, Boolean) => Unit,
  ): Unit =
    val result = for
      adapter <- gpu.requestAdapter().orError("Failed to get WebGPU adapter")
      device <- adapter.requestDevice()
    yield
      setStatus("WebGPU initialized! Rendering triangle with buffers...", false)
      renderTriangle(device, canvas, setStatus)

    result.recover:
      case e: NoSuchElementException => setStatus(e.getMessage, true)
      case e                         => setStatus(s"WebGPU error: $e", true)

  def renderTriangle(
      device: GPUDevice,
      canvas: HTMLCanvasElement,
      setStatus: (String, Boolean) => Unit,
  ): Unit =
    import trivalibs.graphics.shader.{*, given}
    import trivalibs.graphics.math.{*, given}
    import trivalibs.graphics.shader.None as GPUNone

    // Get WebGPU context
    val context = WebGPU.getContext(canvas)
    val format = "bgra8unorm"

    context.configure(
      Obj.literal(
        device = device,
        format = format,
      ),
    )

    type Attribs = (position: Vec2, color: Vec4)
    type Uniforms = (tintColor: Vec4)

    // Define shader with vertex attributes and uniform for tint color
    val triangleShader = Shader[
      Attribs, // Vertex attributes from buffer
      (color: Vec4), // Varyings
      (scene: Uniforms), // Uniform for runtime tint
    ](
      vertexBody = """
        |  out.position = vec4<f32>(in.position, 0.0, 1.0);
        |  out.color = in.color;
        """.stripMargin,
      fragmentBody = """
        |  out.color = in.color * tintColor;
        """.stripMargin,
    )

    val wgslCode = triangleShader.generateWGSL
    log("Generated WGSL:\n" + wgslCode)

    // Create shader module
    val shaderModule = device.createShaderModule(
      Obj.literal(
        code = wgslCode,
      ),
    )

    // Create vertex buffer - layout derived from shader attribs
    val vertices = allocateAttribs[Attribs](3)

    // Vertex 0: top (red)
    vertices(0)(0) := Vec2(0.0, 0.5)
    vertices(0)(1) := (1.0, 0.0, 0.0, 1.0)

    // Vertex 1: bottom-left (green)
    vertices(1)(0) := (-0.5, -0.5)
    vertices(1)(1) := (0.0, 1.0, 0.0, 1.0)

    // Vertex 2: bottom-right (blue)
    vertices(2)(0) := (0.5, -0.5)
    vertices(2)(1).b = 1.0
    vertices(2)(1).a = 1.0

    val vertexBuffer = device.createBuffer(
      Obj.literal(
        size = vertices.arrayBuffer.byteLength,
        usage = GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST,
      ),
    )

    // Upload vertex data
    device.queue.writeBuffer(
      vertexBuffer,
      0,
      vertices.arrayBuffer,
    )

    // Create uniform buffer for tint color
    // val tintColor = Vec4(1, 1, 1, 1).asBinding(device)
    val tintColor = BufferBinding[Vec4](device)
    // val tintColor = BufferBinding(device, Vec4(1, 1, 1, 1))

    // Derive explicit bind group layouts and pipeline layout from shader types
    val (bindGroupLayouts, pipelineLayout) =
      triangleShader.createPipelineLayout(device)

    // Create render pipeline with explicit layout
    val pipeline = device.createRenderPipeline(
      Obj.literal(
        layout = pipelineLayout,
        vertex = Obj.literal(
          module = shaderModule,
          entryPoint = "vs_main",
          buffers = Arr(triangleShader.vertexBufferLayout),
        ),
        fragment = Obj.literal(
          module = shaderModule,
          entryPoint = "fs_main",
          targets = Arr(
            Obj.literal(
              format = format,
            ),
          ),
        ),
        primitive = Obj.literal(
          topology = "triangle-list",
        ),
      ),
    )

    // Create bind group using explicit layout (reusable across pipelines)
    val bindGroup = device.createBindGroup(
      Obj.literal(
        layout = bindGroupLayouts(0),
        entries = Arr(
          Obj.literal(
            binding = 0,
            resource = Obj.literal(
              buffer = tintColor.gpuBuffer,
            ),
          ),
        ),
      ),
    )

    // Animation state
    var startTime = js.Date.now()

    // FPS tracking
    val fpsEl = document.getElementById("fps").asInstanceOf[HTMLElement]
    var frameCount = 0
    var lastFpsTime = 0.0
    var lastFpsLog = 0.0

    // Render frame with animation
    def render(time: Double): Unit =
      // FPS calculation
      frameCount += 1
      if lastFpsTime == 0.0 then
        lastFpsTime = time
        lastFpsLog = time
      val fpsElapsed = time - lastFpsTime
      if fpsElapsed >= 1000.0 then
        val fps = frameCount * 1000.0 / fpsElapsed
        val frameTime = fpsElapsed / frameCount
        fpsEl.textContent =
          s"${fps.toFixed(1)} FPS (${frameTime.toFixed(2)} ms/frame)"
        if time - lastFpsLog >= 1000.0 then
          log(s"${fps.toFixed(1)} FPS — ${frameTime.toFixed(2)} ms/frame")
          lastFpsLog = time
        frameCount = 0
        lastFpsTime = time

      // Animate tint color
      val elapsed = (time - startTime) / 2000.0
      val r = (elapsed * 2.0).sin * 0.5 + 0.5
      val g = (elapsed * 2.0 + 2.0).sin * 0.5 + 0.5
      val b = (elapsed * 2.0 + 4.0).sin * 0.5 + 0.5
      // tintColor := Vec4(r, g, b, 1)
      tintColor.update: c =>
        c.r = r
        c.g = g
        c.b = b

      val commandEncoder = device.createCommandEncoder()
      val textureView = context.getCurrentTexture().createView()

      val renderPass = commandEncoder.beginRenderPass(
        Obj.literal(
          colorAttachments = Arr(
            Obj.literal(
              view = textureView,
              loadOp = "clear",
              storeOp = "store",
              clearValue = Obj.literal(r = 0.15, g = 0.1, b = 0.1, a = 1.0),
            ),
          ),
        ),
      )

      renderPass.setPipeline(pipeline)
      renderPass.setBindGroup(0, bindGroup)
      renderPass.setVertexBuffer(0, vertexBuffer)
      renderPass.draw(3)
      renderPass.end()

      device.queue.submit(Arr(commandEncoder.finish()))

      // Request next frame
      dom.window.requestAnimationFrame(t => render(t))

    // Start animation loop
    dom.window.requestAnimationFrame(t => render(t))
    setStatus("Buffer triangle with animated tint!", false)
