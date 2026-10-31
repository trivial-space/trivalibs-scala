package trivalibs.graphics.painter

import munit.FunSuite
import trivalibs.utils.js.*

import scala.scalajs.js

/** The MRT + auto-pong config-time invariant (`Panel.set`) is pure predicate
  * logic — it null-checks the shade's panel bind-group layout and the
  * layer's `panelBindings`, never a GPU resource. So it can be exercised with
  * stub layers and a null painter, no `GPUDevice` required.
  */
class MrtPongInvariantTest extends FunSuite:

  private def stub[T]: T = null.asInstanceOf[T]

  // A stub shade whose only meaningful field is whether it carries a panel
  // bind-group layout (the auto-pong signal). All GPU handles stay null — the
  // invariant path never touches them.
  private def stubShade(hasPanelLayout: Boolean): Shade[Any, Any] =
    val layout: Opt[GPUBindGroupLayout] =
      if hasPanelLayout then js.Object().asInstanceOf[GPUBindGroupLayout]
      else null
    new Shade[Any, Any](
      id = 0,
      shaderModule = stub,
      vertexBufferLayout = null,
      valueBindGroupLayout = null,
      panelBindGroupLayout = layout,
      pipelineLayout = stub,
      isLayer = true,
      uniformIndices = Dict[Int](),
      panelIndices = Dict[Int](),
    )

  private def layer(hasPanelLayout: Boolean): Layer[Any, Any] =
    new Layer[Any, Any](stub, stubShade(hasPanelLayout))

  private def panel(): Panel = new Panel(stub)

  private val twoFormats =
    Arr(TextureFormat.Rgba8Unorm, TextureFormat.Rgba16Float)

  test("MRT panel + auto-pong layer throws at construction-time set"):
    intercept[js.JavaScriptException]:
      panel().set(formats = twoFormats, layer = layer(hasPanelLayout = true))

  test("MRT panel + non-pong layer (no panel layout) is allowed"):
    panel().set(formats = twoFormats, layer = layer(hasPanelLayout = false))

  test("MRT panel + manually-bound-slot-0 layer is allowed"):
    val l = layer(hasPanelLayout = true)
    // Manual slot-0 binding ⇒ not auto-pong ⇒ no ping-pong ⇒ allowed on MRT.
    l.panelBindings = Arr(new PanelBinding(panel()))
    panel().set(formats = twoFormats, layer = l)

  test("single-format panel + auto-pong layer is allowed"):
    panel().set(
      formats = Arr(TextureFormat.Rgba8Unorm),
      layer = layer(hasPanelLayout = true),
    )

  test("post-construction set that turns a pong panel into MRT throws"):
    val p = panel()
    p.set(
      formats = Arr(TextureFormat.Rgba8Unorm),
      layer = layer(hasPanelLayout = true),
    )
    // Reconfiguring to two formats must re-trip the invariant.
    intercept[js.JavaScriptException]:
      p.set(formats = twoFormats)
