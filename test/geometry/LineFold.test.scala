package trivalibs.graphics.geometry

import munit.FunSuite
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.given
import trivalibs.utils.js.*

/** The fold limit — how far a vertex can be offset before the inner outline
  * folds back through the corner — exercised on its own.
  *
  * Both treatments ask it the same question, so it is tested through the one
  * that reports it directly: `narrowAtTightTurns(1.0)` on a line far wider than
  * any corner can carry leaves `width = 2 * limit` at every vertex, so the
  * widths read back out are the limits.
  */
class LineFoldTest extends FunSuite:

  /** Wide enough that the limit always binds, so nothing is masked by the
    * caller's own width.
    */
  private val Wide = 1000.0

  private def limits(
      points: Arr[Vec2],
      proximity: Boolean = false,
  ): Arr[Double] =
    val line = Line.fromPoints(Wide, points)
    val narrowed = line.narrowAtTightTurns(proximity = proximity)
    val out = Arr[Double]()
    var i = 0
    while i < narrowed.vertCount do
      out.push(narrowed.get(i).width * 0.5)
      i += 1
    out

  /** `count` points along a circle of radius `r`, spanning `sweep` radians. */
  private def arc(r: Double, sweep: Double, count: Int): Arr[Vec2] =
    val out = Arr[Vec2]()
    var i = 0
    while i < count do
      val t = sweep * i / (count - 1)
      out.push(Vec2(r * math.cos(t), r * math.sin(t)))
      i += 1
    out

  /** The half extent an unconstrained vertex keeps — nothing narrowed it. */
  private val Unlimited = Wide * 0.5

  private def assertUnlimited(l: Double, what: String): Unit =
    assertEqualsDouble(l, Unlimited, 1e-9, s"$what was narrowed to $l")

  test("a straight line constrains nothing"):
    val ls = limits(Arr(Vec2(0, 0), Vec2(1, 0), Vec2(2, 0), Vec2(3, 0)))
    var i = 0
    while i < ls.length do
      assertUnlimited(ls(i), s"vertex $i")
      i += 1

  test("the ends are never limited — they have no corner"):
    val ls = limits(Arr(Vec2(0, 0), Vec2(4, 0), Vec2(4, 4)))
    assertUnlimited(ls(0), "the first vertex")
    assertUnlimited(ls(2), "the last vertex")

  test("one sharp corner is limited by its shorter segment"):
    // a right angle: the inner offsets meet halfExtent * tan(45°) back along
    // each segment, so the limit is the shorter segment itself
    val ls = limits(Arr(Vec2(0, 0), Vec2(4, 0), Vec2(4, 3)))
    assertEqualsDouble(ls(1), 3.0, 1e-6)

  test("a sharper corner is limited harder than a gentler one"):
    val gentle = limits(Arr(Vec2(0, 0), Vec2(4, 0), Vec2(8, 3)))
    val sharp = limits(Arr(Vec2(0, 0), Vec2(4, 0), Vec2(1, 3)))
    assert(
      sharp(1) < gentle(1),
      s"sharp ${sharp(1)} should be under gentle ${gentle(1)}",
    )

  test("many small turns are caught by the window, not by any one corner"):
    // an arc of radius 1: each vertex turns a fraction of a degree, so the
    // single-corner test alone would allow about 2 — twice the radius, which
    // folds. The window has to bring it down to the radius itself.
    val ls = limits(arc(1.0, math.Pi * 0.5, 60))
    val mid = ls(30)
    assertEqualsDouble(mid, 1.0, 0.05)
    assert(mid < 1.5, s"the window did not bind: $mid")

  test("the window limit scales with the radius it measures"):
    val small = limits(arc(1.0, math.Pi * 0.5, 60))(30)
    val large = limits(arc(4.0, math.Pi * 0.5, 60))(30)
    assertEqualsDouble(large / small, 4.0, 0.1)

  test("two opposite curves: the limit is tightest at each curve's center"):
    // y = sin(x) over a full period — curvature peaks at the crest and the
    // trough, which bend opposite ways, and falls to nothing at the crossings
    val points = Arr[Vec2]()
    val count = 121
    var i = 0
    while i < count do
      val x = math.Pi * 2.0 * i / (count - 1)
      points.push(Vec2(x, math.sin(x)))
      i += 1
    val ls = limits(points)

    val crest = count / 4 // x = pi/2
    val trough = count * 3 / 4 // x = 3pi/2
    val crossing = count / 2 // x = pi, where the curve is straightest

    // both curve centers are limited
    assert(ls(crest) < 1.5, s"crest not limited: ${ls(crest)}")
    assert(ls(trough) < 1.5, s"trough not limited: ${ls(trough)}")

    // where the turn changes direction nothing can fold, so nothing is
    // narrowed — the two bends either side of it must cancel in the window
    // rather than adding up into one long bend
    assertUnlimited(ls(crossing), "the inflection")

    // and the limit relaxes on the way from a curve's center out to it
    assert(
      ls(crest + 20) > ls(crest),
      s"limit did not relax away from the crest: ${ls(crest)} then " +
        s"${ls(crest + 20)}",
    )

  /** A symmetric V: `arm` long each side, `interior` radians between the arms,
    * apex at the origin opening along +x.
    */
  private def vee(arm: Double, interior: Double, count: Int): Arr[Vec2] =
    val half = interior * 0.5
    val out = Arr[Vec2]()
    var i = count
    while i >= 1 do
      val d = arm * i / count
      out.push(Vec2(d * math.cos(half), -d * math.sin(half)))
      i -= 1
    out.push(Vec2(0, 0))
    var j = 1
    while j <= count do
      val d = arm * j / count
      out.push(Vec2(d * math.cos(half), d * math.sin(half)))
      j += 1
    out

  test("a narrow V's arms are left alone unless proximity is asked for"):
    // the apex is a real corner and the curvature limit narrows it, but the
    // arms are straight, so by default nothing else in the V is touched
    val points = vee(1.0, math.Pi * 50.0 / 180.0, 10)
    val ls = limits(points)
    assert(ls(10) < Unlimited, "the apex should still be narrowed")
    assertUnlimited(ls(5), "a straight arm vertex")
    assertUnlimited(ls(15), "a straight arm vertex")

  test("a narrow V is limited by its arms' proximity, not by any turn"):
    // 50 degrees between the arms. Every vertex along an arm is straight, so
    // the curvature test says nothing at all — but the two arms are close
    // enough in space that a wide stroke would run each one through the other.
    val points = vee(1.0, math.Pi * 50.0 / 180.0, 10)
    val ls = limits(points, proximity = true)
    val mid = ls(5) // halfway along the first arm

    assert(mid < Unlimited, "a straight arm vertex went unconstrained")
    // the gap to the opposite arm at that distance from the apex, halved
    val d = 0.5
    val expected = d * math.sin(math.Pi * 25.0 / 180.0)
    assertEqualsDouble(mid, expected, expected * 0.25)

  test("the proximity limit grows with the gap"):
    val narrow = limits(vee(1.0, math.Pi * 30.0 / 180.0, 10), true)(5)
    val wide = limits(vee(1.0, math.Pi * 90.0 / 180.0, 10), true)(5)
    assert(
      wide > narrow * 1.5,
      s"a 90 degree V ($wide) should allow far more than a 30 degree one " +
        s"($narrow)",
    )

  test("a straight line is not constrained by its own neighbours"):
    // the exclusion has to be scale-free: a long line and a short one are
    // equally unconstrained, however densely they are sampled
    val sparse = Arr(Vec2(0, 0), Vec2(1, 0), Vec2(2, 0), Vec2(3, 0))
    val dense = Arr[Vec2]()
    var i = 0
    while i < 40 do
      dense.push(Vec2(i * 0.01, 0))
      i += 1
    limits(sparse, true).foreach(l => assertUnlimited(l, "a sparse vertex"))
    limits(dense, true).foreach(l => assertUnlimited(l, "a dense vertex"))

  test("narrowing never widens a line that already fits"):
    val points = arc(1.0, math.Pi * 0.5, 60)
    val line = Line.fromPoints(0.1, points)
    val narrowed = line.narrowAtTightTurns()
    var i = 0
    while i < narrowed.vertCount do
      assert(narrowed.get(i).width <= 0.1 + 1e-9)
      i += 1

  test("the factor scales the result"):
    val full = limits(Arr(Vec2(0, 0), Vec2(4, 0), Vec2(4, 3)))(1)
    val half = Line
      .fromPoints(Wide, Arr(Vec2(0, 0), Vec2(4, 0), Vec2(4, 3)))
      .narrowAtTightTurns(0.5)
      .get(1)
      .width * 0.5
    assertEqualsDouble(half, full * 0.5, 1e-6)
