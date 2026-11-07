package trivalibs.graphics.lib.color

// CPU color conversions — the same Iñigo Quilez formulation
// (https://www.shadertoy.com/view/MsS3Wc) as the GPU
// `trivalibs.graphics.shader.lib.color.Color`, so a color computed on the CPU
// and uploaded as a uniform matches one computed shader-side from the same
// HSV. Hue is in [0, 1] (1.0 == 360°) throughout, never degrees; the `…2rgb`
// conversions accept any hue and wrap it themselves.
//
// HSL vs HSV — both share hue and saturation axes but differ in the third
// channel:
//   - HSV "value" V = max(R, G, B). Pure red is (0, 1, 1), white is (0, 0, 1).
//   - HSL "lightness" L = (max + min) / 2. Pure red is (0, 1, 0.5), white is
//     (0, 0, 1).
// They are NOT round-trip-equivalent: rgb2hsl(hsv2rgb(c)) ≠ c, and likewise.

import scala.compiletime.erasedValue
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.gpu.Vec3Expr
import trivalibs.graphics.shader.lib.color.Color as GpuColor
import trivalibs.utils.numbers.NumExt.given

// ---------------------------------------------------------------------------
// Shared extensions — one definition for CPU `Vec3` and GPU `Vec3Expr`
// receivers, branching at compile time (see `trivalibs.graphics.lib` args).
// ---------------------------------------------------------------------------

extension [C <: Vec3 | Vec3Expr](c: C)
  /** RGB → HSV, `value = max(R, G, B)`. */
  transparent inline def rgb2hsv: Vec3 | Vec3Expr = inline erasedValue[C] match
    case _: Vec3 => Color.rgb2hsv(c.asInstanceOf[Vec3])
    case _: Vec3Expr => GpuColor.rgb2hsv(c.asInstanceOf[Vec3Expr])

  /** RGB → HSL, `lightness = (max + min) / 2`. */
  transparent inline def rgb2hsl: Vec3 | Vec3Expr = inline erasedValue[C] match
    case _: Vec3 => Color.rgb2hsl(c.asInstanceOf[Vec3])
    case _: Vec3Expr => GpuColor.rgb2hsl(c.asInstanceOf[Vec3Expr])

  /** HSV → RGB, piecewise linear (cheapest; visible band edges). Hue may be any
    * value: it is wrapped into [0, 1) internally, so no `fract` is needed
    * before the call, and hues below 0 or above 1 keep rotating the spectrum.
    */
  transparent inline def hsv2rgb: Vec3 | Vec3Expr = inline erasedValue[C] match
    case _: Vec3 => Color.hsv2rgb(c.asInstanceOf[Vec3])
    case _: Vec3Expr => GpuColor.hsv2rgb(c.asInstanceOf[Vec3Expr])

  /** HSV → RGB with a cubic smoothstep on the ramp. Hue may be any value; it
    * wraps internally, as in [[hsv2rgb]].
    */
  transparent inline def hsv2rgbSmooth: Vec3 | Vec3Expr = inline erasedValue[C] match
    case _: Vec3 => Color.hsv2rgbSmooth(c.asInstanceOf[Vec3])
    case _: Vec3Expr => GpuColor.hsv2rgbSmooth(c.asInstanceOf[Vec3Expr])

  /** HSV → RGB with a quintic smootherstep on the ramp. Hue may be any value;
    * it wraps internally, as in [[hsv2rgb]].
    */
  transparent inline def hsv2rgbSmoother: Vec3 | Vec3Expr = inline erasedValue[C] match
    case _: Vec3 => Color.hsv2rgbSmoother(c.asInstanceOf[Vec3])
    case _: Vec3Expr => GpuColor.hsv2rgbSmoother(c.asInstanceOf[Vec3Expr])

  /** HSL → RGB. Hue may be any value; it wraps internally, as in [[hsv2rgb]]. */
  transparent inline def hsl2rgb: Vec3 | Vec3Expr = inline erasedValue[C] match
    case _: Vec3 => Color.hsl2rgb(c.asInstanceOf[Vec3])
    case _: Vec3Expr => GpuColor.hsl2rgb(c.asInstanceOf[Vec3Expr])

/** Color conversions on the CPU — same names and formulation as the GPU
  * `Color`. Everything here is `inline` over the scalar [[kernel]]s, which
  * also come in `…Into(out)` forms for hot loops.
  */
object Color:

  inline def rgb2hsv(c: Vec3): Vec3 = kernel.rgb2hsvInto(c.x, c.y, c.z, new Vec3())
  inline def rgb2hsl(c: Vec3): Vec3 = kernel.rgb2hslInto(c.x, c.y, c.z, new Vec3())
  inline def hsv2rgb(c: Vec3): Vec3 = kernel.hsv2rgbInto(c.x, c.y, c.z, new Vec3())
  inline def hsv2rgbSmooth(c: Vec3): Vec3 =
    kernel.hsv2rgbSmoothInto(c.x, c.y, c.z, new Vec3())
  inline def hsv2rgbSmoother(c: Vec3): Vec3 =
    kernel.hsv2rgbSmootherInto(c.x, c.y, c.z, new Vec3())
  inline def hsl2rgb(c: Vec3): Vec3 = kernel.hsl2rgbInto(c.x, c.y, c.z, new Vec3())

  /** Scalar kernels writing the result into `out` and returning it. */
  object kernel:

    /** The IQ hue ramp: one channel of `clamp(abs(fract(h + k)·6 − 3) − 1, 0,
      * 1)`, offset `k` being 0 / ⅔ / ⅓ for R / G / B. `fract` floors, so
      * negative hues wrap like positive ones.
      */
    private inline def hueRamp(h: Double, k: Double): Double =
      (((h + k).fract * 6.0 - 3.0).abs - 1.0).clamp01

    private inline val kG = 2.0 / 3.0
    private inline val kB = 1.0 / 3.0

    private inline def smooth(t: Double): Double = t * t * (3.0 - 2.0 * t)

    private inline def smoother(t: Double): Double =
      t * t * t * (t * (t * 6.0 - 15.0) + 10.0)

    def hsv2rgbInto(h: Double, s: Double, v: Double, out: Vec3): Vec3 =
      out.x = v * (1.0 + (hueRamp(h, 0.0) - 1.0) * s)
      out.y = v * (1.0 + (hueRamp(h, kG) - 1.0) * s)
      out.z = v * (1.0 + (hueRamp(h, kB) - 1.0) * s)
      out

    def hsv2rgbSmoothInto(h: Double, s: Double, v: Double, out: Vec3): Vec3 =
      out.x = v * (1.0 + (smooth(hueRamp(h, 0.0)) - 1.0) * s)
      out.y = v * (1.0 + (smooth(hueRamp(h, kG)) - 1.0) * s)
      out.z = v * (1.0 + (smooth(hueRamp(h, kB)) - 1.0) * s)
      out

    def hsv2rgbSmootherInto(h: Double, s: Double, v: Double, out: Vec3): Vec3 =
      out.x = v * (1.0 + (smoother(hueRamp(h, 0.0)) - 1.0) * s)
      out.y = v * (1.0 + (smoother(hueRamp(h, kG)) - 1.0) * s)
      out.z = v * (1.0 + (smoother(hueRamp(h, kB)) - 1.0) * s)
      out

    def hsl2rgbInto(h: Double, s: Double, l: Double, out: Vec3): Vec3 =
      val chroma = 1.0 - (2.0 * l - 1.0).abs
      out.x = l + s * (hueRamp(h, 0.0) - 0.5) * chroma
      out.y = l + s * (hueRamp(h, kG) - 0.5) * chroma
      out.z = l + s * (hueRamp(h, kB) - 0.5) * chroma
      out

    // The WGSL encodes the two orderings of rgb2hsv/rgb2hsl as `mix`/`step`
    // over a vec4; on the CPU the same two branches are cheaper and clearer.

    def rgb2hsvInto(r: Double, g: Double, b: Double, out: Vec3): Vec3 =
      val gGeB = g >= b
      val px = if gGeB then g else b
      val py = if gGeB then b else g
      val pz = if gGeB then 0.0 else -1.0
      val pw = if gGeB then -1.0 / 3.0 else 2.0 / 3.0
      val rGeP = r >= px
      val qx = if rGeP then r else px
      val qy = py
      val qz = if rGeP then pz else pw
      val qw = if rGeP then px else r
      val d = qx - qy.min(qw)
      val e = 1.0e-10
      out.x = (qz + (qw - qy) / (6.0 * d + e)).abs
      out.y = d / (qx + e)
      out.z = qx
      out

    def rgb2hslInto(r: Double, g: Double, b: Double, out: Vec3): Vec3 =
      val gGeB = g >= b
      val px = if gGeB then g else b
      val py = if gGeB then b else g
      val pz = if gGeB then 0.0 else -1.0
      val pw = if gGeB then -1.0 / 3.0 else 2.0 / 3.0
      val rGeP = r >= px
      val qx = if rGeP then r else px
      val qy = py
      val qz = if rGeP then pz else pw
      val qw = if rGeP then px else r
      val d = qx - qy.min(qw)
      val l = qx - d * 0.5
      val e = 1.0e-10
      out.x = (qz + (qw - qy) / (6.0 * d + e)).abs
      out.y = d / (1.0 - (2.0 * l - 1.0).abs + e)
      out.z = l
      out
