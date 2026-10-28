package examples.simple_triangle

import org.scalajs.dom
import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.document
import trivalibs.graphics.math.cpu.*
import trivalibs.graphics.painter.*
import trivalibs.utils.js.*

import scala.scalajs.js.annotation.*

object SimpleTriangle:
  @JSExportTopLevel("main", moduleID = "simple_triangle")
  def main(): Unit =
    val statusEl = document.getElementById("status").asInstanceOf[HTMLElement]

    def setStatus(msg: String, isError: Boolean): Unit =
      statusEl.textContent = msg
      statusEl.setAttribute("class", if isError then "error" else "success")

    val canvas =
      document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]

    WebGPU.getGPU().toOption match
      case None =>
        setStatus("WebGPU is not supported in this browser", true)
      case Some(gpu) =>
        setStatus("WebGPU available, initializing...", false)
        initWebGPU(gpu, canvas, setStatus)

  def initWebGPU(
      gpu: GPU,
      canvas: HTMLCanvasElement,
      setStatus: (String, Boolean) => Unit,
  ): Unit =
    val result = for
      adapter <- gpu.requestAdapter().orError("Failed to get WebGPU adapter")
      device <- adapter.requestDevice()
    yield
      setStatus("WebGPU initialized! Rendering triangle...", false)
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
    import trivalibs.graphics.shader.None as GPUNone

    // Use the full API to add vertex_index builtin input
    val triangleShader = Shader.full[
      EmptyTuple, // No vertex attributes
      (color: Vec4), // Varyings
      GPUNone, // No uniforms
      (vertexIndex: BuiltinVertexIndex), // Need vertex_index
      VertOut, // Default vertex builtin out
      GPUNone, // No fragment builtin in
      FragOut, // Default fragment out
    ](
      vertexBody = """
        |  let positions = array<vec2<f32>, 3>(
        |    vec2<f32>(0.0, 0.5),
        |    vec2<f32>(-0.5, -0.5),
        |    vec2<f32>(0.5, -0.5)
        |  );
        |  let colors = array<vec4<f32>, 3>(
        |    vec4<f32>(1.0, 0.0, 0.0, 1.0),
        |    vec4<f32>(0.0, 1.0, 0.0, 1.0),
        |    vec4<f32>(0.0, 0.0, 1.0, 1.0)
        |  );
        |  let idx = in.vertexIndex;
        |  out.position = vec4<f32>(positions[idx], 0.0, 1.0);
        |  out.color = colors[idx];
        """.stripMargin,
      fragmentBody = """
        |  out.color = in.color;
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

    // Get WebGPU context
    val context = WebGPU.getContext(canvas)
    val format = "bgra8unorm"

    context.configure(
      Obj.literal(
        device = device,
        format = format,
      ),
    )

    // Create render pipeline
    val pipeline = device.createRenderPipeline(
      Obj.literal(
        layout = "auto",
        vertex = Obj.literal(
          module = shaderModule,
          entryPoint = "vs_main",
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

    // Render frame
    def render(): Unit =
      val commandEncoder = device.createCommandEncoder()
      val textureView = context.getCurrentTexture().createView()

      val renderPass = commandEncoder.beginRenderPass(
        Obj.literal(
          colorAttachments = Arr(
            Obj.literal(
              view = textureView,
              loadOp = "clear",
              storeOp = "store",
              clearValue = Obj.literal(r = 0.1, g = 0.1, b = 0.15, a = 1.0),
            ),
          ),
        ),
      )

      renderPass.setPipeline(pipeline)
      renderPass.draw(3)
      renderPass.end()

      device.queue.submit(Arr(commandEncoder.finish()))

    render()
    setStatus("Triangle rendered successfully!", false)
