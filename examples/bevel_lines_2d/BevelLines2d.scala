package examples.bevel_lines_2d

import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.document
import trivalibs.graphics.geometry.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.math.gpu.{*, given}
import trivalibs.graphics.painter.*
import trivalibs.graphics.shader.dsl.{*, given}
import trivalibs.graphics.shader.lib.line.*
import trivalibs.graphics.shader.{*, given}
import trivalibs.utils.animation.animate
import trivalibs.utils.js.*
import trivalibs.utils.random.*

import scala.scalajs.js.annotation.*

type Attribs = LineAttribs
type Varyings = (uv: Vec2, localUv: Vec2, cross: Vec2)
type Uniforms = (size: VertexUniform[Vec2])

val PointCount = 20

def randWidth(): Double = rand() * 560.0 + 40.0

/** A single wildly-varying polyline, put through every `Line` transformation
  * and split into fragments at its sharp corners — one geometry per fragment.
  */
def generateGeometry(
    width: Double,
    height: Double,
): Arr[BufferedGeometry[LineAttribsBuffer]] =
  // random points spread over 1.5x the canvas, so the stroke runs off-screen
  val line = Line(40.0)
  for _ <- 0 until PointCount do
    line.add(
      Vec2((rand() - 0.5) * width * 1.5, (rand() - 0.5) * height * 1.5),
      randWidth(),
    )

  // two extra vertices per segment with fresh widths — a jittery ribbon that
  // `cleanup` then has to thin back out
  val subdivided = line.flatMapWithNeighbours: (prev, curr, next) =>
    if next == null then Arr(curr.copy)
    else
      val n = next
      Arr(
        LineVertex(curr.pos, curr.width),
        LineVertex(curr.pos.lerp(n.pos, 0.333), randWidth()),
        LineVertex(curr.pos.lerp(n.pos, 0.666), randWidth()),
      )

  subdivided
    .cleanup(0.25, 0.1, 0.1)
    .splitAtAngle(math.Pi * 3.0 / 4.0)
    .toBufferedGeometries(
      smoothDepth = 4,
      smoothAngleThreshold = 0.001,
      smoothMinLength = 5.0,
    )

@JSExportTopLevel("main", moduleID = "bevel_lines_2d")
def main(): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]

  Painter.init(canvas): p =>
    val shade = p.shade[Attribs, Varyings, Uniforms]: program =>
      program.vert: ctx =>
        val pos = LetVec2("pos")
        Block(
          pos := ctx.in.position / ctx.bindings.size,
          ctx.out.uv := ctx.in.uv,
          ctx.out.localUv := ctx.in.localUv,
          // the cross coordinate is packed here and divided in the fragment
          // stage — interpolating `uv.y` directly kinks at every triangle
          // diagonal wherever the width changes. See `shader.lib.line`.
          ctx.out.cross := LineCross.pack(ctx.in.uv.y, ctx.in.width),
          ctx.out.position := vec4(pos.x, -pos.y, 0.0, 1.0),
        )
      // uv debug color: any broken mitre or uv discontinuity shows up as a
      // seam in the gradient
      program.frag: ctx =>
        ctx.out.color :=
          vec4(ctx.in.uv.x, ctx.in.cross.lineV, 1.0, 1.0)

    val size = p.binding[Vec2]

    // All fragments of the split stroke live in one form, drawn in sequence.
    val form = p.form(
      geometries = generateGeometry(p.width, p.height),
      topology = PrimitiveTopology.TriangleStrip,
    )

    val shape = p.shape(form, shade).bind("size" := size)

    val panel = p.panel(
      shape = shape,
      clearColor = Vec4(1.0),
      multisample = true,
    )

    p.onResize: (w, h) =>
      size.set(Vec2(w, h))
      form.set(geometries = generateGeometry(w, h))
      p.paintAndShow(panel)
