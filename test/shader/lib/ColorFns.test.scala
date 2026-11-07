package trivalibs.graphics.shader.lib.color

import munit.FunSuite
import trivalibs.graphics.shader.dsl.WgslFnData

class ColorFnsTest extends FunSuite:

  test("rgb2hsv returns max(R,G,B) as third channel"):
    val data = Color.wgsl.rgb2hsv.asInstanceOf[WgslFnData]
    assert(data.src.contains("fn color_rgb2hsv(c: vec3<f32>)"), data.src)
    assert(data.src.contains("-> vec3<f32>"), data.src)
    assert(data.src.contains("step(c.z, c.y)"), data.src)
    assert(data.src.contains("step(p.x, c.x)"), data.src)
    assert(data.src.contains("d / (q.x + e)"), data.src)
    // V is q.x (max channel)
    assert(data.src.contains(", q.x);"), data.src)

  test("rgb2hsl returns (max+min)/2 as third channel"):
    val data = Color.wgsl.rgb2hsl.asInstanceOf[WgslFnData]
    assert(data.src.contains("fn color_rgb2hsl(c: vec3<f32>)"), data.src)
    assert(data.src.contains("let l = q.x - d * 0.5"), data.src)
    // HSL saturation formula uses 1 - |2L - 1|
    assert(data.src.contains("1.0 - abs(2.0 * l - 1.0)"), data.src)

  test("hsv2rgb emits piecewise-linear ramp"):
    val data = Color.wgsl.hsv2rgb.asInstanceOf[WgslFnData]
    assert(data.src.contains("fn color_hsv2rgb(c: vec3<f32>)"), data.src)
    assert(
      data.src.contains("fract(c.x + vec3<f32>(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0"),
      data.src,
    )
    assert(!data.src.contains("% 6.0"), data.src)
    assert(data.src.contains("clamp("), data.src)
    assert(data.src.contains("c.z * mix(vec3<f32>(1.0), rgb, c.y)"), data.src)

  test("hsv2rgbSmooth applies cubic smoothstep"):
    val data = Color.wgsl.hsv2rgbSmooth.asInstanceOf[WgslFnData]
    assert(data.src.contains("fn color_hsv2rgb_smooth(c: vec3<f32>)"), data.src)
    assert(data.src.contains("t * t * (vec3<f32>(3.0) - 2.0 * t)"), data.src)

  test("hsv2rgbSmoother applies quintic smootherstep"):
    val data = Color.wgsl.hsv2rgbSmoother.asInstanceOf[WgslFnData]
    assert(data.src.contains("fn color_hsv2rgb_smoother(c: vec3<f32>)"), data.src)
    assert(data.src.contains("t * (t * 6.0 - 15.0) + 10.0"), data.src)

  test("hsl2rgb scales chroma by 1 - |2L - 1|"):
    val data = Color.wgsl.hsl2rgb.asInstanceOf[WgslFnData]
    assert(data.src.contains("fn color_hsl2rgb(c: vec3<f32>)"), data.src)
    assert(
      data.src.contains(
        "c.z + c.y * (rgb - 0.5) * (1.0 - abs(2.0 * c.z - 1.0))",
      ),
      data.src,
    )
