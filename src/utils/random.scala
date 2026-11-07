package trivalibs.utils.random

import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.cpu.Vec4
import trivalibs.utils.js.Arr

import scala.scalajs.js

private inline def random: Double =
  js.Math.random()

// ---------------------------------------------------------------------------
// Scalars
// ---------------------------------------------------------------------------

/** Uniform `Double` in `[0, 1)`. */
def rand(): Double = random

/** Uniform `Double` in `[min, max)`. */
def randInRange(min: Double, max: Double): Double =
  random * (max - min) + min

/** Uniform `Int` in `[0, max)` — `max` itself is never returned. */
def randInt(max: Int): Int =
  (random * max).toInt

/** Uniform `Int` in `[min, max)` — `max` itself is never returned. */
def randIntInRange(min: Int, max: Int): Int =
  min + (random * (max - min)).toInt

/** `true` or `false` with equal probability. */
def randBool(): Boolean = random < 0.5

/** `-1.0` or `1.0` with equal probability. */
def randSign(): Double = if random < 0.5 then -1.0 else 1.0

/** Approximately normal-distributed `Double` in `[0, 1]`, centered on `0.5` —
  * the mean of three uniform samples (Irwin–Hall / Bates).
  */
def randNormal01(): Double = (random + random + random) / 3.0

/** Approximately normal-distributed `Double` in `[-1, 1]`, centered on `0` —
  * [[randNormal01]] fitted to `[-1, 1]`.
  */
def randNormal11(): Double = randNormal01() * 2.0 - 1.0

// ---------------------------------------------------------------------------
// Vectors
// ---------------------------------------------------------------------------

/** `Vec2` with each component uniform in `[0, 1)`. */
def randVec2(): Vec2 = Vec2(random, random)

/** `Vec2` with each component uniform in `[min, max)`. */
def randVec2InRange(min: Double, max: Double): Vec2 =
  Vec2(randInRange(min, max), randInRange(min, max))

/** `Vec2` with each component uniform between the matching components of `min`
  * and `max`.
  */
def randVec2InRange(min: Vec2, max: Vec2): Vec2 =
  Vec2(randInRange(min.x, max.x), randInRange(min.y, max.y))

/** `Vec3` with each component uniform in `[0, 1)`. */
def randVec3(): Vec3 = Vec3(random, random, random)

/** `Vec3` with each component uniform in `[min, max)`. */
def randVec3InRange(min: Double, max: Double): Vec3 =
  Vec3(randInRange(min, max), randInRange(min, max), randInRange(min, max))

/** `Vec3` with each component uniform between the matching components of `min`
  * and `max`.
  */
def randVec3InRange(min: Vec3, max: Vec3): Vec3 =
  Vec3(
    randInRange(min.x, max.x),
    randInRange(min.y, max.y),
    randInRange(min.z, max.z),
  )

/** `Vec4` with each component uniform in `[0, 1)`. */
def randVec4(): Vec4 = Vec4(random, random, random, random)

/** `Vec4` with each component uniform in `[min, max)`. */
def randVec4InRange(min: Double, max: Double): Vec4 =
  Vec4(
    randInRange(min, max),
    randInRange(min, max),
    randInRange(min, max),
    randInRange(min, max),
  )

/** `Vec4` with each component uniform between the matching components of `min`
  * and `max`.
  */
def randVec4InRange(min: Vec4, max: Vec4): Vec4 =
  Vec4(
    randInRange(min.x, max.x),
    randInRange(min.y, max.y),
    randInRange(min.z, max.z),
    randInRange(min.w, max.w),
  )

// ---------------------------------------------------------------------------
// Arr
// ---------------------------------------------------------------------------

extension [A](arr: Arr[A])

  /** A uniformly picked element. Throws on an empty array. */
  def pick(): A =
    if arr.length == 0 then
      throw trivalibs.utils.js.jsError("pick(): empty array")
    arr((random * arr.length).toInt)

  /** Shuffles `arr` **in place** (Fisher–Yates). See [[shuffled]] for the
    * copying variant.
    */
  def shuffle(): Unit =
    var i = arr.length - 1
    while i > 0 do
      val j = (random * (i + 1)).toInt
      val tmp = arr(i)
      arr(i) = arr(j)
      arr(j) = tmp
      i -= 1

  /** A shuffled copy of `arr`, leaving `arr` untouched. */
  def shuffled(): Arr[A] =
    val out = Arr.from(arr)
    out.shuffle()
    out
