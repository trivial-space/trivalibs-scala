package trivalibs.graphics.painter

import munit.FunSuite
import org.scalajs.dom.HTMLCanvasElement
import trivalibs.utils.js.jsError
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.*

/** GPU integration tests: render offscreen through node-webgpu and assert on
  * the read-back pixels. `webgpu` is a dev dependency, loaded with a dynamic
  * import; without it, or without an adapter, the tests are skipped.
  *
  * `TRIVALIBS_WEBGPU_ADAPTER` / `TRIVALIBS_WEBGPU_BACKEND` pass Dawn's
  * `adapter=` / `backend=` options to `create`, e.g. to pin lavapipe / llvmpipe
  * in CI (list the exact names with `node
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
      webgpu <- js
        .`import`[js.Dynamic](
          url.pathToFileURL(path).href.asInstanceOf[String],
        )
        .toFuture
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
        .init(target, g, requiredFeatures = features)
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
      p.awaitReadback()
        .toFuture
        .map: _ =>
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
      p.awaitReadback()
        .toFuture
        .map: _ =>
          def expected(v: Double) = (v * 255.0).round.toDouble
          var x = 0
          while x < 4 do
            assertEqualsDouble(
              channel(image, x, 0, 0),
              expected((x + 0.5) / 4),
              1.0,
            )
            x += 1
          assertEqualsDouble(channel(image, 0, 0, 1), expected(0.25), 1.0)
          assertEqualsDouble(channel(image, 0, 1, 1), expected(0.75), 1.0)

  test("rgba16float keeps HDR values above 1"):
    val image = PixelImage(4, 4, PixelFormat.Rgba16Float)
    painterOn(image).flatMap: p =>
      p.paintAndShow(p.panel(clearColor = (4.0, 0.5, -2.0, 1.0)))
      p.awaitReadback()
        .toFuture
        .map: _ =>
          assertEquals(channel(image, 3, 3, 0), 4.0)
          assertEquals(channel(image, 3, 3, 1), 0.5)
          assertEquals(channel(image, 3, 3, 2), -2.0)

  test("rgba32float pixel target fails fast without float32-filterable"):
    gpuOrSkip.flatMap: g =>
      WebGPU
        .requestAdapter(g)
        .flatMap(a => WebGPU.requestDevice(a))
        .toFuture
        .map: device =>
          val image = PixelImage(2, 2, PixelFormat.Rgba32Float)
          val error = intercept[js.JavaScriptException]:
            Painter.init(image, device)
          assert(error.getMessage.contains("float32-filterable"))

  test("init routes a raw canvas to a CanvasSurface"):
    gpuOrSkip.flatMap: g =>
      WebGPU
        .requestAdapter(g)
        .flatMap(a => WebGPU.requestDevice(a))
        .toFuture
        .map: device =>
          val getContext: js.Function1[String, Any] =
            kind => throw jsError(s"getContext($kind)")
          val canvasLike = Obj
            .literal(width = 4, height = 4, getContext = getContext)
            .asInstanceOf[HTMLCanvasElement]
          val error = intercept[js.JavaScriptException]:
            Painter.init(canvasLike, device)
          assert(error.getMessage.contains("getContext(webgpu)"))

  test("PixelFrames gets one frame per show, in show order"):
    val frames = PixelFrames(2, 2)
    painterOn(frames).flatMap: p =>
      val value = p.binding(0.0)
      val panel = p.panel(layer = p.layer(valueShade(p)).bind("value" := value))
      for v <- Seq(0.2, 0.4, 0.6) do
        value.set(v)
        p.paintAndShow(panel)
      p.awaitReadback()
        .toFuture
        .map: _ =>
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
      p.awaitReadback()
        .toFuture
        .map: _ =>
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

  test("showTo caches, works uncached, and survives freeCaptureCache"):
    val image = PixelImage(8, 8)
    painterOn(image).flatMap: p =>
      val red = p.panel(clearColor = (1.0, 0.0, 0.0, 1.0))
      val green = p.panel(clearColor = (0.0, 1.0, 0.0, 1.0))
      p.paint(red, green)
      val first = PixelImage(4, 4)
      val second = PixelImage(4, 4)
      val oneOff = PixelImage(5, 3)
      val all = js.Promise.all(
        Arr[js.Promise[Any]](
          p.showTo(red, first),
          p.showTo(green, second),
          p.showTo(red, oneOff, cache = false),
        ),
      )
      // Freed while those reads are in flight: they still land.
      p.freeCaptureCache()
      val afterFree = PixelImage(4, 4)
      val again = p.showTo(green, afterFree)
      js.Promise
        .all(Arr[js.Promise[Any]](all, again))
        .toFuture
        .map: _ =>
          assertEquals(channel(first, 3, 3, 0), 255.0)
          assertEquals(channel(second, 3, 3, 1), 255.0)
          assertEquals(channel(second, 3, 3, 0), 0.0)
          assertEquals(channel(oneOff, 4, 2, 0), 255.0)
          assertEquals(channel(afterFree, 3, 3, 1), 255.0)

  test("showTo validates its target"):
    val image = PixelImage(4, 4)
    painterOn(image).map: p =>
      val panel = p.panel(clearColor = (0.0, 0.0, 0.0, 1.0))
      p.paint(panel)
      val bad = Obj
        .literal(
          width = 4,
          height = 4,
          format = "rgba8unorm",
          data = new Uint8Array(3),
        )
        .asInstanceOf[PixelImage]
      val error = intercept[js.JavaScriptException]:
        p.showTo(panel, bad)
      assert(error.getMessage.contains("Painter.showTo"))

  test("PixelTarget.validate is public, and passes a well-formed target"):
    val target: PixelTarget = PixelImage(4, 4)
    PixelTarget.validate(target)
    val bad = Obj
      .literal(
        width = 0,
        height = 4,
        format = "rgba8unorm",
        data = new Uint8Array(0),
      )
      .asInstanceOf[PixelImage]
    val error = intercept[js.JavaScriptException]:
      PixelTarget.validate(bad)
    assert(
      error.getMessage.contains(
        "PixelTarget.validate: invalid pixel target size",
      ),
    )

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
        assertEqualsDouble(
          channel(shot, 0, 0, 0),
          (0.25 * 255).round.toDouble,
          1.0,
        )
        assertEqualsDouble(
          channel(shot, 1, 2, 0),
          (0.75 * 255).round.toDouble,
          1.0,
        )

  // ---- texture sources ----

  private def pixels(image: PixelImage): Seq[Double] =
    val data = image.data.asInstanceOf[js.Array[Double]]
    (0 until image.width * image.height * 4).map(i => data(i))

  // Distinct, deterministic RGBA bytes per pixel, alpha 255.
  private def checker(w: Int, h: Int): PixelImage =
    val image = PixelImage(w, h)
    val data = image.data.asInstanceOf[Uint8Array]
    var i = 0
    while i < w * h do
      data(i * 4) = ((i * 37 + 11) % 256).toShort
      data(i * 4 + 1) = ((i * 91 + 7) % 256).toShort
      data(i * 4 + 2) = ((i * 13 + 200) % 256).toShort
      data(i * 4 + 3) = 255.toShort
      i += 1
    image

  private def solid(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int) =
    val image = PixelImage(w, h)
    val data = image.data.asInstanceOf[Uint8Array]
    var i = 0
    while i < w * h do
      data(i * 4) = r.toShort
      data(i * 4 + 1) = g.toShort
      data(i * 4 + 2) = b.toShort
      data(i * 4 + 3) = a.toShort
      i += 1
    image

  private def inverted(image: PixelImage): Seq[Double] =
    pixels(image).zipWithIndex.map((v, i) => if i % 4 == 3 then v else 255 - v)

  private def invertLayer(p: Painter) =
    val shade = p.layerShade[(samp: Sampler), (src: FragmentPanel)]: program =>
      program.frag: ctx =>
        val c = ctx.textures.src(ctx.in.uv, ctx.bindings.samp)
        ctx.out.color := vec4(1.0 - c.x, 1.0 - c.y, 1.0 - c.z, 1.0)
    p.layer(shade).bind("samp" := p.samplerNearest)

  private def fullscreenShape(p: Painter, color: Vec3) =
    type Attribs = (position: Vec2)
    type Uniforms = (color: Vec3)
    val shade = p.shade[Attribs, EmptyTuple, Uniforms](
      vertWgsl = """
        out.position = vec4<f32>(in.position, 0.5, 1.0);
        """,
      fragWgsl = """
        out.color = vec4<f32>(color, 1.0);
        """,
    )
    val vertices = allocateAttribs[Attribs](3)
    vertices(0).set0(-1.0, -1.0)
    vertices(1).set0(3.0, -1.0)
    vertices(2).set0(-1.0, 3.0)
    p.shape(p.form(vertices = vertices), shade).bind("color" := color)

  test("a PixelImage source is shown exactly, without painting"):
    val out = PixelImage(4, 3)
    val src = checker(4, 3)
    painterOn(out).flatMap: p =>
      val panel = p.panel(source = src)
      p.show(panel)
      p.paint(panel)
      p.show(panel)
      p.awaitReadback().toFuture.map(_ => assertEquals(pixels(out), pixels(src)))

  test("an rgba16float source keeps HDR values"):
    val out = PixelImage(2, 2, PixelFormat.Rgba16Float)
    val data = new Float16Array(16)
    var i = 0
    while i < 16 do
      data(i) = if i % 4 == 3 then 1.0 else i * 0.5
      i += 1
    val src = PixelImage(2, 2, data)
    painterOn(out).flatMap: p =>
      p.show(p.panel(source = src))
      p.awaitReadback().toFuture.map(_ => assertEquals(pixels(out), pixels(src)))

  test("layers process the source fresh on every paint"):
    val out = PixelImage(4, 3)
    val src = checker(4, 3)
    painterOn(out).flatMap: p =>
      val panel = p.panel(source = src, layer = invertLayer(p))
      p.paint(panel)
      p.paint(panel)
      p.show(panel)
      p.awaitReadback().toFuture.map(_ => assertEquals(pixels(out), inverted(src)))

  test("a source scales to an explicit size"):
    val out = PixelImage(6, 4)
    painterOn(out).flatMap: p =>
      p.paintAndShow(
        p.panel(source = solid(2, 2, 10, 120, 240, 255), width = 6, height = 4),
      )
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(
          pixels(out),
          Seq.fill(6 * 4)(Seq(10.0, 120.0, 240.0, 255.0)).flatten,
        )

  test("sourceBlend blends the source over the clear color"):
    val out = PixelImage(2, 2)
    painterOn(out).flatMap: p =>
      p.paintAndShow(
        p.panel(
          source = solid(2, 2, 255, 0, 0, 128),
          sourceBlend = BlendState.Alpha,
          clearColor = (0.0, 0.0, 1.0, 1.0),
        ),
      )
      p.awaitReadback().toFuture.map: _ =>
        assertEqualsDouble(channel(out, 1, 1, 0), 128.0, 2.0)
        assertEqualsDouble(channel(out, 1, 1, 2), 127.0, 2.0)

  test("shapes land on top of the source, with depth and MSAA"):
    val out = PixelImage(4, 4)
    painterOn(out).flatMap: p =>
      p.paintAndShow(
        p.panel(
          source = checker(4, 4),
          depthTest = true,
          multisample = true,
          shape = fullscreenShape(p, Vec3(0.0, 1.0, 0.0)),
        ),
      )
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(
          pixels(out),
          Seq.fill(16)(Seq(0.0, 255.0, 0.0, 255.0)).flatten,
        )

  test("a pong layer and MSAA added after the first paint reallocate"):
    val out = PixelImage(2, 2)
    painterOn(out).flatMap: p =>
      val panel = p.panel(clearColor = (0.2, 0.4, 0.6, 1.0))
      p.paint(panel)
      panel.set(layer = invertLayer(p))
      p.paint(panel)
      panel.set(
        multisample = true,
        layers = Arr[AnyLayer](),
        shape = fullscreenShape(p, Vec3(1.0, 0.0, 0.0)),
      )
      p.paint(panel)
      val afterPong = PixelImage(2, 2)
      p.showTo(panel, afterPong)
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(pixels(afterPong).take(4), Seq(255.0, 0.0, 0.0, 255.0))

  test("path switches and unsetting the source keep its pixels"):
    val out = PixelImage(4, 3)
    val src = checker(4, 3)
    painterOn(out).flatMap: p =>
      val panel = p.panel(source = src)
      panel.set(layer = invertLayer(p))
      p.paint(panel)
      val drawn = p.showTo(panel, PixelImage(4, 3))
      panel.set(layers = Arr[AnyLayer]())
      val direct = p.showTo(panel, PixelImage(4, 3))
      panel.set(source = null)
      p.paint(panel)
      val unset = p.showTo(panel, PixelImage(4, 3))
      for
        d <- drawn.toFuture
        r <- direct.toFuture
        u <- unset.toFuture
      yield
        assertEquals(pixels(d), inverted(src))
        assertEquals(pixels(r), pixels(src))
        assertEquals(pixels(u), pixels(src))

  test("pixelsAt turns a frame into a source"):
    val out = PixelImage(2, 2)
    val frames = PixelFrames(2, 2)
    frames.frames.push(checker(2, 2).data)
    frames.frames.push(solid(2, 2, 1, 2, 3, 4).data)
    painterOn(out).flatMap: p =>
      p.show(p.panel(source = frames.pixelsAt(1)))
      val error = intercept[js.JavaScriptException]:
        frames.pixelsAt(2)
      assert(error.getMessage.contains("PixelFrames.pixelsAt"))
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(pixels(out), Seq.fill(4)(Seq(1.0, 2.0, 3.0, 4.0)).flatten)

  test("a source on an MRT panel throws"):
    val out = PixelImage(2, 2)
    painterOn(out).map: p =>
      val error = intercept[js.JavaScriptException]:
        p.panel(
          formats = Arr(TextureFormat.Rgba8Unorm, TextureFormat.Rgba8Unorm),
          source = checker(2, 2),
        )
      assert(error.getMessage.contains("cannot have a source"))

  test("followSurface stretches the source over the surface size"):
    val out = PixelImage(5, 3)
    painterOn(out).flatMap: p =>
      val panel =
        p.panel(source = solid(2, 2, 40, 80, 160, 255), followSurface = true)
      p.paint(panel)
      assertEquals((panel.panelWidth, panel.panelHeight), (5, 3))
      p.show(panel)
      p.awaitReadback().toFuture.map: _ =>
        assertEquals(
          pixels(out),
          Seq.fill(15)(Seq(40.0, 80.0, 160.0, 255.0)).flatten,
        )
