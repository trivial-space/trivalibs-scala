package trivalibs.graphics.math.cpu

import trivalibs.bufferdata.F32
import trivalibs.bufferdata.F64
import trivalibs.bufferdata.StructRef
import trivalibs.graphics.math.*

// === implementations for common vector types ===

// ==== Buffer types ====
// Note: Vec2Buffer uses F32 (for GPU upload), Vec2dBuffer uses F64

type Vec2Buffer = (F32, F32)

object Vec2Buffer:
  given vec2MutableBuffer: Vec2Mutable[StructRef[Vec2Buffer]]:
    extension (v: StructRef[Vec2Buffer])
      inline def x = v.getAt(0): Double
      inline def y = v.getAt(1): Double
      inline def x_=(value: Double) = v.setAt(0)(value.toFloat)
      inline def y_=(value: Double) = v.setAt(1)(value.toFloat)

  given vec2MutableOpsBuffer: Vec2MutableOps[StructRef[Vec2Buffer]] =
    new Vec2MutableOps[StructRef[Vec2Buffer]] {}

type Vec2dBuffer = (F64, F64)

object Vec2dBuffer:
  given vec2MutableDBuffer: Vec2Mutable[StructRef[Vec2dBuffer]]:
    extension (v: StructRef[Vec2dBuffer])
      inline def x = v.getAt(0): Double
      inline def y = v.getAt(1): Double
      inline def x_=(value: Double) = v.setAt(0)(value)
      inline def y_=(value: Double) = v.setAt(1)(value)

  given vec2MutableOpsDBuffer: Vec2MutableOps[StructRef[Vec2dBuffer]] =
    new Vec2MutableOps[StructRef[Vec2dBuffer]] {}

// ===== Tuple and class types =====

type Vec2Tuple = (Double, Double)

object Vec2Tuple extends Vec2ImmutableOps[Vec2Tuple]:
  inline def create(x: Double, y: Double) = (x, y)
  given Vec2ImmutableOps[Vec2Tuple] = Vec2Tuple
  val zero: Vec2Tuple = (0.0, 0.0)
  val X: Vec2Tuple = (1.0, 0.0)
  val Y: Vec2Tuple = (0.0, 1.0)

  given Vec2Base[Vec2Tuple]:
    extension (v: Vec2Tuple)
      inline def x = v._1
      inline def y = v._2

/** Mutable 2D vector. `Vec2(x, y)`; `+ - * /`, `.dot`, `.length`, `.normalize`,
  * `.mix`, in-place variants, swizzles; companion `zero`/`one`/`X`/`Y`.
  * Siblings: `Vec2Tuple` (immutable), `Vec2Buffer` (GPU).
  */
class Vec2(var x: Double = 0.0, var y: Double = 0.0)

object Vec2 extends Vec2ImmutableOps[Vec2]:
  inline def create(x: Double, y: Double) = new Vec2(x, y)
  given Vec2ImmutableOps[Vec2] = Vec2

  /** Concrete, so generic `[T: Lerp]` callers get a module and nothing
    * allocates at any call site. A parameterized instance would put an
    * anonymous class at each call site and allocate on every call. The tuple
    * representation has its own concrete instance in `LerpBy`'s companion,
    * since `Vec2Tuple`'s companion is not in the implicit scope of `Lerp[…]`.
    * The buffer representation has none. See
    * `documents/lerp-typeclass-refactor.md`.
    */
  given lerpInstance: LerpBy[Vec2, Double]:
    extension (a: Vec2) inline def lerp(b: Vec2, t: Double): Vec2 = a.mix(b, t)

  def apply(x: Double, y: Double): Vec2 = new Vec2(x, y)
  def apply(scalar: Double): Vec2 = new Vec2(scalar, scalar)

  def zero: Vec2 = new Vec2(0.0, 0.0)
  def one: Vec2 = new Vec2(1.0, 1.0)
  def X: Vec2 = new Vec2(1.0, 0.0)
  def Y: Vec2 = new Vec2(0.0, 1.0)

  given Vec2Mutable[Vec2]:
    extension (v: Vec2)
      inline def x = v.x
      inline def y = v.y
      inline def x_=(value: Double) = v.x = value
      inline def y_=(value: Double) = v.y = value

  given Vec2MutableOps[Vec2] = new Vec2MutableOps[Vec2] {}

  given Conversion[Vec2, Vec2Tuple] = _.toTuple
  given Conversion[Vec2Tuple, Vec2] = _.toVec2
