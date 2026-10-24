package trivalibs.graphics.painter

import munit.FunSuite
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.*

/** GPU integration tests: render offline through node-webgpu and assert on the
  * read-back pixels. `webgpu` is a dev dependency, loaded with a dynamic
  * import; without it, or without an adapter, the tests are skipped.
  *
  * `TRIVALIBS_WEBGPU_ADAPTER` / `TRIVALIBS_WEBGPU_BACKEND` pass Dawn's
  * `adapter=` / `backend=` options to `create`, e.g. to pin lavapipe /
  * llvmpipe in CI (list the exact names with `node
  * examples/headless_triangle/render.node.ts --list-adapters`).
  */
class HeadlessPainterTest extends FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(60, "s")

  private lazy val gpu: Future[Opt[GPU]] =
    val env = js.Dynamic.global.process.env
    val options = Arr[String]()
    if !js.isUndefined(env.TRIVALIBS_WEBGPU_ADAPTER) then
      options.push(s"adapter=${env.TRIVALIBS_WEBGPU_ADAPTER}")
    if !js.isUndefined(env.TRIVALIBS_WEBGPU_BACKEND) then
      options.push(s"backend=${env.TRIVALIBS_WEBGPU_BACKEND}")
    // The linked test module runs from a temp dir, so a bare `webgpu` import
    // would not find the repo's node_modules: resolve it from the cwd.
    val loaded = for
      module <- js.`import`[js.Dynamic]("node:module").toFuture
      url <- js.`import`[js.Dynamic]("node:url").toFuture
      cwd = js.Dynamic.global.process.cwd().asInstanceOf[String]
      path = module.createRequire(cwd + "/").resolve("webgpu")
      webgpu <- js.`import`[js.Dynamic](
        url.pathToFileURL(path).href.asInstanceOf[String],
      ).toFuture
    yield webgpu.create(options).asInstanceOf[GPU]: Opt[GPU]
    loaded.recover(_ => null)

  private def gpuOrSkip: Future[GPU] =
    gpu.map: g =>
      assume(g.notNull, "node-webgpu not available")
      g.get

  private def painterOn(
      target: PixelTarget,
      features: Arr[String] = Arr(),
  ): Future[Painter] =
    gpuOrSkip.flatMap: g =>
      Painter
        .create(target, g, requiredFeatures = features)
        .toFuture
        .recover:
          case e =>
            assume(false, s"no WebGPU adapter: $e")
            throw e

  private def channel(image: PixelImage, x: Int, y: Int, c: Int): Double =
    image.data.asInstanceOf[js.Array[Double]]((y * image.width + x) * 4 + c)

  private def valueShade(p: Painter) =
    p.layerShade[(value: Float)]: program =>
      program.frag: ctx =>
        ctx.out.color := vec4(ctx.bindings.value, 0.0, 0.0, 1.0)

  test("clear color reads back exactly into rgba8unorm"):
    val image = PixelImage(5, 3)
    painterOn(image).flatMap: p =>
      p.paintAndShow(p.panel(clearColor = (0.2, 0.4, 0.6, 1.0)))
      p.awaitReadback().toFuture.map: _ =>
        val data = image.data.asInstanceOf[Uint8Array]
        var i = 0
        while i < 5 * 3 do
          assertEquals(
            (data(i * 4), data(i * 4 + 1), data(i * 4 + 2), data(i * 4 + 3)),
            (51.toShort, 102.toShort, 153.toShort, 255.toShort),
          )
          i += 1

  test("rows are top row first, sampled at pixel centers"):
    val image = PixelImage(4, 2)
    painterOn(image).flatMap: p =>
      val shade = p.layerShade[GPUNone]: program =>
        program.frag: ctx =>
          ctx.out.color := vec4(ctx.in.uv.x, ctx.in.uv.y, 0.0, 1.0)
      p.paintAndShow(p.panel(layer = p.layer(shade)))
      p.awaitReadback().toFuture.map: _ =>
        def expected(v: Double) = (v * 255.0).round.toDouble
        var x = 0
        while x < 4 do
          assertEqualsDouble(channel(image, x, 0, 0), expected((x + 0.5) / 4), 1.0)
          x += 1
        assertEqualsDouble(channel(image, 0, 0, 1), expected(0.25), 1.0)
        assertEqualsDouble(channel(image, 0, 1, 1), expected(0.75), 1.0)

  test("rgba16float keeps HDR values above 1"):
    val image = PixelImage(4, 4, PixelFormat.Rgba16Float)
    painterOn(image).flatMap: p =>
      p.paintAndShow(p.panel(clearColor = (4.0, 0.5, -2.0, 1.0)))
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(channel(image, 3, 3, 0), 4.0)
        assertEquals(channel(image, 3, 3, 1), 0.5)
        assertEquals(channel(image, 3, 3, 2), -2.0)

  test("rgba32float container fails fast without float32-filterable"):
    gpuOrSkip.flatMap: g =>
      WebGPU
        .requestAdapter(g)
        .flatMap(a => WebGPU.requestDevice(a))
        .toFuture
        .map: device =>
          val image = PixelImage(2, 2, PixelFormat.Rgba32Float)
          val error = intercept[js.JavaScriptException]:
            Painter.create(image, device)
          assert(error.getMessage.contains("float32-filterable"))

  test("PixelFrames gets one frame per show, in show order"):
    val frames = PixelFrames(2, 2)
    painterOn(frames).flatMap: p =>
      val value = p.binding(0.0)
      val panel = p.panel(layer = p.layer(valueShade(p)).bind("value" := value))
      for v <- Seq(0.2, 0.4, 0.6) do
        value.set(v)
        p.paintAndShow(panel)
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(frames.frames.length, 3)
        val reds = frames.frames.map(f => f.asInstanceOf[Uint8Array](0).toInt)
        assertEquals(reds.toSeq, Seq(51, 102, 153))

  test("PixelImage keeps the last of several in-flight shows"):
    val image = PixelImage(2, 2)
    painterOn(image).flatMap: p =>
      val value = p.binding(0.0)
      val panel = p.panel(layer = p.layer(valueShade(p)).bind("value" := value))
      for v <- Seq(0.2, 0.4, 0.6, 0.8, 1.0) do
        value.set(v)
        p.paintAndShow(panel)
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(channel(image, 1, 1, 0), 255.0)

  test("showTo resolves with its target, scaled to its size"):
    val image = PixelImage(8, 8)
    painterOn(image).flatMap: p =>
      val panel = p.panel(clearColor = (0.2, 0.4, 0.6, 1.0))
      p.paint(panel)
      val small = PixelImage(3, 2)
      val sequence = PixelFrames(4, 4)
      val both = js.Promise.all(
        Arr[js.Promise[Any]](p.showTo(panel, small), p.showTo(panel, sequence)),
      )
      both.toFuture.map: results =>
        assert(js.special.strictEquals(results(0), small))
        assert(js.special.strictEquals(results(1), sequence))
        assertEquals(channel(small, 2, 1, 1), 102.0)
        assertEquals(sequence.frames.length, 1)

  test("showTo validates its target"):
    val image = PixelImage(4, 4)
    painterOn(image).map: p =>
      val panel = p.panel(clearColor = (0.0, 0.0, 0.0, 1.0))
      p.paint(panel)
      val bad = Obj
        .literal(width = 4, height = 4, format = "rgba8unorm", data = new Uint8Array(3))
        .asInstanceOf[PixelImage]
      val error = intercept[js.JavaScriptException]:
        p.showTo(panel, bad)
      assert(error.getMessage.contains("Painter.showTo"))

  test("atSize overrides the target size and restores it"):
    val image = PixelImage(8, 8)
    painterOn(image).flatMap: p =>
      val sizes = Arr[String]()
      p.onResize((w, h) => sizes.push(s"${w.toInt}x${h.toInt}"))
      val shade = p.layerShade[GPUNone]: program =>
        program.frag: ctx =>
          ctx.out.color := vec4(ctx.in.uv.x, 0.0, 0.0, 1.0)
      val panel = p.panel(layer = p.layer(shade))
      val shot = PixelImage(2, 3)
      var insideWidth = 0.0
      val saved = p.atSize(2, 3):
        insideWidth = p.width
        p.paint(panel)
        p.showTo(panel, shot)
      assertEquals(insideWidth, 2.0)
      assertEquals(p.width, 8.0)
      assertEquals(sizes.toSeq, Seq("8x8", "2x3", "8x8"))
      saved.toFuture.map: _ =>
        // Painted at 2×3, so each of the two columns is one exact texel.
        assertEqualsDouble(channel(shot, 0, 0, 0), (0.25 * 255).round.toDouble, 1.0)
        assertEqualsDouble(channel(shot, 1, 2, 0), (0.75 * 255).round.toDouble, 1.0)
