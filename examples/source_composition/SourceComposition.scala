package examples.source_composition

import org.scalajs.dom.CanvasRenderingContext2D
import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.document
import trivalibs.graphics.shader.lib.blur.Blur
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js.annotation.*

/** A panel source composed with the rest of a panel. Click to cycle:
  *   - background: the image stretched over the canvas (`followSurface`),
  *     under two depth-tested, multisampled triangles that cut through each
  *     other;
  *   - effects: the image at its own size through a blur and a color grade
  *     layer stack, re-run on the untouched image every paint;
  *   - blend: a translucent image blended (`sourceBlend`) over an animated
  *     clear color.
  */
@JSExportTopLevel("main", moduleID = "source_composition")
def main(): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]
  val status = document.getElementById("status").asInstanceOf[HTMLElement]

  Painter.init(canvas): p =>
    // ---- images ----

    def drawing(translucent: Boolean): HTMLCanvasElement =
      val c = document.createElement("canvas").asInstanceOf[HTMLCanvasElement]
      c.width = 512
      c.height = 512
      val ctx = c.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
      if !translucent then
        ctx.fillStyle = "#1d3557"
        ctx.fillRect(0, 0, 512, 512)
      for i <- 0 until 12 do
        ctx.fillStyle =
          if translucent then s"hsla(${i * 30}, 80%, 60%, 0.5)"
          else s"hsl(${i * 30}, 70%, 55%)"
        ctx.beginPath()
        ctx.arc(
          256 + 150 * math.cos(i * math.Pi / 6),
          256 + 150 * math.sin(i * math.Pi / 6),
          70,
          0,
          2 * math.Pi,
        )
        ctx.fill()
      ctx.fillStyle = "#f1faee"
      ctx.font = "bold 64px system-ui, sans-serif"
      ctx.textAlign = "center"
      ctx.fillText("source", 256, 278)
      c

    val image = drawing(translucent = false)

    // ---- background: image under depth-tested shapes ----

    type Attribs = (position: Vec3, color: Vec3)
    type Uniforms = (mvp: VertexUniform[Mat4])

    val colorShade = p.shade[Attribs, (color: Vec3), Uniforms]: program =>
      program.vert: ctx =>
        Block(
          ctx.out.color := ctx.in.color,
          ctx.out.position := ctx.bindings.mvp * vec4(ctx.in.position, 1.0),
        )
      program.frag: ctx =>
        ctx.out.color := vec4(ctx.in.color, 1.0)

    def triangle(color: Vec3) =
      val v = allocateAttribs[Attribs](3)
      v(0).set0(0.0, 1.0, 0.0)
      v(1).set0(-0.9, -0.6, 0.0)
      v(2).set0(0.9, -0.6, 0.0)
      v(0).set1(color.x, color.y, color.z)
      v(1).set1(color.x * 0.6, color.y * 0.6, color.z * 0.6)
      v(2).set1(color.x * 0.8, color.y * 0.8, color.z * 0.8)
      p.form(vertices = v)

    val mvpA = p.binding[Mat4]
    val mvpB = p.binding[Mat4]
    val triA =
      p.shape(triangle(Vec3(1.0, 0.85, 0.3)), colorShade).bind("mvp" := mvpA)
    val triB =
      p.shape(triangle(Vec3(0.9, 0.3, 0.5)), colorShade).bind("mvp" := mvpB)

    val background = p.panel(
      source = image,
      followSurface = true,
      depthTest = true,
      multisample = true,
      shapes = Arr(triA, triB),
    )

    // ---- effects: image through a layer stack ----

    type BlurUniforms = (resolution: Vec2, dir: Vec2, samp: Sampler)
    type BlurPanels = (src: FragmentPanel)
    val blurShade = p.layerShade[BlurUniforms, BlurPanels]: program =>
      program.frag: ctx =>
        ctx.out.color := Blur.gaussian9(
          ctx.textures.src,
          ctx.bindings.samp,
          ctx.in.uv,
          ctx.bindings.resolution,
          ctx.bindings.dir,
        )

    type GradeUniforms = (time: Float, samp: Sampler)
    val gradeShade = p.layerShade[GradeUniforms, BlurPanels]: program =>
      program.frag: ctx =>
        val c = ctx.textures.src(ctx.in.uv, ctx.bindings.samp)
        val pulse = (ctx.bindings.time * 2.0).sin * 0.5 + 0.5
        ctx.out.color := vec4(
          c.x * (0.6 + 0.4 * pulse),
          c.y,
          c.z * (1.0 - 0.4 * pulse),
          1.0,
        )

    val time = p.binding(0.0)
    val resolution = Vec2(512.0, 512.0)
    def blur(dir: Vec2) =
      p.layer(blurShade)
        .bind(
          "resolution" := resolution,
          "dir" := dir,
          "samp" := p.samplerLinear,
        )

    val effects = p.panel(
      source = image,
      layers = Arr[AnyLayer](
        blur(Vec2(4.0, 0.0)),
        blur(Vec2(0.0, 4.0)),
        p.layer(gradeShade).bind("time" := time, "samp" := p.samplerNearest),
      ),
    )

    // ---- blend: translucent image over the clear color ----

    val blended = p.panel(
      source = drawing(translucent = true),
      sourceBlend = BlendState.Alpha,
      followSurface = true,
    )

    // ---- modes ----

    val modes = Arr(background, effects, blended)
    val labels = Arr(
      "background · followSurface source under depth-tested MSAA shapes",
      "effects · blur + grade layer stack over the image",
      "blend · translucent source over an animated clear color",
    )
    var mode = 0
    status.textContent = s"${labels(0)} — click to cycle"
    canvas.onclick = _ =>
      mode = (mode + 1) % modes.length
      status.textContent = s"${labels(mode)} — click to cycle"

    var proj = Mat4.perspective(math.Pi / 3.0, 1.0, 0.1, 50.0)
    val view = Mat4.lookAt(0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    p.onResize: (w, h) =>
      proj = Mat4.perspective(math.Pi / 3.0, w / h, 0.1, 50.0)

    var t = 0.0

    animate: tpf =>
      t += tpf * 0.001
      time := t
      mvpA := proj * view * Mat4.fromRotationY(t * 0.7)
      mvpB := proj * view * Mat4.fromRotationY(t * 0.7 + math.Pi / 2)
      val hue = t * 0.1 % 1.0
      val rgb = Vec3(hue, 0.6, 0.35).hsv2rgb
      blended.set(clearColor = Vec4(rgb.x, rgb.y, rgb.z, 1.0))

      val panel = modes(mode)
      p.paint(panel)
      p.show(panel)
