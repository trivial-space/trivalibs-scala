package trivalibs.graphics.shader.lib.color

// hsv2rgb family adapted from Iñigo Quilez — https://www.shadertoy.com/view/MsS3Wc
//
// HSL vs HSV — both share hue and saturation axes but differ in the third
// channel:
//   - HSV "value" V = max(R, G, B). Pure red is (0, 1, 1), white is (0, 0, 1),
//     black is (0, 0, 0). Saturation desaturates toward white only at V = 1.
//   - HSL "lightness" L = (max + min) / 2. Pure red is (0, 1, 0.5), white is
//     (0, 0, 1), black is (0, 0, 0). L is symmetric — moving L from 0.5 toward
//     0 darkens, toward 1 brightens.
// They are NOT round-trip-equivalent: rgb2hsl(hsv2rgb(c)) ≠ c, and likewise.
// Pick the space that matches your intuition for the third channel and stay
// in it.

import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.gpu.Vec3Expr
import trivalibs.graphics.shader.dsl.WgslFn
import trivalibs.graphics.shader.given

/** Color conversions on the GPU. Hue is in `[0, 1]` (1.0 == 360°) throughout;
  * the `…2rgb` fns accept any hue and wrap it themselves (`fract`), so no
  * wrapping is needed before the call.
  *
  * The CPU mirror is `trivalibs.graphics.lib.color.Color`, with the same
  * names and formulation; the shared extensions (`c.hsv2rgb`, …) cover both.
  */
object Color:

  /** RGB → HSV, `value = max(R, G, B)`. */
  inline def rgb2hsv(c: Vec3Expr): Vec3Expr = wgsl.rgb2hsv(c)

  /** RGB → HSL, `lightness = (max + min) / 2`. */
  inline def rgb2hsl(c: Vec3Expr): Vec3Expr = wgsl.rgb2hsl(c)

  /** HSV → RGB, piecewise linear (cheapest; visible band edges). Any hue;
    * wrapped internally.
    */
  inline def hsv2rgb(c: Vec3Expr): Vec3Expr = wgsl.hsv2rgb(c)

  /** HSV → RGB with a cubic smoothstep on the ramp. Any hue; wrapped
    * internally.
    */
  inline def hsv2rgbSmooth(c: Vec3Expr): Vec3Expr = wgsl.hsv2rgbSmooth(c)

  /** HSV → RGB with a quintic smootherstep on the ramp. Any hue; wrapped
    * internally.
    */
  inline def hsv2rgbSmoother(c: Vec3Expr): Vec3Expr = wgsl.hsv2rgbSmoother(c)

  /** HSL → RGB. Any hue; wrapped internally. */
  inline def hsl2rgb(c: Vec3Expr): Vec3Expr = wgsl.hsl2rgb(c)

  /** HSL → RGB with a cubic smoothstep on the ramp. Any hue; wrapped
    * internally.
    */
  inline def hsl2rgbSmooth(c: Vec3Expr): Vec3Expr = wgsl.hsl2rgbSmooth(c)

  /** HSL → RGB with a quintic smootherstep on the ramp. Any hue; wrapped
    * internally.
    */
  inline def hsl2rgbSmoother(c: Vec3Expr): Vec3Expr = wgsl.hsl2rgbSmoother(c)

  /** The WgslFn definitions — the layer for `.withDeps` and raw WGSL
    * composition (`hsv2rgb` emits `color_hsv2rgb`).
    */
  object wgsl:

    // ---------------------------------------------------------------------------
    // RGB → HSV / HSL
    // ---------------------------------------------------------------------------

    /** RGB → HSV. Input components in [0, 1]; output `(hue, saturation, value)`
      * with hue in [0, 1] (1.0 == 360°) and `value = max(R, G, B)`.
      *
      * The natural inverse for [[hsv2rgb]].
      */
    lazy val rgb2hsv: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_rgb2hsv"):
        """  let k = vec4<f32>(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    let p = mix(vec4<f32>(c.z, c.y, k.w, k.z), vec4<f32>(c.y, c.z, k.x, k.y), step(c.z, c.y));
    let q = mix(vec4<f32>(p.x, p.y, p.w, c.x), vec4<f32>(c.x, p.y, p.z, p.x), step(p.x, c.x));
    let d = q.x - min(q.w, q.y);
    let e = 1.0e-10;
    return vec3<f32>(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);"""

    /** RGB → HSL. Input components in [0, 1]; output `(hue, saturation,
      * lightness)` with hue in [0, 1] (1.0 == 360°) and `lightness = (max + min)
      * / 2`.
      *
      * The natural inverse for [[hsl2rgb]]. Saturation here uses the HSL formula
      * `chroma / (1 - |2L − 1|)` — different from HSV saturation.
      */
    lazy val rgb2hsl: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_rgb2hsl"):
        """  let k = vec4<f32>(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    let p = mix(vec4<f32>(c.z, c.y, k.w, k.z), vec4<f32>(c.y, c.z, k.x, k.y), step(c.z, c.y));
    let q = mix(vec4<f32>(p.x, p.y, p.w, c.x), vec4<f32>(c.x, p.y, p.z, p.x), step(p.x, c.x));
    let d = q.x - min(q.w, q.y);
    let l = q.x - d * 0.5;
    let e = 1.0e-10;
    let h = abs(q.z + (q.w - q.y) / (6.0 * d + e));
    let s = d / (1.0 - abs(2.0 * l - 1.0) + e);
    return vec3<f32>(h, s, l);"""

    // ---------------------------------------------------------------------------
    // Hue ramp — shared by the hsv and hsl conversions, assembled at build time
    // so every emitted fn stays self-contained. A ramp leaves the per-channel
    // shaped hue ramp in `rgb`; `fromHsv` / `fromHsl` apply the third channel.
    // ---------------------------------------------------------------------------

    private val hueRamp =
      """  let h = fract(c.x + vec3<f32>(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0;
    let t = clamp(abs(h - 3.0) - 1.0, vec3<f32>(0.0), vec3<f32>(1.0));"""

    private val linearRamp = s"$hueRamp\n    let rgb = t;"

    private val smoothRamp =
      s"$hueRamp\n    let rgb = t * t * (vec3<f32>(3.0) - 2.0 * t);"

    private val smootherRamp =
      s"$hueRamp\n    let rgb = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);"

    private def fromHsv(ramp: String): String =
      s"$ramp\n    return c.z * mix(vec3<f32>(1.0), rgb, c.y);"

    private def fromHsl(ramp: String): String =
      s"$ramp\n    return c.z + c.y * (rgb - 0.5) * (1.0 - abs(2.0 * c.z - 1.0));"

    // ---------------------------------------------------------------------------
    // HSV → RGB (Iñigo Quilez piecewise-linear, plus smoothed variants)
    // ---------------------------------------------------------------------------

    /** HSV → RGB (Iñigo Quilez piecewise-linear). Input `(hue, saturation,
      * value)`: saturation and value in [0, 1], hue any value (wrapped via
      * `fract`); output RGB in [0, 1]. Cheapest of the hsv→rgb variants —
      * visible color-band edges where the ramp changes slope.
      */
    lazy val hsv2rgb: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_hsv2rgb")(fromHsv(linearRamp))

    /** HSV → RGB with cubic smoothstep on the rgb ramp (`t·t·(3 − 2·t)`). Removes
      * the slope discontinuities of [[hsv2rgb]] for ~free.
      */
    lazy val hsv2rgbSmooth: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_hsv2rgb_smooth")(fromHsv(smoothRamp))

    /** HSV → RGB with quintic smootherstep (`t³·(t·(t·6 − 15) + 10)`). Smoother
      * than [[hsv2rgbSmooth]] at second-derivative-continuous cost.
      */
    lazy val hsv2rgbSmoother: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_hsv2rgb_smoother")(fromHsv(smootherRamp))

    // ---------------------------------------------------------------------------
    // HSL → RGB (same ramp and smoothed variants as HSV)
    // ---------------------------------------------------------------------------

    /** HSL → RGB. Input `(hue, saturation, lightness)`: saturation and
      * lightness in [0, 1], hue any value (wrapped via `fract`); output RGB in
      * [0, 1]. The natural inverse for [[rgb2hsl]].
      *
      * Lightness scales symmetrically: `L = 0` is black, `L = 1` is white,
      * `L = 0.5` is the fully-saturated hue. This differs from HSV `value`, where
      * the fully-saturated hue is at `V = 1`.
      */
    lazy val hsl2rgb: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_hsl2rgb")(fromHsl(linearRamp))

    /** HSL → RGB with cubic smoothstep on the rgb ramp, as [[hsv2rgbSmooth]]. */
    lazy val hsl2rgbSmooth: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_hsl2rgb_smooth")(fromHsl(smoothRamp))

    /** HSL → RGB with quintic smootherstep on the rgb ramp, as
      * [[hsv2rgbSmoother]].
      */
    lazy val hsl2rgbSmoother: WgslFn[(c: Vec3), Vec3] =
      WgslFn.raw("color_hsl2rgb_smoother")(fromHsl(smootherRamp))
