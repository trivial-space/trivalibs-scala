package trivalibs.graphics.math.cpu

import trivalibs.bufferdata.F32
import trivalibs.bufferdata.F64
import trivalibs.bufferdata.StructRef
import trivalibs.graphics.math.*

// === implementations for common vector types ===

// ==== Buffer types ====
// Note: Vec4Buffer uses F32 (for GPU upload), Vec4dBuffer uses F64

type Vec4Buffer = (F32, F32, F32, F32)

object Vec4Buffer:
  given vec4MutableBuffer: Vec4Mutable[StructRef[Vec4Buffer]]:
    extension (v: StructRef[Vec4Buffer])
      inline def x = v.getAt(0): Double
      inline def y = v.getAt(1): Double
      inline def z = v.getAt(2): Double
      inline def w = v.getAt(3): Double
      inline def x_=(value: Double) = v.setAt(0)(value.toFloat)
      inline def y_=(value: Double) = v.setAt(1)(value.toFloat)
      inline def z_=(value: Double) = v.setAt(2)(value.toFloat)
      inline def w_=(value: Double) = v.setAt(3)(value.toFloat)

  given vec4MutableOpsBuffer: Vec4MutableOps[StructRef[Vec4Buffer]] =
    new Vec4MutableOps[StructRef[Vec4Buffer]] {}

type Vec4dBuffer = (F64, F64, F64, F64)

object Vec4dBuffer:
  given vec4MutableDBuffer: Vec4Mutable[StructRef[Vec4dBuffer]]:
    extension (v: StructRef[Vec4dBuffer])
      inline def x = v.getAt(0): Double
      inline def y = v.getAt(1): Double
      inline def z = v.getAt(2): Double
      inline def w = v.getAt(3): Double
      inline def x_=(value: Double) = v.setAt(0)(value)
      inline def y_=(value: Double) = v.setAt(1)(value)
      inline def z_=(value: Double) = v.setAt(2)(value)
      inline def w_=(value: Double) = v.setAt(3)(value)

  given vec4MutableOpsDBuffer: Vec4MutableOps[StructRef[Vec4dBuffer]] =
    new Vec4MutableOps[StructRef[Vec4dBuffer]] {}

// ===== Tuple and class types =====

type Vec4Tuple = (Double, Double, Double, Double)

object Vec4Tuple extends Vec4ImmutableOps[Vec4Tuple]:
  inline def create(x: Double, y: Double, z: Double, w: Double) = (x, y, z, w)
  given Vec4ImmutableOps[Vec4Tuple] = Vec4Tuple
  val zero: Vec4Tuple = (0.0, 0.0, 0.0, 0.0)
  val X: Vec4Tuple = (1.0, 0.0, 0.0, 0.0)
  val Y: Vec4Tuple = (0.0, 1.0, 0.0, 0.0)
  val Z: Vec4Tuple = (0.0, 0.0, 1.0, 0.0)
  val W: Vec4Tuple = (0.0, 0.0, 0.0, 1.0)

  given Vec4Base[Vec4Tuple]:
    extension (v: Vec4Tuple)
      inline def x = v._1
      inline def y = v._2
      inline def z = v._3
      inline def w = v._4

/** Mutable 4D vector (also used for RGBA colors). `Vec4(x, y, z, w)`;
  * `+ - * /`, `.dot`, `.length`, `.normalize`, `.mix`, in-place variants,
  * swizzles (`.xyz`, `.rgb`, …); companion `zero`/`one`. Siblings: `Vec4Tuple`
  * (immutable), `Vec4Buffer` (GPU).
  */
class Vec4(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var w: Double = 0.0,
)

object Vec4 extends Vec4ImmutableOps[Vec4]:
  inline def create(x: Double, y: Double, z: Double, w: Double) =
    new Vec4(x, y, z, w)
  given Vec4ImmutableOps[Vec4] = Vec4

  /** Concrete, so generic `[T: Lerp]` callers get a module and nothing
    * allocates at any call site. A parameterized instance would put an
    * anonymous class at each call site and allocate on every call. The tuple
    * representation has its own concrete instance in `LerpBy`'s companion,
    * since `Vec4Tuple`'s companion is not in the implicit scope of `Lerp[…]`.
    * The buffer representation has none. See
    * `documents/lerp-typeclass-refactor.md`.
    */
  given lerpInstance: LerpBy[Vec4, Double]:
    extension (a: Vec4) inline def lerp(b: Vec4, t: Double): Vec4 = a.mix(b, t)

  def apply(x: Double, y: Double, z: Double, w: Double): Vec4 =
    new Vec4(x, y, z, w)
  def apply(scalar: Double): Vec4 = new Vec4(scalar, scalar, scalar, scalar)
  def apply(xyz: Vec3, w: Double): Vec4 = new Vec4(xyz.x, xyz.y, xyz.z, w)
  def apply(xy: Vec2, z: Double, w: Double): Vec4 = new Vec4(xy.x, xy.y, z, w)

  def zero: Vec4 = new Vec4(0.0, 0.0, 0.0, 0.0)
  def one: Vec4 = new Vec4(1.0, 1.0, 1.0, 1.0)
  def X: Vec4 = new Vec4(1.0, 0.0, 0.0, 0.0)
  def Y: Vec4 = new Vec4(0.0, 1.0, 0.0, 0.0)
  def Z: Vec4 = new Vec4(0.0, 0.0, 1.0, 0.0)
  def W: Vec4 = new Vec4(0.0, 0.0, 0.0, 1.0)

  given Vec4Mutable[Vec4]:
    extension (v: Vec4)
      inline def x = v.x
      inline def y = v.y
      inline def z = v.z
      inline def w = v.w
      inline def x_=(value: Double) = v.x = value
      inline def y_=(value: Double) = v.y = value
      inline def z_=(value: Double) = v.z = value
      inline def w_=(value: Double) = v.w = value

  given Vec4MutableOps[Vec4] = new Vec4MutableOps[Vec4] {}

  given Conversion[Vec4, Vec4Tuple] = _.toTuple
  given Conversion[Vec4Tuple, Vec4] = _.toVec4
