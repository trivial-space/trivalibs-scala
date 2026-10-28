package examples.panel_realloc

import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.document
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js.annotation.*

/** One panel reconfigured while it renders: every 1.5 s `set` toggles MSAA and
  * an auto-pong layer. Both change how the panel's textures are allocated, so
  * the next paint reallocates them — at an unchanged size.
  */
@JSExportTopLevel("main", moduleID = "panel_realloc")
def main(): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]
  val status = document.getElementById("status").asInstanceOf[HTMLElement]

  Painter.init(canvas): p =>
    type Attribs = (position: Vec2, color: Vec3)
    type Uniforms = (angle: VertexUniform[Float])

    val shade = p.shade[Attribs, (color: Vec3), Uniforms]: program =>
      program.vert: ctx =>
        val a = ctx.bindings.angle
        val pos = ctx.in.position
        Block(
          ctx.out.color := ctx.in.color,
          ctx.out.position := vec4(
            pos.x * a.cos - pos.y * a.sin,
            pos.x * a.sin + pos.y * a.cos,
            0.0,
            1.0,
          ),
        )
      program.frag: ctx =>
        ctx.out.color := vec4(ctx.in.color, 1.0)

    val vertices = allocateAttribs[Attribs](3)
    vertices(0).set0(0.0, 0.8)
    vertices(0).set1(1.0, 0.3, 0.3)
    vertices(1).set0(-0.7, -0.5)
    vertices(1).set1(0.3, 1.0, 0.3)
    vertices(2).set0(0.7, -0.5)
    vertices(2).set1(0.3, 0.3, 1.0)

    val angle = p.binding(0.0)
    val shape = p.shape(p.form(vertices = vertices), shade).bind("angle" := angle)

    val invertShade = p.layerShade[(samp: Sampler), (src: FragmentPanel)]:
      program =>
        program.frag: ctx =>
          val c = ctx.textures.src(ctx.in.uv, ctx.bindings.samp)
          ctx.out.color := vec4(1.0 - c.x, 1.0 - c.y, 1.0 - c.z, 1.0)
    val invert = p.layer(invertShade).bind("samp" := p.samplerNearest)

    val panel = p.panel(clearColor = (0.08, 0.08, 0.14, 1.0), shape = shape)

    var state = 0
    var sinceSwitch = 0.0

    def applyState(): Unit =
      val msaa = state % 2 == 1
      val pong = state >= 2
      panel.set(
        multisample = msaa,
        layers = if pong then Arr[AnyLayer](invert) else Arr[AnyLayer](),
      )
      status.textContent =
        s"MSAA ${if msaa then "on" else "off"} · invert layer ${if pong then "on" else "off"}"

    applyState()

    animate: tpf =>
      sinceSwitch += tpf
      if sinceSwitch > 1500 then
        sinceSwitch = 0.0
        state = (state + 1) % 4
        applyState()
      angle := angle.get + tpf * 0.0004
      p.paint(panel)
      p.show(panel)
