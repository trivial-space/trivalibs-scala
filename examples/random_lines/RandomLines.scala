package examples.random_lines

import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.document
import trivalibs.bufferdata.StructArray
import trivalibs.graphics.buffers.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.math.gpu.{*, given}
import trivalibs.graphics.painter.*
import trivalibs.graphics.shader.dsl.{*, given}
import trivalibs.graphics.shader.{*, given}
import trivalibs.utils.animation.animate
import trivalibs.utils.js.*
import trivalibs.utils.random.*

import scala.scalajs.js.annotation.*

type Attribs = (position: Vec2)
type Varyings = EmptyTuple
type Uniforms = (color: FragmentUniform[Vec3])

/** The buffer layout `allocateAttribs[Attribs]` derives — spelled out because
  * these vertex arrays are passed around as values.
  */
type LineVerts = StructArray[Vec2Buffer *: EmptyTuple]

/** One line: `segments` quads (two triangles each) along `start` → `end`. */
def generateLineGeometry(
    start: Vec2,
    end: Vec2,
    width: Double,
    segments: Int,
): LineVerts =
  val dir = (end - start).normalize
  val perp = Vec2(-dir.y, dir.x) * (width * 0.5)

  val verts = allocateAttribs[Attribs](segments * 6)
  for i <- 0 until segments do
    val p0 = start.lerp(end, i.toDouble / segments)
    val p1 = start.lerp(end, (i + 1).toDouble / segments)
    val v = i * 6
    verts(v + 0).set0(p0.x - perp.x, p0.y - perp.y)
    verts(v + 1).set0(p1.x + perp.x, p1.y + perp.y)
    verts(v + 2).set0(p0.x + perp.x, p0.y + perp.y)
    verts(v + 3).set0(p0.x - perp.x, p0.y - perp.y)
    verts(v + 4).set0(p1.x - perp.x, p1.y - perp.y)
    verts(v + 5).set0(p1.x + perp.x, p1.y + perp.y)
  verts

/** 1–10 lines of 1–10 segments each — every regeneration yields a different
  * number of buffers with different sizes, which is what exercises the form's
  * grow / reuse path.
  */
def generateAllLines(): Arr[LineVerts] =
  val lineCount = randInRange(1, 20).toInt
  val lines = Arr[LineVerts]()
  for _ <- 0 until lineCount do
    val start = Vec2(randInRange(-0.9, 0.9), randInRange(-0.9, 0.9))
    val end = Vec2(randInRange(-0.9, 0.9), randInRange(-0.9, 0.9))
    val segments = randInRange(1, 20).toInt
    lines.push(generateLineGeometry(start, end, 0.06, segments))
  lines

def randomColor(): Vec3 = Vec3(rand(), rand(), rand())

@JSExportTopLevel("main", moduleID = "random_lines")
def main(): Unit =
  val canvas =
    document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]

  Painter.init(canvas): p =>
    val shade = p.shade[Attribs, Varyings, Uniforms]: program =>
      program.vert: ctx =>
        ctx.out.position := vec4(ctx.in.position, 0.0, 1.0)
      program.frag: ctx =>
        ctx.out.color := vec4(ctx.bindings.color, 1.0)

    // All lines live in a single form, one geometry buffer each — drawn in
    // sequence by one shape with one pipeline and one bind group.
    val form = p.form(verticesAll = generateAllLines())

    val color = p.binding(randomColor())
    val shape = p.shape(form, shade).bind("color" := color)

    val panel = p.panel(
      shape = shape,
      clearColor = Vec4(1.0),
      multisample = true,
    )

    var timer = 0.0

    animate: tpf =>
      timer += tpf

      // Every second: a whole new set of buffers, with a new count and new
      // sizes. Buffers get appended, reused where the data fits, and
      // reallocated where it grew.
      if timer >= 1000.0 then
        timer -= 1000.0
        val lines = generateAllLines()
        form.set(verticesAll = lines)
        color.set(randomColor())

        var total = 0
        for l <- lines do total += l.length
        log(s"${lines.length} lines, $total vertices")

      p.paintAndShow(panel)
