package trivalibs.graphics.math

import munit.FunSuite
import trivalibs.graphics.lib.color.*
import trivalibs.graphics.lib.coords.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.utils.numbers.NumExt.given

// CPU color-space and coordinate conversions — the CPU branch of the shared
// `graphics.lib` extensions, mirroring `shader.lib.color` / `shader.lib.coords`.
class CpuColorCoordsTest extends FunSuite:

  private val eps = 1e-9

  test("hsv2rgb — primaries and greys"):
    val red = Vec3(0.0, 1.0, 1.0).hsv2rgb
    assertEqualsDouble(red.x, 1.0, eps)
    assertEqualsDouble(red.y, 0.0, eps)
    assertEqualsDouble(red.z, 0.0, eps)

    val green = Vec3(1.0 / 3.0, 1.0, 1.0).hsv2rgb
    assertEqualsDouble(green.x, 0.0, eps)
    assertEqualsDouble(green.y, 1.0, eps)
    assertEqualsDouble(green.z, 0.0, eps)

    val blue = Vec3(2.0 / 3.0, 1.0, 1.0).hsv2rgb
    assertEqualsDouble(blue.x, 0.0, eps)
    assertEqualsDouble(blue.y, 0.0, eps)
    assertEqualsDouble(blue.z, 1.0, eps)

    val white = Vec3(0.0, 0.0, 1.0).hsv2rgb
    assertEqualsDouble(white.x, 1.0, eps)
    assertEqualsDouble(white.y, 1.0, eps)
    assertEqualsDouble(white.z, 1.0, eps)

    val black = Vec3(0.5, 0.7, 0.0).hsv2rgb
    assertEqualsDouble(black.x, 0.0, eps)
    assertEqualsDouble(black.z, 0.0, eps)

  test("hue wraps at 1.0"):
    val a = Vec3(0.0, 1.0, 1.0).hsv2rgb
    val b = Vec3(1.0, 1.0, 1.0).hsv2rgb
    assertEqualsDouble(b.x, a.x, eps)
    assertEqualsDouble(b.y, a.y, eps)
    assertEqualsDouble(b.z, a.z, eps)

  test("negative hue wraps like positive"):
    for h <- Seq(0.1, 0.3, 0.5, 0.7, 0.9) do
      val pos = Vec3(h, 0.8, 0.9)
      val neg = Vec3(h - 1.0, 0.8, 0.9)
      val negTwice = Vec3(h - 2.0, 0.8, 0.9)
      for (a, b) <- Seq(
          (pos.hsv2rgb, neg.hsv2rgb),
          (pos.hsv2rgb, negTwice.hsv2rgb),
          (pos.hsv2rgbSmooth, neg.hsv2rgbSmooth),
          (pos.hsl2rgb, neg.hsl2rgb),
        )
      do
        assertEqualsDouble(b.x, a.x, 1e-9)
        assertEqualsDouble(b.y, a.y, 1e-9)
        assertEqualsDouble(b.z, a.z, 1e-9)

  test("rgb2hsv inverts hsv2rgb"):
    val samples = Seq(
      Vec3(0.0, 1.0, 1.0),
      Vec3(0.15, 0.6, 0.9),
      Vec3(0.4, 0.25, 0.5),
      Vec3(0.72, 1.0, 0.33),
      Vec3(0.95, 0.8, 1.0),
    )
    for hsv <- samples do
      val back = hsv.hsv2rgb.rgb2hsv
      assertEqualsDouble(back.x, hsv.x, 1e-6)
      assertEqualsDouble(back.y, hsv.y, 1e-6)
      assertEqualsDouble(back.z, hsv.z, 1e-6)

  test("rgb2hsl inverts hsl2rgb"):
    val samples = Seq(
      Vec3(0.0, 1.0, 0.5),
      Vec3(0.15, 0.6, 0.4),
      Vec3(0.62, 0.9, 0.7),
      Vec3(0.88, 0.35, 0.25),
    )
    for hsl <- samples do
      val back = hsl.hsl2rgb.rgb2hsl
      assertEqualsDouble(back.x, hsl.x, 1e-6)
      assertEqualsDouble(back.y, hsl.y, 1e-6)
      assertEqualsDouble(back.z, hsl.z, 1e-6)

  test("hsl lightness — 0 is black, 1 is white, 0.5 is the pure hue"):
    val black = Vec3(0.3, 1.0, 0.0).hsl2rgb
    assertEqualsDouble(black.x, 0.0, eps)
    assertEqualsDouble(black.y, 0.0, eps)
    assertEqualsDouble(black.z, 0.0, eps)
    val white = Vec3(0.3, 1.0, 1.0).hsl2rgb
    assertEqualsDouble(white.x, 1.0, eps)
    assertEqualsDouble(white.y, 1.0, eps)
    assertEqualsDouble(white.z, 1.0, eps)
    val pure = Vec3(0.0, 1.0, 0.5).hsl2rgb
    assertEqualsDouble(pure.x, 1.0, eps)
    assertEqualsDouble(pure.y, 0.0, eps)
    assertEqualsDouble(pure.z, 0.0, eps)

  test("smoothed hsv variants agree at ramp endpoints"):
    // t = 0 and t = 1 are fixed points of every smoothing curve, so a fully
    // saturated primary is identical across all four variants.
    val hsv = Vec3(0.0, 1.0, 1.0)
    for c <- Seq(hsv.hsv2rgb, hsv.hsv2rgbSmooth, hsv.hsv2rgbSmoother) do
      assertEqualsDouble(c.x, 1.0, 1e-9)
      assertEqualsDouble(c.y, 0.0, 1e-9)
      assertEqualsDouble(c.z, 0.0, 1e-9)

  test("smoothed hsl variants keep the hsl anchors"):
    for (lightness, expected) <- Seq(
        (0.0, Vec3(0.0, 0.0, 0.0)),
        (1.0, Vec3(1.0, 1.0, 1.0)),
        (0.5, Vec3(1.0, 0.0, 0.0)),
      )
    do
      val hsl = Vec3(0.0, 1.0, lightness)
      for c <- Seq(hsl.hsl2rgb, hsl.hsl2rgbSmooth, hsl.hsl2rgbSmoother) do
        assertEqualsDouble(c.x, expected.x, 1e-9)
        assertEqualsDouble(c.y, expected.y, 1e-9)
        assertEqualsDouble(c.z, expected.z, 1e-9)

  test("smoothed hsl variants shape the ramp like the hsv ones"):
    // mid-ramp hue: linear ramp 0.5, smoothstep 0.5, but at a quarter the
    // shaped ramps diverge — compare each hsl variant against its hsv twin
    val h = 1.0 / 24.0
    val pairs = Seq(
      (Vec3(h, 1.0, 0.5).hsl2rgb, Vec3(h, 1.0, 1.0).hsv2rgb),
      (Vec3(h, 1.0, 0.5).hsl2rgbSmooth, Vec3(h, 1.0, 1.0).hsv2rgbSmooth),
      (Vec3(h, 1.0, 0.5).hsl2rgbSmoother, Vec3(h, 1.0, 1.0).hsv2rgbSmoother),
    )
    for (hsl, hsv) <- pairs do
      assertEqualsDouble(hsl.x, hsv.x, 1e-9)
      assertEqualsDouble(hsl.y, hsv.y, 1e-9)
      assertEqualsDouble(hsl.z, hsv.z, 1e-9)
    assert((pairs(0)._1.y - pairs(1)._1.y).abs > 0.01)

  test("smoothed hsl variants wrap negative hues"):
    for h <- Seq(0.1, 0.45, 0.8) do
      val pos = Vec3(h, 0.7, 0.4)
      val neg = Vec3(h - 1.0, 0.7, 0.4)
      for (a, b) <- Seq(
          (pos.hsl2rgbSmooth, neg.hsl2rgbSmooth),
          (pos.hsl2rgbSmoother, neg.hsl2rgbSmoother),
        )
      do
        assertEqualsDouble(b.x, a.x, 1e-9)
        assertEqualsDouble(b.y, a.y, 1e-9)
        assertEqualsDouble(b.z, a.z, 1e-9)

  test("kernel Into forms write into a caller-owned vector"):
    val out = new Vec3()
    val rgb = Color.kernel.hsv2rgbInto(0.0, 1.0, 1.0, out)
    assert(rgb eq out)
    assertEqualsDouble(out.x, 1.0, eps)
    assertEqualsDouble(out.y, 0.0, eps)

  test("polarToCart / cartToPolar round-trip"):
    val cart = Vec2(3.0, 4.0)
    val polar = cart.cartToPolar
    assertEqualsDouble(polar.x, 5.0, eps)
    assertEqualsDouble(polar.y, 4.0.atan2(3.0), eps)
    val back = polar.polarToCart
    assertEqualsDouble(back.x, cart.x, 1e-12)
    assertEqualsDouble(back.y, cart.y, 1e-12)

  test("polarToCart — unit circle at 0 and π/2"):
    val at0 = Vec2(1.0, 0.0).polarToCart
    assertEqualsDouble(at0.x, 1.0, eps)
    assertEqualsDouble(at0.y, 0.0, eps)
    val at90 = Vec2(2.0, Math.PI / 2.0).polarToCart
    assertEqualsDouble(at90.x, 0.0, 1e-15)
    assertEqualsDouble(at90.y, 2.0, eps)
