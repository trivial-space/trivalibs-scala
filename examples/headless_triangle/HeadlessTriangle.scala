package examples.headless_triangle

import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Offscreen render without a DOM: the host passes a `GPU` (node-webgpu's
  * `create([...])`) and a `PixelImage` / `PixelFrames` target, and processes
  * the pixels once the promise resolves. See `render.node.ts`.
  *
  * Shows `frameCount` frames whose tint ramps up to `intensity`, so a
  * `PixelFrames` target gets distinct frames and a `PixelImage` keeps the last
  * one. An `intensity` above 1 survives into float targets (HDR).
  */
@JSExportTopLevel("render", moduleID = "headless_triangle")
def render(
    gpu: GPU,
    out: PixelTarget,
    frameCount: Int,
    intensity: Double,
    software: Boolean,
    requireName: String,
): js.Promise[Unit] =
  val format = out.asInstanceOf[PixelImage].format
  Painter
    .init(
      out,
      gpu,
      forceFallbackAdapter = software,
      requireAdapter =
        maybe(requireName.length > 0, WebGPU.adapterNamed(requireName)),
      requiredFeatures =
        maybe(format == PixelFormat.Rgba32Float, Arr("float32-filterable")),
      logAdapter = true,
    )
    .flatMap: painter =>
      type Attribs = (position: Vec2, color: Vec3)
      type Varyings = (color: Vec3)
      type Uniforms = (tint: Vec3)

      val shade = painter.shade[Attribs, Varyings, Uniforms](
        vertWgsl = """
          out.position = vec4<f32>(in.position, 0.0, 1.0);
          out.color = in.color;
          """,
        fragWgsl = """
          out.color = vec4<f32>(in.color * tint, 1.0);
          """,
      )

      val vertices = allocateAttribs[Attribs](3)
      vertices(0).set0(0.0, 0.7)
      vertices(0).set1(1.0, 0.0, 0.0)
      vertices(1).set0(-0.7, -0.6)
      vertices(1).set1(0.0, 1.0, 0.0)
      vertices(2).set0(0.7, -0.6)
      vertices(2).set1(0.0, 0.0, 1.0)

      val tint = painter.binding[Vec3]
      val shape = painter
        .shape(painter.form(vertices = vertices), shade)
        .bind("tint" := tint)
      val panel =
        painter.panel(shape = shape, clearColor = (0.1, 0.1, 0.15, 1.0))

      for i <- 1 to frameCount do
        val k = intensity * i / frameCount
        tint := Vec3(k)
        painter.paintAndShow(panel)

      painter.awaitReadback()

/** Feeds a decoded image back in as a panel `source` and shows it into a fresh
  * `PixelImage` of the same size — on the direct path, so the result must equal
  * the input byte for byte.
  */
@JSExportTopLevel("roundtrip", moduleID = "headless_triangle")
def roundtrip(gpu: GPU, image: PixelImage): js.Promise[PixelImage] =
  val out = PixelImage(image.width, image.height, image.format)
  Painter
    .init(out, gpu)
    .flatMap: painter =>
      painter.show(painter.panel(source = image))
      painter.awaitReadback().map(_ => out)
