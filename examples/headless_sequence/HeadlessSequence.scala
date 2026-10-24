package examples.headless_sequence

import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Offline motion sequence: the script owns the loop and steps the animation
  * with a fixed `tpf`, so every frame is exact regardless of GPU speed. Each
  * `show` appends one frame to `out`; the host encodes them once the promise
  * resolves. See `render.node.ts`.
  *
  * The animation loops seamlessly over `frameCount` frames. After the loop, a
  * smaller `preview` of the last frame is rendered at its own size with
  * `atSize` + `showTo`.
  */
@JSExportTopLevel("render", moduleID = "headless_sequence")
def render(
    gpu: GPU,
    out: PixelFrames,
    preview: PixelImage,
    fps: Double,
    frameCount: Int,
): js.Promise[Unit] =
  Painter
    .create(out, gpu, logAdapter = true)
    .flatMap: painter =>
      type Uniforms = (phase: Float, res: Vec2)

      val shade = painter.layerShade[Uniforms]: program =>
        program.frag: ctx =>
          val p = LetVec2("p")
          val d = LetFloat("d")
          val t = ctx.bindings.phase
          val res = ctx.bindings.res
          Block(
            p := (ctx.in.uv - 0.5) * vec2(res.x / res.y * 2.0, 2.0),
            d := p.length,
            ctx.out.color := vec4(
              (p.x * 2.0 + (p.y * 1.5 + t).sin + t).sin * 0.5 + 0.5,
              (p.y * 2.0 + (p.x * 1.5 - t * 2.0).sin - t).sin * 0.5 + 0.5,
              (d * 3.0 - t).sin * 0.5 + 0.5,
              1.0,
            ),
          )

      val phase = painter.binding(0.0)
      val res = painter.binding[Vec2]
      val panel =
        painter.panel(layer = painter.layer(shade).bind("phase" := phase, "res" := res))

      painter.onResize: (w, h) =>
        res.set(Vec2(w, h))

      val tpf = 1000.0 / fps
      val loopMs = frameCount * tpf
      var time = 0.0

      for _ <- 0 until frameCount do
        phase.set(time / loopMs * Tau)
        painter.paint(panel)
        painter.show(panel)
        time += tpf

      painter.atSize(preview.width, preview.height):
        painter.paint(panel)
        painter.showTo(panel, preview)

      painter.awaitReadback()
