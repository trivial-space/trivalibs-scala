package examples.screenshot

import org.scalajs.dom.HTMLButtonElement
import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.document
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** A 4K screenshot next to canvas rendering: `atSize` repaints the panel at
  * 3840×2160 (the `res` uniform follows through `onResize`), `showTo` reads it
  * back into a `PixelImage`, and the canvas keeps animating at its own size.
  * Encoding and the download are host code, passed in as `savePng`.
  */
@JSExportTopLevel("main", moduleID = "screenshot")
def main(savePng: js.Function2[PixelImage, String, Unit]): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]
  val button =
    document.getElementById("shot").asInstanceOf[HTMLButtonElement]
  val status = document.getElementById("status").asInstanceOf[HTMLElement]

  Painter.init(canvas): painter =>
    type Uniforms = (time: Float, res: Vec2)

    val shade = painter.layerShade[Uniforms]: program =>
      program.frag: ctx =>
        val p = LetVec2("p")
        val ring = LetFloat("ring")
        val t = ctx.bindings.time
        val res = ctx.bindings.res
        Block(
          p := (ctx.in.uv - 0.5) * vec2(res.x / res.y * 2.0, 2.0),
          ring := ((p.length - 0.6).abs * 40.0 - t * 3.0).sin * 0.5 + 0.5,
          ctx.out.color := vec4(
            (p.x * 3.0 + t).sin * 0.5 + 0.5,
            ring,
            (p.y * 3.0 - t * 0.7).sin * 0.5 + 0.5,
            1.0,
          ),
        )

    val time = painter.binding(0.0)
    val res = painter.binding[Vec2]
    val panel =
      painter.panel(layer =
        painter.layer(shade).bind("time" := time, "res" := res),
      )

    painter.onResize: (w, h) =>
      res.set(Vec2(w, h))

    val shot = PixelImage(3840, 2160)

    button.onclick = _ =>
      button.disabled = true
      val start = js.Date.now()
      val saved = painter.atSize(shot.width, shot.height):
        painter.paint(panel)
        painter.showTo(panel, shot)
      saved.map: image =>
        savePng(image, "screenshot-3840x2160.png")
        status.textContent =
          s"Saved ${image.width}×${image.height} in ${(js.Date.now() - start).round} ms"
        button.disabled = false

    animate: tpf =>
      time.set(time.get + tpf * 0.001)
      painter.paint(panel)
      painter.show(panel)
