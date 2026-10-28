package examples.headless_triangle

import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.document
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js.annotation.*

/** The other half of the bake workflow: `render.node.ts` renders the triangle
  * offscreen and writes `assets/pre-rendered-triangle.png`; this page loads
  * that file with `WebImages.load` and uses it as a sprite texture. The panel
  * holding it is never painted — its source is its content.
  */
@JSExportTopLevel("main", moduleID = "triangle_sprites")
def main(): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]
  val status = document.getElementById("status").asInstanceOf[HTMLElement]

  Painter.init(canvas): p =>
    WebImages
      .load("assets/pre-rendered-triangle.png")
      .map: bitmap =>
        val sprite = p.panel(source = bitmap, mips = true)
        status.textContent =
          s"assets/pre-rendered-triangle.png · ${bitmap.width}×${bitmap.height}, baked by render.node.ts"

        type Attribs = (position: Vec3, uv: Vec2)
        type Uniforms = (
            viewProj: VertexUniform[Mat4],
            model: VertexUniform[Mat4],
            texSampler: FragmentUniform[Sampler],
        )
        type Panels = (tex: FragmentPanel)

        val shade = p.shade[Attribs, (uv: Vec2), Uniforms, Panels]: program =>
          program.vert: ctx =>
            Block(
              ctx.out.uv := ctx.in.uv,
              ctx.out.position := ctx.bindings.viewProj * ctx.bindings.model *
                vec4(ctx.in.position, 1.0),
            )
          program.frag: ctx =>
            ctx.out.color :=
              ctx.textures.tex(ctx.in.uv, ctx.bindings.texSampler)

        val v = allocateAttribs[Attribs](6)
        v(0).set0(-0.5, -0.5, 0.0)
        v(0).set1(0.0, 1.0)
        v(1).set0(0.5, -0.5, 0.0)
        v(1).set1(1.0, 1.0)
        v(2).set0(0.5, 0.5, 0.0)
        v(2).set1(1.0, 0.0)
        v(3).set0(-0.5, -0.5, 0.0)
        v(3).set1(0.0, 1.0)
        v(4).set0(0.5, 0.5, 0.0)
        v(4).set1(1.0, 0.0)
        v(5).set0(-0.5, 0.5, 0.0)
        v(5).set1(0.0, 0.0)

        val mipSampler = p.sampler(
          magFilter = FilterMode.Linear,
          minFilter = FilterMode.Linear,
          mipmapFilter = FilterMode.Linear,
        )
        val shape = p
          .shape(p.form(vertices = v), shade)
          .bind("texSampler" := mipSampler, "tex" := sprite)

        val count = 24
        for i <- 0 until count do
          shape.instances.add("model" := Mat4.identity)

        val viewProj = p.binding(Mat4.identity)
        val scene = p
          .panel(
            clearColor = (0.05, 0.05, 0.09, 1.0),
            depthTest = true,
            multisample = true,
            shape = shape,
          )
          .bind("viewProj" := viewProj)

        val view = Mat4.lookAt(0.0, 0.0, 9.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        def updateProj(w: Double, h: Double): Unit =
          viewProj := Mat4.perspective(math.Pi / 3.0, w / h, 0.1, 50.0) * view
        updateProj(p.width, p.height)
        p.onResize(updateProj)

        var t = 0.0
        animate: tpf =>
          t += tpf * 0.0004
          for i <- 0 until count do
            val a = t + i * 2.0 * math.Pi / count
            val radius = 2.2 + 1.2 * math.sin(t * 3.0 + i)
            val model = Mat4.fromTranslation(
              radius * math.cos(a),
              radius * math.sin(a),
              math.sin(a * 2.0),
            ) * Mat4.fromRotationZ(-a * 2.0) *
              Mat4.fromScale(0.4 + 0.6 * ((i % 4) / 3.0), 0.4 + 0.6 * ((i % 4) / 3.0), 1.0)
            shape.instances(i).bind("model" := model)
          p.paint(scene)
          p.show(scene)
