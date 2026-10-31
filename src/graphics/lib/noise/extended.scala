package trivalibs.graphics.lib.noise

// CPU extended noise: psrdnoise by Stefan Gustavson and Ian McEwan (MIT),
// https://github.com/stegu/psrdnoise — ported from the canonical GLSL
// src/psrdnoise2.glsl / psrdnoise3.glsl. The lattice setup, the period wrap
// and the contribution sums follow upstream statement by statement, with the
// upstream names (uv, i0, f0, o1, v0…, x0…, iu, iv, w, w2, w4, gdotx, dw, dn…).
// Like the GLSL (and unlike the GPU's WGSL variant split), one kernel per
// dimension branches on `period > 0` and `alpha != 0` at runtime; on the CPU
// those branches are predictable and nearly free.
//
// The hash and the gradients are table lookups (`NoiseTables.ext*`), the
// simplex-noise.js idiom already used for CPU simplex: upstream derives them
// per corner with mod289 divisions, trig and a sqrt, which the GPU does in
// hardware but which dominated the CPU cost. They depend on the integer hash
// only, so the field is the same; rotation costs one sin / cos of alpha per
// call via the angle-addition identity (2D) / upstream's `cos α·p + sin α·q`
// (3D).
//
// Other deviations: the GLSL `out gradient` becomes a caller-owned `out`
// vector (value in `.x`, gradient in the rest), the wrappers feed `alpha` in
// radians from `rot` in turns, and the seeded kernels add the two seed
// permute stages after the hash.
//
// Not ported (yet), available upstream if ever needed:
//   - src/psrddnoise2.glsl / psrddnoise3.glsl: also return the second
//     derivatives (Hessian).
//   - src/mpsrdnoise2.glsl: 2D variant safe for 16-bit (mediump) floats.

import scala.scalajs.js.typedarray.Float64Array
import scala.scalajs.js.typedarray.Int32Array
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.cpu.Vec4
import trivalibs.utils.js.*
import trivalibs.utils.numbers.NumExt.given

/** Extended noise (psrdnoise), 2D / 3D, on the CPU — same names, parameters,
  * defaults and ranges as the GPU `Extended`: value + analytic gradient,
  * optional `tilingPeriod` (integer components up to 289, zero = unwrapped;
  * in 2D the y period must be even, an odd one tiles at twice its value), `rot`
  * in turns and `seed`.
  *
  * Everything here is `inline` and erases to one direct [[kernel]] call. The
  * `…Value` members call value-only kernels, which skip the gradient and
  * allocate nothing.
  */
object Extended:

  inline val Tau = 6.283185307179586

  private inline def rad(inline rot: Double | Null): Double = inline rot match
    case null => 0.0
    case r: Double => r * Tau

  // ---- 2D ----

  /** Value + gradient at `pos`: `Vec3(value, dx, dy)`. */
  inline def noise2d(
      pos: Vec2,
      inline tilingPeriod: Vec2 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Vec3 =
    val out = new Vec3()
    inline tilingPeriod match
      case null =>
        inline seed match
          case null => kernel.noise2dInto(pos.x, pos.y, 0.0, 0.0, rad(rot), out)
          case s: Double =>
            kernel.noise2dSeededInto(pos.x, pos.y, 0.0, 0.0, rad(rot), s, out)
      case t: Vec2 =>
        inline seed match
          case null => kernel.noise2dInto(pos.x, pos.y, t.x, t.y, rad(rot), out)
          case s: Double =>
            kernel.noise2dSeededInto(pos.x, pos.y, t.x, t.y, rad(rot), s, out)

  /** Value only at `pos`. */
  inline def noiseValue2d(
      pos: Vec2,
      inline tilingPeriod: Vec2 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Double =
    inline tilingPeriod match
      case null =>
        inline seed match
          case null => kernel.noiseValue2d(pos.x, pos.y, 0.0, 0.0, rad(rot))
          case s: Double => kernel.noiseValue2dSeeded(pos.x, pos.y, 0.0, 0.0, rad(rot), s)
      case t: Vec2 =>
        inline seed match
          case null => kernel.noiseValue2d(pos.x, pos.y, t.x, t.y, rad(rot))
          case s: Double => kernel.noiseValue2dSeeded(pos.x, pos.y, t.x, t.y, rad(rot), s)

  /** Fractal Brownian motion, value + gradient: `Vec3(value, dx, dy)`. A tiling
    * fbm needs a whole-number `lacunarity`.
    */
  inline def fbm2d(
      pos: Vec2,
      octaves: Int = 4,
      lacunarity: Double = 2.0,
      gain: Double = 0.5,
      inline tilingPeriod: Vec2 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Vec3 =
    val out = new Vec3()
    inline tilingPeriod match
      case null =>
        kernel.fbm2dInto(pos.x, pos.y, octaves, lacunarity, gain, 0.0, 0.0, rad(rot), seedArg(seed), out)
      case t: Vec2 =>
        kernel.fbm2dInto(pos.x, pos.y, octaves, lacunarity, gain, t.x, t.y, rad(rot), seedArg(seed), out)

  /** [[fbm2d]], value only. */
  inline def fbmValue2d(
      pos: Vec2,
      octaves: Int = 4,
      lacunarity: Double = 2.0,
      gain: Double = 0.5,
      inline tilingPeriod: Vec2 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Double =
    inline tilingPeriod match
      case null =>
        kernel.fbmValue2d(pos.x, pos.y, octaves, lacunarity, gain, 0.0, 0.0, rad(rot), seedArg(seed))
      case t: Vec2 =>
        kernel.fbmValue2d(pos.x, pos.y, octaves, lacunarity, gain, t.x, t.y, rad(rot), seedArg(seed))

  // ---- 3D ----

  /** Value + gradient at `pos`: `Vec4(value, dx, dy, dz)`. */
  inline def noise3d(
      pos: Vec3,
      inline tilingPeriod: Vec3 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Vec4 =
    val out = new Vec4()
    inline tilingPeriod match
      case null =>
        inline seed match
          case null => kernel.noise3dInto(pos.x, pos.y, pos.z, 0.0, 0.0, 0.0, rad(rot), out)
          case s: Double =>
            kernel.noise3dSeededInto(pos.x, pos.y, pos.z, 0.0, 0.0, 0.0, rad(rot), s, out)
      case t: Vec3 =>
        inline seed match
          case null => kernel.noise3dInto(pos.x, pos.y, pos.z, t.x, t.y, t.z, rad(rot), out)
          case s: Double =>
            kernel.noise3dSeededInto(pos.x, pos.y, pos.z, t.x, t.y, t.z, rad(rot), s, out)

  /** Value only at `pos`. */
  inline def noiseValue3d(
      pos: Vec3,
      inline tilingPeriod: Vec3 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Double =
    inline tilingPeriod match
      case null =>
        inline seed match
          case null => kernel.noiseValue3d(pos.x, pos.y, pos.z, 0.0, 0.0, 0.0, rad(rot))
          case s: Double =>
            kernel.noiseValue3dSeeded(pos.x, pos.y, pos.z, 0.0, 0.0, 0.0, rad(rot), s)
      case t: Vec3 =>
        inline seed match
          case null => kernel.noiseValue3d(pos.x, pos.y, pos.z, t.x, t.y, t.z, rad(rot))
          case s: Double =>
            kernel.noiseValue3dSeeded(pos.x, pos.y, pos.z, t.x, t.y, t.z, rad(rot), s)

  /** 3D [[fbm2d]]: `Vec4(value, dx, dy, dz)`. */
  inline def fbm3d(
      pos: Vec3,
      octaves: Int = 4,
      lacunarity: Double = 2.0,
      gain: Double = 0.5,
      inline tilingPeriod: Vec3 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Vec4 =
    val out = new Vec4()
    inline tilingPeriod match
      case null =>
        kernel.fbm3dInto(pos.x, pos.y, pos.z, octaves, lacunarity, gain, 0.0, 0.0, 0.0, rad(rot), seedArg(seed), out)
      case t: Vec3 =>
        kernel.fbm3dInto(pos.x, pos.y, pos.z, octaves, lacunarity, gain, t.x, t.y, t.z, rad(rot), seedArg(seed), out)

  /** 3D [[fbm2d]], value only. */
  inline def fbmValue3d(
      pos: Vec3,
      octaves: Int = 4,
      lacunarity: Double = 2.0,
      gain: Double = 0.5,
      inline tilingPeriod: Vec3 | Null = null,
      inline rot: Double | Null = null,
      inline seed: Double | Null = null,
  ): Double =
    inline tilingPeriod match
      case null =>
        kernel.fbmValue3d(pos.x, pos.y, pos.z, octaves, lacunarity, gain, 0.0, 0.0, 0.0, rad(rot), seedArg(seed))
      case t: Vec3 =>
        kernel.fbmValue3d(pos.x, pos.y, pos.z, octaves, lacunarity, gain, t.x, t.y, t.z, rad(rot), seedArg(seed))

  /** fbm kernels take the seed as a packed offset (`-1` = unseeded), computed
    * once before the octave loop; a `null` seed folds to the constant `-1`.
    */
  private inline def seedArg(inline seed: Double | Null): Int = inline seed match
    case null => -1
    case s: Double => NoiseTables.seedOffsets(s)

  /** Scalar kernels. Gradient kernels write `(value, gradient…)` into `out`
    * and return it; value kernels return the value.
    */
  object kernel:

    // The lattice setup and the period wrap follow upstream's GLSL statement by
    // statement. The hash and the gradients are table lookups instead of
    // upstream's per-corner arithmetic (`NoiseTables.ext*`): they depend on
    // the integer hash only, and on the CPU the trig / sqrt / mod289 per
    // corner dominated the cost (the GPU has hardware trig). Same field, and
    // rotation needs one sin / cos of alpha per call.

    /** GLSL `mod(x, y)`. */
    private inline def mod(x: Double, y: Double): Double = x - y * (x / y).floor

    /** Integer `mod 289` of an (integer-valued) lattice coordinate. */
    private inline def lat(i: Double): Int = NoiseTables.mod289(i)

    /** Our seed stages (not upstream): two more permutes of the hash. */
    private inline def seeded(P: Int32Array, h: Int, so: Int): Int =
      P(P(h + so % 289) + so / 289)

    // ---- 2D ----

    /** One corner: its gradient (from the hash `h`) and contribution to `n`;
      * the gradient part accumulates into `out` when `gradient`. `ca` / `sa`
      * are cos / sin of the rotation.
      */
    private inline def corner2(
        h: Int,
        x: Double,
        y: Double,
        ca: Double,
        sa: Double,
        cosT: Float64Array,
        sinT: Float64Array,
        inline gradient: Boolean,
        out: Opt[Vec3],
    ): Double =
      val c = cosT(h)
      val s = sinT(h)
      val gx = c * ca - s * sa
      val gy = s * ca + c * sa
      val w = Math.max(0.8 - (x * x + y * y), 0.0)
      val w2 = w * w
      val w4 = w2 * w2
      val gdotx = gx * x + gy * y
      inline if gradient then
        val w3 = w2 * w
        val dw = -8.0 * w3 * gdotx
        out.get.y += w4 * gx + dw * x
        out.get.z += w4 * gy + dw * y
      w4 * gdotx

    private inline def impl2(
        x: Double,
        y: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        so: Int,
        inline isSeeded: Boolean,
        inline gradient: Boolean,
        out: Opt[Vec3],
    ): Double =
      val uvx = x + y * 0.5
      val uvy = y
      val i0x = uvx.floor
      val i0y = uvy.floor
      val f0x = uvx - i0x
      val f0y = uvy - i0y
      val cmp = if f0x >= f0y then 1.0 else 0.0
      val o1x = cmp
      val o1y = 1.0 - cmp
      val i1x = i0x + o1x
      val i1y = i0y + o1y
      val i2x = i0x + 1.0
      val i2y = i0y + 1.0
      val v0x = i0x - i0y * 0.5
      val v0y = i0y
      val v1x = v0x + o1x - o1y * 0.5
      val v1y = v0y + o1y
      val v2x = v0x + 0.5
      val v2y = v0y + 1.0
      val x0x = x - v0x
      val x0y = y - v0y
      val x1x = x - v1x
      val x1y = y - v1y
      val x2x = x - v2x
      val x2y = y - v2y
      var iu0 = i0x
      var iu1 = i1x
      var iu2 = i2x
      var iv0 = i0y
      var iv1 = i1y
      var iv2 = i2y
      if periodX > 0.0 || periodY > 0.0 then
        var xw0 = v0x
        var xw1 = v1x
        var xw2 = v2x
        var yw0 = v0y
        var yw1 = v1y
        var yw2 = v2y
        if periodX > 0.0 then
          xw0 = mod(v0x, periodX)
          xw1 = mod(v1x, periodX)
          xw2 = mod(v2x, periodX)
        if periodY > 0.0 then
          yw0 = mod(v0y, periodY)
          yw1 = mod(v1y, periodY)
          yw2 = mod(v2y, periodY)
        iu0 = (xw0 + 0.5 * yw0 + 0.5).floor
        iu1 = (xw1 + 0.5 * yw1 + 0.5).floor
        iu2 = (xw2 + 0.5 * yw2 + 0.5).floor
        iv0 = (yw0 + 0.5).floor
        iv1 = (yw1 + 0.5).floor
        iv2 = (yw2 + 0.5).floor
      // hash = mod(iu, 289); hash = mod((hash*51 + 2)*hash + iv, 289);
      // hash = mod((hash*34 + 10)*hash, 289) — as table lookups.
      val P = NoiseTables.extPerm
      val mix = NoiseTables.ext2Mix
      var h0 = P(mix(lat(iu0)) + lat(iv0))
      var h1 = P(mix(lat(iu1)) + lat(iv1))
      var h2 = P(mix(lat(iu2)) + lat(iv2))
      inline if isSeeded then
        h0 = seeded(P, h0, so)
        h1 = seeded(P, h1, so)
        h2 = seeded(P, h2, so)
      val rotating = alpha != 0.0
      val ca = if rotating then alpha.cos else 1.0
      val sa = if rotating then alpha.sin else 0.0
      val cosT = NoiseTables.ext2Cos
      val sinT = NoiseTables.ext2Sin
      inline if gradient then
        out.get.y = 0.0
        out.get.z = 0.0
      val n = corner2(h0, x0x, x0y, ca, sa, cosT, sinT, gradient, out) +
        corner2(h1, x1x, x1y, ca, sa, cosT, sinT, gradient, out) +
        corner2(h2, x2x, x2y, ca, sa, cosT, sinT, gradient, out)
      inline if gradient then
        out.get.x = 10.9 * n
        out.get.y *= 10.9
        out.get.z *= 10.9
      10.9 * n

    def noiseValue2d(x: Double, y: Double, periodX: Double, periodY: Double, alpha: Double): Double =
      impl2(x, y, periodX, periodY, alpha, 0, false, false, null)

    private def noiseValue2dSo(
        x: Double,
        y: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        so: Int,
    ): Double = impl2(x, y, periodX, periodY, alpha, so, true, false, null)

    def noiseValue2dSeeded(
        x: Double,
        y: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        seed: Double,
    ): Double = noiseValue2dSo(x, y, periodX, periodY, alpha, NoiseTables.seedOffsets(seed))

    def noise2dInto(
        x: Double,
        y: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        out: Vec3,
    ): Vec3 =
      impl2(x, y, periodX, periodY, alpha, 0, false, true, out)
      out

    private def noise2dSoInto(
        x: Double,
        y: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        so: Int,
        out: Vec3,
    ): Vec3 =
      impl2(x, y, periodX, periodY, alpha, so, true, true, out)
      out

    def noise2dSeededInto(
        x: Double,
        y: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        seed: Double,
        out: Vec3,
    ): Vec3 = noise2dSoInto(x, y, periodX, periodY, alpha, NoiseTables.seedOffsets(seed), out)

    // ---- 3D ----

    private inline def corner3(
        h: Int,
        x: Double,
        y: Double,
        z: Double,
        rotating: Boolean,
        ca: Double,
        sa: Double,
        tpx: Float64Array,
        tpy: Float64Array,
        tpz: Float64Array,
        tqx: Float64Array,
        tqy: Float64Array,
        tqz: Float64Array,
        inline gradient: Boolean,
        out: Opt[Vec4],
    ): Double =
      var gx = tpx(h)
      var gy = tpy(h)
      var gz = tpz(h)
      if rotating then
        gx = ca * gx + sa * tqx(h)
        gy = ca * gy + sa * tqy(h)
        gz = ca * gz + sa * tqz(h)
      val w = Math.max(0.5 - (x * x + y * y + z * z), 0.0)
      val w2 = w * w
      val w3 = w2 * w
      val gdotx = gx * x + gy * y + gz * z
      inline if gradient then
        val dw = -6.0 * w2 * gdotx
        out.get.y += w3 * gx + dw * x
        out.get.z += w3 * gy + dw * y
        out.get.w += w3 * gz + dw * z
      w3 * gdotx

    private inline def impl3(
        x: Double,
        y: Double,
        z: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        so: Int,
        inline isSeeded: Boolean,
        inline gradient: Boolean,
        out: Opt[Vec4],
    ): Double =
      // uvw = M * x, M = mat3(0,1,1, 1,0,1, 1,1,0)
      val uvwX = y + z
      val uvwY = x + z
      val uvwZ = x + y
      var i0x = uvwX.floor
      var i0y = uvwY.floor
      var i0z = uvwZ.floor
      val f0x = uvwX - i0x
      val f0y = uvwY - i0y
      val f0z = uvwZ - i0z
      // g_ = step(f0.xyx, f0.yzz), l_ = 1 - g_,
      // g = (l_.z, g_.xy), l = (l_.xy, g_.z)
      val g_x = if f0y >= f0x then 1.0 else 0.0
      val g_y = if f0z >= f0y then 1.0 else 0.0
      val g_z = if f0z >= f0x then 1.0 else 0.0
      val gx = 1.0 - g_z
      val gy = g_x
      val gz = g_y
      val lx = 1.0 - g_x
      val ly = 1.0 - g_y
      val lz = g_z
      val o1x = Math.min(gx, lx)
      val o1y = Math.min(gy, ly)
      val o1z = Math.min(gz, lz)
      val o2x = Math.max(gx, lx)
      val o2y = Math.max(gy, ly)
      val o2z = Math.max(gz, lz)
      var i1x = i0x + o1x
      var i1y = i0y + o1y
      var i1z = i0z + o1z
      var i2x = i0x + o2x
      var i2y = i0y + o2y
      var i2z = i0z + o2z
      var i3x = i0x + 1.0
      var i3y = i0y + 1.0
      var i3z = i0z + 1.0
      // v = Mi * i, Mi = mat3(-0.5,0.5,0.5, 0.5,-0.5,0.5, 0.5,0.5,-0.5)
      val v0x = 0.5 * (-i0x + i0y + i0z)
      val v0y = 0.5 * (i0x - i0y + i0z)
      val v0z = 0.5 * (i0x + i0y - i0z)
      val v1x = 0.5 * (-i1x + i1y + i1z)
      val v1y = 0.5 * (i1x - i1y + i1z)
      val v1z = 0.5 * (i1x + i1y - i1z)
      val v2x = 0.5 * (-i2x + i2y + i2z)
      val v2y = 0.5 * (i2x - i2y + i2z)
      val v2z = 0.5 * (i2x + i2y - i2z)
      val v3x = 0.5 * (-i3x + i3y + i3z)
      val v3y = 0.5 * (i3x - i3y + i3z)
      val v3z = 0.5 * (i3x + i3y - i3z)
      val x0x = x - v0x
      val x0y = y - v0y
      val x0z = z - v0z
      val x1x = x - v1x
      val x1y = y - v1y
      val x1z = z - v1z
      val x2x = x - v2x
      val x2y = y - v2y
      val x2z = z - v2z
      val x3x = x - v3x
      val x3y = y - v3y
      val x3z = z - v3z
      if periodX > 0.0 || periodY > 0.0 || periodZ > 0.0 then
        var vx0 = v0x
        var vx1 = v1x
        var vx2 = v2x
        var vx3 = v3x
        var vy0 = v0y
        var vy1 = v1y
        var vy2 = v2y
        var vy3 = v3y
        var vz0 = v0z
        var vz1 = v1z
        var vz2 = v2z
        var vz3 = v3z
        if periodX > 0.0 then
          vx0 = mod(vx0, periodX)
          vx1 = mod(vx1, periodX)
          vx2 = mod(vx2, periodX)
          vx3 = mod(vx3, periodX)
        if periodY > 0.0 then
          vy0 = mod(vy0, periodY)
          vy1 = mod(vy1, periodY)
          vy2 = mod(vy2, periodY)
          vy3 = mod(vy3, periodY)
        if periodZ > 0.0 then
          vz0 = mod(vz0, periodZ)
          vz1 = mod(vz1, periodZ)
          vz2 = mod(vz2, periodZ)
          vz3 = mod(vz3, periodZ)
        // i = floor(M * v + 0.5)
        i0x = (vy0 + vz0 + 0.5).floor
        i0y = (vx0 + vz0 + 0.5).floor
        i0z = (vx0 + vy0 + 0.5).floor
        i1x = (vy1 + vz1 + 0.5).floor
        i1y = (vx1 + vz1 + 0.5).floor
        i1z = (vx1 + vy1 + 0.5).floor
        i2x = (vy2 + vz2 + 0.5).floor
        i2y = (vx2 + vz2 + 0.5).floor
        i2z = (vx2 + vy2 + 0.5).floor
        i3x = (vy3 + vz3 + 0.5).floor
        i3y = (vx3 + vz3 + 0.5).floor
        i3z = (vx3 + vy3 + 0.5).floor
      // hash = permute(permute(permute(i.z) + i.y) + i.x), as table lookups.
      val P = NoiseTables.extPerm
      var h0 = P(P(P(lat(i0z)) + lat(i0y)) + lat(i0x))
      var h1 = P(P(P(lat(i1z)) + lat(i1y)) + lat(i1x))
      var h2 = P(P(P(lat(i2z)) + lat(i2y)) + lat(i2x))
      var h3 = P(P(P(lat(i3z)) + lat(i3y)) + lat(i3x))
      inline if isSeeded then
        h0 = seeded(P, h0, so)
        h1 = seeded(P, h1, so)
        h2 = seeded(P, h2, so)
        h3 = seeded(P, h3, so)
      val rotating = alpha != 0.0
      val ca = if rotating then alpha.cos else 1.0
      val sa = if rotating then alpha.sin else 0.0
      val tpx = NoiseTables.ext3Px
      val tpy = NoiseTables.ext3Py
      val tpz = NoiseTables.ext3Pz
      val tqx = NoiseTables.ext3Qx
      val tqy = NoiseTables.ext3Qy
      val tqz = NoiseTables.ext3Qz
      inline if gradient then
        out.get.y = 0.0
        out.get.z = 0.0
        out.get.w = 0.0
      val n =
        corner3(h0, x0x, x0y, x0z, rotating, ca, sa, tpx, tpy, tpz, tqx, tqy, tqz, gradient, out) +
          corner3(h1, x1x, x1y, x1z, rotating, ca, sa, tpx, tpy, tpz, tqx, tqy, tqz, gradient, out) +
          corner3(h2, x2x, x2y, x2z, rotating, ca, sa, tpx, tpy, tpz, tqx, tqy, tqz, gradient, out) +
          corner3(h3, x3x, x3y, x3z, rotating, ca, sa, tpx, tpy, tpz, tqx, tqy, tqz, gradient, out)
      inline if gradient then
        out.get.x = 39.5 * n
        out.get.y *= 39.5
        out.get.z *= 39.5
        out.get.w *= 39.5
      39.5 * n

    def noiseValue3d(
        x: Double,
        y: Double,
        z: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
    ): Double = impl3(x, y, z, periodX, periodY, periodZ, alpha, 0, false, false, null)

    private def noiseValue3dSo(
        x: Double,
        y: Double,
        z: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        so: Int,
    ): Double = impl3(x, y, z, periodX, periodY, periodZ, alpha, so, true, false, null)

    def noiseValue3dSeeded(
        x: Double,
        y: Double,
        z: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        seed: Double,
    ): Double =
      noiseValue3dSo(x, y, z, periodX, periodY, periodZ, alpha, NoiseTables.seedOffsets(seed))

    def noise3dInto(
        x: Double,
        y: Double,
        z: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        out: Vec4,
    ): Vec4 =
      impl3(x, y, z, periodX, periodY, periodZ, alpha, 0, false, true, out)
      out

    private def noise3dSoInto(
        x: Double,
        y: Double,
        z: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        so: Int,
        out: Vec4,
    ): Vec4 =
      impl3(x, y, z, periodX, periodY, periodZ, alpha, so, true, true, out)
      out

    def noise3dSeededInto(
        x: Double,
        y: Double,
        z: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        seed: Double,
        out: Vec4,
    ): Vec4 =
      noise3dSoInto(x, y, z, periodX, periodY, periodZ, alpha, NoiseTables.seedOffsets(seed), out)

    // ---- fbm (ours) ----
    // `so` is a packed seed offset from `NoiseTables.seedOffsets`, or -1 for
    // unseeded; octave `i` shifts the first offset by `i`.

    private inline def octaveSo(so: Int, i: Int): Int =
      (so % 289 + i) % 289 + 289 * (so / 289)

    def fbmValue2d(
        x: Double,
        y: Double,
        octaves: Int,
        lacunarity: Double,
        gain: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        so: Int,
    ): Double =
      var sum = 0.0
      var amplitude = 1.0
      var total = 0.0
      var frequency = 1.0
      var i = 0
      while i < octaves do
        val n =
          if so < 0 then
            noiseValue2d(x * frequency, y * frequency, periodX * frequency, periodY * frequency, alpha)
          else
            noiseValue2dSo(
              x * frequency,
              y * frequency,
              periodX * frequency,
              periodY * frequency,
              alpha,
              octaveSo(so, i),
            )
        sum += n * amplitude
        total += amplitude
        amplitude *= gain
        frequency *= lacunarity
        i += 1
      sum / total

    def fbm2dInto(
        x: Double,
        y: Double,
        octaves: Int,
        lacunarity: Double,
        gain: Double,
        periodX: Double,
        periodY: Double,
        alpha: Double,
        so: Int,
        out: Vec3,
    ): Vec3 =
      var sum = 0.0
      var dx = 0.0
      var dy = 0.0
      var amplitude = 1.0
      var total = 0.0
      var frequency = 1.0
      var i = 0
      while i < octaves do
        if so < 0 then
          noise2dInto(x * frequency, y * frequency, periodX * frequency, periodY * frequency, alpha, out)
        else
          noise2dSoInto(
            x * frequency,
            y * frequency,
            periodX * frequency,
            periodY * frequency,
            alpha,
            octaveSo(so, i),
            out,
          )
        sum += out.x * amplitude
        dx += out.y * frequency * amplitude
        dy += out.z * frequency * amplitude
        total += amplitude
        amplitude *= gain
        frequency *= lacunarity
        i += 1
      out.x = sum / total
      out.y = dx / total
      out.z = dy / total
      out

    def fbmValue3d(
        x: Double,
        y: Double,
        z: Double,
        octaves: Int,
        lacunarity: Double,
        gain: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        so: Int,
    ): Double =
      var sum = 0.0
      var amplitude = 1.0
      var total = 0.0
      var frequency = 1.0
      var i = 0
      while i < octaves do
        val n =
          if so < 0 then
            noiseValue3d(
              x * frequency,
              y * frequency,
              z * frequency,
              periodX * frequency,
              periodY * frequency,
              periodZ * frequency,
              alpha,
            )
          else
            noiseValue3dSo(
              x * frequency,
              y * frequency,
              z * frequency,
              periodX * frequency,
              periodY * frequency,
              periodZ * frequency,
              alpha,
              octaveSo(so, i),
            )
        sum += n * amplitude
        total += amplitude
        amplitude *= gain
        frequency *= lacunarity
        i += 1
      sum / total

    def fbm3dInto(
        x: Double,
        y: Double,
        z: Double,
        octaves: Int,
        lacunarity: Double,
        gain: Double,
        periodX: Double,
        periodY: Double,
        periodZ: Double,
        alpha: Double,
        so: Int,
        out: Vec4,
    ): Vec4 =
      var sum = 0.0
      var dx = 0.0
      var dy = 0.0
      var dz = 0.0
      var amplitude = 1.0
      var total = 0.0
      var frequency = 1.0
      var i = 0
      while i < octaves do
        if so < 0 then
          noise3dInto(
            x * frequency,
            y * frequency,
            z * frequency,
            periodX * frequency,
            periodY * frequency,
            periodZ * frequency,
            alpha,
            out,
          )
        else
          noise3dSoInto(
            x * frequency,
            y * frequency,
            z * frequency,
            periodX * frequency,
            periodY * frequency,
            periodZ * frequency,
            alpha,
            octaveSo(so, i),
            out,
          )
        sum += out.x * amplitude
        dx += out.y * frequency * amplitude
        dy += out.z * frequency * amplitude
        dz += out.w * frequency * amplitude
        total += amplitude
        amplitude *= gain
        frequency *= lacunarity
        i += 1
      out.x = sum / total
      out.y = dx / total
      out.z = dy / total
      out.w = dz / total
      out
