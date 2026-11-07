package trivalibs.utils

import munit.FunSuite
import trivalibs.utils.js.Arr
import trivalibs.utils.random.*

// Statistical helpers are sampled rather than asserted exactly — each test runs
// enough draws that a broken range or a lost element is caught deterministically.
class RandomTest extends FunSuite:

  private val n = 2000

  test("randInt stays in [0, max)"):
    var i = 0
    while i < n do
      val v = randInt(5)
      assert(v >= 0 && v < 5, s"randInt(5) returned $v")
      i += 1

  test("randInt covers every bucket"):
    val seen = Arr[Boolean](false, false, false, false)
    var i = 0
    while i < n do
      seen(randInt(4)) = true
      i += 1
    assert(seen.forall(identity), "randInt(4) never hit some bucket")

  test("randIntInRange stays in [min, max)"):
    var i = 0
    while i < n do
      val v = randIntInRange(-3, 4)
      assert(v >= -3 && v < 4, s"randIntInRange(-3, 4) returned $v")
      i += 1

  test("randSign is only ±1, and hits both"):
    var neg = 0
    var pos = 0
    var i = 0
    while i < n do
      val v = randSign()
      if v == -1.0 then neg += 1
      else if v == 1.0 then pos += 1
      else fail(s"randSign() returned $v")
      i += 1
    assert(neg > 0 && pos > 0)

  test("randBool hits both values"):
    var t = 0
    var i = 0
    while i < n do
      if randBool() then t += 1
      i += 1
    assert(t > 0 && t < n)

  test("randNormal01 stays in [0, 1] and centers on 0.5"):
    var sum = 0.0
    var i = 0
    while i < n do
      val v = randNormal01()
      assert(v >= 0.0 && v <= 1.0, s"randNormal01() returned $v")
      sum += v
      i += 1
    assertEqualsDouble(sum / n, 0.5, 0.05)

  test("randNormal11 stays in [-1, 1] and centers on 0"):
    var sum = 0.0
    var i = 0
    while i < n do
      val v = randNormal11()
      assert(v >= -1.0 && v <= 1.0, s"randNormal11() returned $v")
      sum += v
      i += 1
    assertEqualsDouble(sum / n, 0.0, 0.1)

  test("randNormal01 is tighter than uniform"):
    // The Bates mean of three uniforms has variance 1/36 vs the uniform's 1/12,
    // so far fewer samples land in the outer fifths.
    var normalTails = 0
    var uniformTails = 0
    var i = 0
    while i < n do
      if randNormal01() < 0.2 || randNormal01() > 0.8 then normalTails += 1
      if rand() < 0.2 || rand() > 0.8 then uniformTails += 1
      i += 1
    assert(
      normalTails < uniformTails,
      s"normal tails $normalTails should be below uniform tails $uniformTails",
    )

  test("randVec* components stay in range"):
    val v3 = randVec3()
    assert(v3.x >= 0.0 && v3.x < 1.0)
    assert(v3.y >= 0.0 && v3.y < 1.0)
    assert(v3.z >= 0.0 && v3.z < 1.0)
    val r = randVec2InRange(-2.0, -1.0)
    assert(r.x >= -2.0 && r.x < -1.0)
    assert(r.y >= -2.0 && r.y < -1.0)
    val perAxis =
      randVec4InRange(Vec4Bounds.min, Vec4Bounds.max)
    assert(perAxis.x >= 0.0 && perAxis.x < 1.0)
    assert(perAxis.y >= 10.0 && perAxis.y < 20.0)
    assert(perAxis.z >= -1.0 && perAxis.z < 0.0)
    assert(perAxis.w >= 5.0 && perAxis.w < 5.5)

  test("pick returns an element of the array"):
    val xs = Arr("a", "b", "c")
    var i = 0
    while i < 200 do
      assert(xs.indexOf(xs.pick()) >= 0)
      i += 1

  test("pick on an empty array throws"):
    intercept[Exception](Arr[String]().pick())

  test("shuffle permutes in place, preserving every element"):
    val xs = Arr(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    xs.shuffle()
    assertEquals(xs.length, 10)
    var v = 0
    while v < 10 do
      assert(xs.indexOf(v) >= 0, s"shuffle() lost $v")
      v += 1

  test("shuffle actually reorders"):
    // 10! orderings — hitting the identity 20 times running is impossible.
    var moved = false
    var i = 0
    while i < 20 && !moved do
      val xs = Arr(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
      xs.shuffle()
      if xs(0) != 0 || xs(1) != 1 then moved = true
      i += 1
    assert(moved, "shuffle() never changed the order")

  test("shuffled copies, leaving the source untouched"):
    val xs = Arr(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    val out = xs.shuffled()
    assertEquals(xs(0), 0)
    assertEquals(xs(9), 9)
    assertEquals(out.length, 10)
    var v = 0
    while v < 10 do
      assert(out.indexOf(v) >= 0, s"shuffled() lost $v")
      v += 1

object Vec4Bounds:
  import trivalibs.graphics.math.cpu.Vec4
  val min = Vec4(0.0, 10.0, -1.0, 5.0)
  val max = Vec4(1.0, 20.0, 0.0, 5.5)
