package examples.panel_freeze

import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.document
import trivalibs.graphics.buffers.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.math.gpu.{*, given}
import trivalibs.graphics.painter.*
import trivalibs.graphics.scene.*
import trivalibs.graphics.shader.dsl.{*, given}
import trivalibs.graphics.shader.{*, given}
import trivalibs.utils.animation.animate
import trivalibs.utils.js.*
import trivalibs.utils.numbers.NumExt.given

import scala.scalajs.js
import scala.scalajs.js.annotation.*

// A one-shot bake: a multisampled, mipmapped panel is painted once, then
// frozen. `freeze()` frees its 4× MSAA buffer (the largest texture it owns) and
// keeps only the mip chain that the canvas samples every frame.

@JSExportTopLevel("main", moduleID = "panel_freeze")
def main(): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]

  Painter.init(canvas): p =>
    // ---- the baked panel: thin spokes, which alias badly without MSAA ----

    type SpokeAttribs = (position: Vec2, color: Vec3)
    type SpokeUniforms = (tint: FragmentUniform[Vec3])

    val spokeShade = p.shade[SpokeAttribs, (color: Vec3), SpokeUniforms]:
      program =>
        program.vert: ctx =>
          Block(
            ctx.out.color := ctx.in.color,
            ctx.out.position := vec4(ctx.in.position, 0.0, 1.0),
          )
        program.frag: ctx =>
          ctx.out.color := vec4(ctx.in.color * ctx.bindings.tint, 1.0)

    val spokeCount = 48
    val spokes = allocateAttribs[SpokeAttribs](spokeCount * 3)
    var i = 0
    while i < spokeCount do
      val a = i.toDouble / spokeCount * 2.0 * math.Pi
      val half = 0.012
      val warm = i % 2 == 0
      val r = if warm then 1.0 else 0.2
      val g = if warm then 0.55 else 0.6
      val b = if warm then 0.2 else 1.0
      spokes(i * 3).set0(0.0, 0.0)
      spokes(i * 3).set1(r, g, b)
      spokes(i * 3 + 1).set0(math.cos(a - half) * 0.95, math.sin(a - half) * 0.95)
      spokes(i * 3 + 1).set1(r, g, b)
      spokes(i * 3 + 2).set0(math.cos(a + half) * 0.95, math.sin(a + half) * 0.95)
      spokes(i * 3 + 2).set1(r, g, b)
      i += 1

    val bake = p.panel(
      width = 1024,
      height = 1024,
      clearColor = (0.95, 0.94, 0.9, 1.0),
      multisample = true,
      mips = true,
      shape = p
        .shape(p.form(vertices = spokes), spokeShade)
        .bind("tint" := Vec3(1.0, 1.0, 1.0)),
    )
    p.paint(bake)
    bake.freeze()

    // ---- the canvas: a tilted quad sampling the frozen panel ----

    type TexAttribs = (position: Vec3, uv: Vec2)
    type TexUniforms = (
        mvp: VertexUniform[Mat4],
        samp: FragmentUniform[Sampler],
    )
    type TexPanels = (tex: FragmentPanel)

    val texShade =
      p.shade[TexAttribs, (uv: Vec2), TexUniforms, TexPanels]: program =>
        program.vert: ctx =>
          Block(
            ctx.out.uv := ctx.in.uv,
            ctx.out.position := ctx.bindings.mvp * vec4(ctx.in.position, 1.0),
          )
        program.frag: ctx =>
          ctx.out.color := ctx.textures.tex(ctx.in.uv, ctx.bindings.samp)

    val quad = allocateAttribs[TexAttribs](6)
    quad(0).set0(-1.0, -1.0, 0.0)
    quad(0).set1(0.0, 1.0)
    quad(1).set0(1.0, -1.0, 0.0)
    quad(1).set1(1.0, 1.0)
    quad(2).set0(1.0, 1.0, 0.0)
    quad(2).set1(1.0, 0.0)
    quad(3).set0(-1.0, -1.0, 0.0)
    quad(3).set1(0.0, 1.0)
    quad(4).set0(1.0, 1.0, 0.0)
    quad(4).set1(1.0, 0.0)
    quad(5).set0(-1.0, 1.0, 0.0)
    quad(5).set1(0.0, 0.0)

    val mvp = p.binding[Mat4]
    val sampler = p.sampler(
      FilterMode.Linear,
      FilterMode.Linear,
      FilterMode.Linear,
      maxAnisotropy = 16,
    )

    val canvasPanel = p.panel(
      clearColor = (0.1, 0.1, 0.14, 1.0),
      multisample = true,
      shape = p
        .shape(p.form(vertices = quad), texShade, cullMode = CullMode.None)
        .bind("mvp" := mvp, "samp" := sampler, "tex" := bake),
    )

    val cam = PerspectiveCamera(
      fov = math.Pi / 3.0,
      pos = Vec3(0.0, 0.6, 2.4),
      rotV = -0.25,
    )
    p.onResize: (w, h) =>
      cam(aspect = w / h)

    val tilt = Quat.fromRotationX(-math.Pi * 0.42)
    val quadTransform = Transform.identity

    var t = 0.0
    animate: tpf =>
      t += tpf * 0.0003
      quadTransform.rotation = tilt * Quat.fromRotationZ(t)
      mvp.set(cam.viewProjMat * quadTransform.matrix)
      p.paint(canvasPanel)
      p.show(canvasPanel)
