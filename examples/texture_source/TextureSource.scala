package examples.texture_source

import org.scalajs.dom.CanvasRenderingContext2D
import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.document
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

/** Two panels whose content is an image instead of a render: a `PixelImage`
  * built in Scala (raw RGBA bytes, re-uploaded every second with `set`) and a
  * 2D canvas (a browser image, with a generated mip chain). Neither panel is
  * painted — the upload is all — and both are sampled as textures by the
  * rotating quads of the canvas panel.
  */
@JSExportTopLevel("main", moduleID = "texture_source")
def main(): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]
  val status = document.getElementById("status").asInstanceOf[HTMLElement]

  Painter.init(canvas): p =>
    // ---- sources ----

    val rawSize = 16
    val raw = PixelImage(rawSize, rawSize)

    def fillRaw(shift: Int): Unit =
      val data = raw.data.asInstanceOf[Uint8Array]
      for y <- 0 until rawSize; x <- 0 until rawSize do
        val i = (y * rawSize + x) * 4
        val on = (x / 4 + y / 4 + shift) % 2 == 0
        data(i) = (if on then 240 else 30 + x * 12).toShort
        data(i + 1) = (if on then 180 else 40 + y * 12).toShort
        data(i + 2) = (if on then 60 else 160).toShort
        data(i + 3) = 255.toShort

    val drawing =
      document.createElement("canvas").asInstanceOf[HTMLCanvasElement]
    drawing.width = 512
    drawing.height = 512
    val ctx = drawing.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    val gradient = ctx.createLinearGradient(0, 0, 512, 512)
    gradient.addColorStop(0, "#2a6f97")
    gradient.addColorStop(1, "#f9c74f")
    ctx.fillStyle = gradient
    ctx.fillRect(0, 0, 512, 512)
    ctx.fillStyle = "#101020"
    ctx.font = "bold 92px system-ui, sans-serif"
    ctx.textAlign = "center"
    ctx.fillText("trivalibs", 256, 230)
    ctx.font = "48px system-ui, sans-serif"
    ctx.fillText("2D canvas source", 256, 320)

    fillRaw(0)
    val rawPanel = p.panel(source = raw)
    val drawingPanel = p.panel(source = drawing, mips = true)

    // ---- textured quads ----

    type Attribs = (position: Vec3, uv: Vec2)
    type Uniforms = (
        mvp: VertexUniform[Mat4],
        texSampler: FragmentUniform[Sampler],
    )
    type Panels = (tex: FragmentPanel)

    val shade = p.shade[Attribs, (uv: Vec2), Uniforms, Panels]: program =>
      program.vert: ctx =>
        Block(
          ctx.out.uv := ctx.in.uv,
          ctx.out.position := ctx.bindings.mvp * vec4(ctx.in.position, 1.0),
        )
      program.frag: ctx =>
        ctx.out.color := ctx.textures.tex(ctx.in.uv, ctx.bindings.texSampler)

    def quad() =
      val v = allocateAttribs[Attribs](6)
      v(0).set0(-0.8, -0.8, 0.0)
      v(0).set1(0.0, 1.0)
      v(1).set0(0.8, -0.8, 0.0)
      v(1).set1(1.0, 1.0)
      v(2).set0(0.8, 0.8, 0.0)
      v(2).set1(1.0, 0.0)
      v(3).set0(-0.8, -0.8, 0.0)
      v(3).set1(0.0, 1.0)
      v(4).set0(0.8, 0.8, 0.0)
      v(4).set1(1.0, 0.0)
      v(5).set0(-0.8, 0.8, 0.0)
      v(5).set1(0.0, 0.0)
      p.form(vertices = v)

    val mipSampler = p.sampler(
      magFilter = FilterMode.Linear,
      minFilter = FilterMode.Linear,
      mipmapFilter = FilterMode.Linear,
    )

    val leftMvp = p.binding[Mat4]
    val rightMvp = p.binding[Mat4]
    val left = p
      .shape(quad(), shade)
      .bind(
        "mvp" := leftMvp,
        "texSampler" := p.samplerNearest,
        "tex" := rawPanel,
      )
    val right = p
      .shape(quad(), shade)
      .bind("mvp" := rightMvp, "texSampler" := mipSampler, "tex" := drawingPanel)

    val scene = p.panel(
      clearColor = (0.05, 0.05, 0.09, 1.0),
      depthTest = true,
      multisample = true,
      shapes = Arr(left, right),
    )

    var proj = Mat4.perspective(math.Pi / 3.5, 1.0, 0.1, 50.0)
    val view = Mat4.lookAt(0.0, 0.0, 3.6, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    p.onResize: (w, h) =>
      proj = Mat4.perspective(math.Pi / 3.5, w / h, 0.1, 50.0)

    status.textContent =
      "left: 16×16 PixelImage, nearest, re-uploaded every second · right: 2D canvas, mips, trilinear"

    var t = 0.0
    var sinceUpload = 0.0
    var shift = 0

    animate: tpf =>
      t += tpf * 0.0005
      sinceUpload += tpf
      if sinceUpload > 1000 then
        sinceUpload = 0.0
        shift += 1
        fillRaw(shift)
        rawPanel.set(source = raw)

      leftMvp := proj * view * Mat4.fromTranslation(-1.0, 0.0, 0.0) *
        Mat4.fromRotationY(t)
      rightMvp := proj * view * Mat4.fromTranslation(1.0, 0.0, 0.0) *
        Mat4.fromRotationY(-t * 1.3)

      p.paint(scene)
      p.show(scene)
