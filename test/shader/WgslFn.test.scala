package trivalibs.graphics.shader.dsl

import munit.FunSuite
import trivalibs.graphics.math.gpu.{*, given}
import trivalibs.graphics.shader.FragOut
import trivalibs.graphics.shader.FragmentUniform
import trivalibs.graphics.shader.VertexUniform
import trivalibs.graphics.shader.given
import trivalibs.utils.js.get

class WgslFnTest extends FunSuite:

  // =========================================================================
  // WgslFn.raw — source generation
  // =========================================================================

  test("WgslFn.raw with 1 param generates correct WGSL source"):
    val fn: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("double_it")("  return x * 2.0;")
    val data = fn.asInstanceOf[WgslFnData]
    assertEquals(data.name, "double_it")
    assert(
      data.src.contains("fn double_it("),
      s"Missing fn declaration:\n${data.src}",
    )
    assert(
      data.src.contains("-> f32"),
      s"Missing return type:\n${data.src}",
    )
    assert(
      data.src.contains("return x * 2.0;"),
      s"Missing body:\n${data.src}",
    )

  test("WgslFn.raw generates correct param list for named tuple"):
    val fn: WgslFn[(v: Vec2, angle: Float), Vec2] =
      WgslFn.raw("rotate")("  return v;")
    val data = fn.asInstanceOf[WgslFnData]
    assert(
      data.src.contains("v: vec2<f32>"),
      s"Missing v param:\n${data.src}",
    )
    assert(
      data.src.contains("angle: f32"),
      s"Missing angle param:\n${data.src}",
    )
    assert(
      data.src.contains("-> vec2<f32>"),
      s"Missing return type:\n${data.src}",
    )

  test("WgslFn.raw with 3 params"):
    val fn: WgslFn[(pos: Vec2, mat: Mat2, offset: Vec2), Vec2] =
      WgslFn.raw("apply_transform")("  return mat * pos + offset;")
    val data = fn.asInstanceOf[WgslFnData]
    assert(data.src.contains("pos: vec2<f32>"), data.src)
    assert(data.src.contains("mat: mat2x2<f32>"), data.src)
    assert(data.src.contains("offset: vec2<f32>"), data.src)

  test("WgslFn.raw with Unit return type generates void"):
    val fn: WgslFn[Float *: EmptyTuple, Unit] =
      WgslFn.raw("no_op")("  // nothing")
    val data = fn.asInstanceOf[WgslFnData]
    assert(data.src.contains("-> void"), s"Missing void return:\n${data.src}")

  // =========================================================================
  // WgslFn apply — call expression generation
  // =========================================================================

  test("WgslFn arity-1 apply generates correct call expression"):
    val fn: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("double_it")("  return x * 2.0;")
    val a = FloatExpr("myVal")
    val result = fn(a)
    assertEquals(result.toString, "double_it(myVal)")

  test("WgslFn arity-2 apply generates correct call expression"):
    val fn: WgslFn[(v: Vec2, angle: Float), Vec2] =
      WgslFn.raw("rotate")("  return v;")
    val v = Vec2Expr("pos")
    val a = FloatExpr("theta")
    val result = fn(v, a)
    assertEquals(result.toString, "rotate(pos, theta)")

  test("WgslFn arity-3 apply generates correct call expression"):
    val fn: WgslFn[(pos: Vec2, mat: Mat2, offset: Vec2), Vec2] =
      WgslFn.raw("transform")("  return mat * pos + offset;")
    val result = fn(Vec2Expr("p"), Mat2Expr("m"), Vec2Expr("o"))
    assertEquals(result.toString, "transform(p, m, o)")

  test("WgslFn apply return type is correct Expr subtype"):
    val fn: WgslFn[(v: Vec3, s: Float), Vec3] =
      WgslFn.raw("scale_vec")("  return v * s;")
    val result: Vec3Expr = fn(Vec3Expr("v"), FloatExpr("s"))
    assertEquals(result.toString, "scale_vec(v, s)")

  test("WgslFn apply can be used in larger DSL expressions"):
    val fn: WgslFn[(v: Vec2, angle: Float), Vec2] =
      WgslFn.raw("rotate")("  return v;")
    val rotated = fn(Vec2Expr("pos"), FloatExpr("angle"))
    val result = vec4(rotated, 0.0, 1.0)
    assertEquals(result.toString, "vec4<f32>(rotate(pos, angle), 0.0, 1.0)")

  // =========================================================================
  // WgslFn.dsl — DSL body constructor
  // =========================================================================

  test("WgslFn.dsl generates correct source"):
    val fn: WgslFn[(a: Float, b: Float), Float] =
      WgslFn.dsl("add"): (p, ret) =>
        ret(p.a + p.b)
    val data = fn.asInstanceOf[WgslFnData]
    assert(data.src.contains("fn add("), data.src)
    assert(data.src.contains("a: f32"), data.src)
    assert(data.src.contains("b: f32"), data.src)
    assert(data.src.contains("-> f32"), data.src)
    assert(data.src.contains("return (a + b);"), data.src)

  test("WgslFn.dsl with Vec2 params and return"):
    val fn: WgslFn[(v: Vec2, offset: Vec2), Vec2] =
      WgslFn.dsl("add_offset"): (p, ret) =>
        ret(p.v + p.offset)
    val data = fn.asInstanceOf[WgslFnData]
    assert(data.src.contains("v: vec2<f32>"), data.src)
    assert(data.src.contains("offset: vec2<f32>"), data.src)
    assert(data.src.contains("return (v + offset);"), data.src)

  // =========================================================================
  // Program.fn — registration and deduplication
  // =========================================================================

  test("program.fn registers helper function"):
    type Attribs = (position: Vec2)
    type Uniforms = (angle: VertexUniform[Float])
    val program = Program[Attribs, EmptyTuple, Uniforms, EmptyTuple, FragOut]()

    val rotate: WgslFn[(v: Vec2, angle: Float), Vec2] =
      WgslFn.raw("rotate")("  return v;")
    program.fn(rotate)

    assert(
      program.helperFnsStr.contains("fn rotate("),
      s"Helper not registered:\n${program.helperFnsStr}",
    )

  test("program.fn is idempotent — registering twice emits once"):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val fn: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("my_fn")("  return x;")
    program.fn(fn)
    program.fn(fn)

    val count = program.helperFnsStr.split("fn my_fn").length - 1
    assertEquals(count, 1)

  test("program.fn accumulates multiple functions in order"):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val fn1: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("fn_one")("  return x;")
    val fn2: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("fn_two")("  return x * 2.0;")
    program.fn(fn1)
    program.fn(fn2)

    val helpers = program.helperFnsStr
    val pos1 = helpers.indexOf("fn_one")
    val pos2 = helpers.indexOf("fn_two")
    assert(pos1 < pos2, "fn_one should appear before fn_two")

  // =========================================================================
  // Integration — function used in vertex body
  // =========================================================================

  test("WgslFn call expression integrates into vertex body"):
    type Attribs = (position: Vec2)
    type Uniforms = (angle: VertexUniform[Float])
    val program = Program[Attribs, EmptyTuple, Uniforms, EmptyTuple, FragOut]()

    val rotate: WgslFn[(v: Vec2, angle: Float), Vec2] =
      WgslFn.raw("rotate")("  return v;")
    program.fn(rotate)

    program.vert: ctx =>
      Block(
        ctx.out.position := vec4(
          rotate(ctx.in.position, ctx.bindings.angle),
          0.0,
          1.0,
        ),
      )

    val body = program.vertBodyStr
    assert(
      body.contains("rotate(in.position, angle)"),
      s"Missing rotate call:\n$body",
    )

  // =========================================================================
  // VarExpr inside WgslFn.dsl — manual var declaration
  // =========================================================================

  test("VarVec2 inside WgslFn.dsl generates var decl and reassignment"):
    val fn =
      WgslFn.dsl[(v: Vec2, delta: Vec2), Vec2]("accumulate"): (p, ret) =>
        val acc = VarVec2("acc")
        Block(
          acc := p.v,
          acc := acc + p.delta,
          ret(acc),
        )

    val data = fn.asInstanceOf[WgslFnData]

    assert(data.src.contains("var acc = v;"), s"Missing var decl:\n${data.src}")
    assert(
      data.src.contains("acc = (acc + delta);"),
      s"Missing reassign:\n${data.src}",
    )
    assert(data.src.contains("return acc;"), s"Missing return:\n${data.src}")

  // =========================================================================
  // WgslFn.dsl with ad-hoc locals
  // =========================================================================

  test("WgslFn.dsl with a local var generates decl and reassignment"):
    val fn: WgslFn[(v: Vec2, delta: Vec2), Vec2] =
      WgslFn.dsl[(v: Vec2, delta: Vec2), Vec2]("accumulate"): (p, ret) =>
        val acc = VarVec2("acc")
        Block(
          acc := p.v,
          acc := acc + p.delta,
          ret(acc),
        )

    val data = fn.asInstanceOf[WgslFnData]
    assert(data.src.contains("var acc = v;"), s"Missing var decl:\n${data.src}")
    assert(
      data.src.contains("acc = (acc + delta);"),
      s"Missing reassign:\n${data.src}",
    )
    assert(data.src.contains("return acc;"), s"Missing return:\n${data.src}")

  test("WgslFn.dsl with const local generates const decl"):
    val fn: WgslFn[(v: Vec2, scale: Float), Vec2] =
      WgslFn.dsl[(v: Vec2, scale: Float), Vec2]("scaleVec"): (p, ret) =>
        val s = ConstFloat("s")
        Block(
          s := p.scale,
          ret(p.v * s),
        )

    val data = fn.asInstanceOf[WgslFnData]
    assert(
      data.src.contains("const s = scale;"),
      s"Missing const decl:\n${data.src}",
    )

  test("WgslFn.dsl with mixed locals (var + let)"):
    val fn: WgslFn[(v: Vec2), Vec2] =
      WgslFn.dsl[(v: Vec2), Vec2]("mixedLocals"): (p, ret) =>
        val acc = VarVec2("acc")
        val tmp = LetVec2("tmp")
        Block(
          acc := p.v,
          tmp := acc,
          ret(tmp),
        )

    val data = fn.asInstanceOf[WgslFnData]
    assert(
      data.src.contains("var acc = v;"),
      s"Missing var decl:\n${data.src}",
    )
    assert(
      data.src.contains("let tmp = acc;"),
      s"Missing let decl:\n${data.src}",
    )

  // =========================================================================
  // Auto-registration via FnRegistry — calls inside program/dsl bodies
  // automatically register the called fn (and its withDeps chain) on the
  // enclosing program / parent dsl fn.
  // =========================================================================

  // Helper: split a program.helperFnsStr into ordered fn names so tests can
  // assert dependency ordering and dedup without scraping raw WGSL.
  def fnNames(helpers: String): Seq[String] =
    "fn ([a-zA-Z_][a-zA-Z0-9_]*)\\(".r
      .findAllMatchIn(helpers)
      .map(_.group(1).get)
      .toSeq

  test("auto-register: fn called in program.frag without program.fn(...)"):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val double_it: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("double_it")("  return x * 2.0;")

    program.vert: ctx =>
      Block(
        ctx.out.position := vec4(ctx.in.position, 0.0, 1.0),
      )
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(double_it(FloatExpr("0.5")), 0.0, 0.0, 1.0),
      )

    assertEquals(fnNames(program.helperFnsStr), Seq("double_it"))

  test("auto-register: dsl fn called in program body, raw fn not called"):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val called: WgslFn[(a: Float, b: Float), Float] =
      WgslFn.dsl("add_them"): (p, ret) =>
        ret(p.a + p.b)

    val notCalled: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("dead_fn")("  return x;")

    val _ = notCalled // touch the val so it is realized; still not invoked

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(called(FloatExpr("0.5"), FloatExpr("0.5"))),
      )

    val names = fnNames(program.helperFnsStr)
    assertEquals(names, Seq("add_them"))
    assert(!names.contains("dead_fn"), "uncalled fn must not appear")

  test("auto-register: nested DSL fn auto-collects its callees as deps"):
    val leaf: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("leaf")("  return x + 1.0;")

    val mid: WgslFn[(x: Float), Float] =
      WgslFn.dsl("mid"): (p, ret) =>
        ret(leaf(p.x) + 1.0)

    // mid's deps should have been auto-collected from the call site of leaf
    val midData = mid.asInstanceOf[WgslFnData]
    assertEquals(midData.deps.length, 1)
    assertEquals(midData.deps(0).name, "leaf")

  test(
    "auto-register: program.frag picks up nested dsl fn AND its transitive raw dep",
  ):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val leaf: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("leaf")("  return x + 1.0;")

    val mid: WgslFn[(x: Float), Float] =
      WgslFn.dsl("mid"): (p, ret) =>
        ret(leaf(p.x) + 1.0)

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(mid(FloatExpr("0.5")), 0.0, 0.0, 1.0),
      )

    val names = fnNames(program.helperFnsStr)
    // deps emitted before dependents
    assertEquals(names, Seq("leaf", "mid"))

  test(
    "manual withDeps on raw fn: program walks the chain when raw fn is called",
  ):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val a: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("a")("  return x;")
    val b: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("b")("  return a(x);").withDeps(a)
    val c: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("c")("  return b(x);").withDeps(b)

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(c(FloatExpr("0.5")), 0.0, 0.0, 1.0),
      )

    assertEquals(fnNames(program.helperFnsStr), Seq("a", "b", "c"))

  test(
    "mixed: dsl fn calls raw helper that itself withDeps another raw helper",
  ):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val deepRaw: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("deep_raw")("  return x + 0.1;")
    val midRaw: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn
        .raw("mid_raw")("  return deep_raw(x) * 2.0;")
        .withDeps(deepRaw)

    val topDsl: WgslFn[(x: Float), Float] =
      WgslFn.dsl("top_dsl"): (p, ret) =>
        ret(midRaw(p.x) + 1.0)

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(topDsl(FloatExpr("0.5")), 0.0, 0.0, 1.0),
      )

    assertEquals(
      fnNames(program.helperFnsStr),
      Seq("deep_raw", "mid_raw", "top_dsl"),
    )

  test("withDeps merges with auto-collected deps of a dsl fn (no duplicates)"):
    val rawHelper: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("raw_helper")("  return x + 0.5;")
    val dslCallee: WgslFn[(x: Float), Float] =
      WgslFn.dsl("dsl_callee"): (p, ret) =>
        ret(p.x + 1.0)
    val extraRaw: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("extra_raw")("  return x - 1.0;")

    // dslCallee is auto-collected (called in body); rawHelper auto-collected
    // (called in body); extraRaw is added via explicit withDeps; all should
    // appear once on `parent`'s deps.
    val parent: WgslFn[(x: Float), Float] =
      WgslFn
        .dsl[(x: Float), Float]("parent"): (p, ret) =>
          ret(dslCallee(rawHelper(p.x)))
        .withDeps(extraRaw, rawHelper) // rawHelper duplicated intentionally

    val parentData = parent.asInstanceOf[WgslFnData]
    val depNames = (0 until parentData.deps.length).map(parentData.deps(_).name)
    // auto-collected deps appear first (in call order), then withDeps appends
    // any not already present.
    assertEquals(depNames.toSet, Set("raw_helper", "dsl_callee", "extra_raw"))
    // No duplicates
    assertEquals(depNames.length, depNames.toSet.size)

  test("dedup: same fn called multiple times in body emitted only once"):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val twice: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("twice")("  return x * 2.0;")

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(
          twice(FloatExpr("1.0")) + twice(FloatExpr("2.0")),
          0.0,
          0.0,
          1.0,
        ),
      )

    val occurrences = program.helperFnsStr.split("fn twice\\(").length - 1
    assertEquals(occurrences, 1)

  test(
    "auto-register cooperates with explicit program.fn(...) — no duplicates",
  ):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val fn: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("both_paths")("  return x;")

    // Pre-register explicitly
    program.fn(fn)

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(fn(FloatExpr("0.5")), 0.0, 0.0, 1.0),
      )

    val occurrences =
      program.helperFnsStr.split("fn both_paths\\(").length - 1
    assertEquals(occurrences, 1)

  test("LayerProgram.frag auto-registers fn called in body"):
    val layer = LayerProgram[EmptyTuple, EmptyTuple, FragOut]()

    val noiseHelper: WgslFn[Vec2 *: EmptyTuple, Float] =
      WgslFn.raw("noise_helper")("  return uv.x;")

    layer.frag: ctx =>
      Block(
        ctx.out.color :=
          vec4(noiseHelper(ctx.in.uv), 0.0, 0.0, 1.0),
      )

    assertEquals(fnNames(layer.helperFnsStr), Seq("noise_helper"))

  test(
    "lambda-passing helper: body lambda built outside program scope still " +
      "auto-registers (ambient global registry survives helper indirection)",
  ):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val helperFn: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("via_helper")("  return x + 1.0;")

    // Body lambda built OUTSIDE any program/dsl scope. Ambient registry
    // becomes active when the lambda is invoked from inside program.frag.
    val makeBody: () => (FragmentCtx[
      EmptyTuple,
      EmptyTuple,
      EmptyTuple,
      FragOut,
    ] => Block) = () =>
      ctx =>
        Block(
          ctx.out.color := vec4(helperFn(FloatExpr("0.5")), 0.0, 0.0, 1.0),
        )

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag(makeBody())

    assertEquals(fnNames(program.helperFnsStr), Seq("via_helper"))

  test("nested fn-call diamond: shared dep registered once"):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val shared: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("shared")("  return x + 0.1;")
    val left: WgslFn[(x: Float), Float] =
      WgslFn.dsl("left"): (p, ret) =>
        ret(shared(p.x) * 2.0)
    val right: WgslFn[(x: Float), Float] =
      WgslFn.dsl("right"): (p, ret) =>
        ret(shared(p.x) - 1.0)

    program.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    program.frag: ctx =>
      Block(
        ctx.out.color := vec4(
          left(FloatExpr("0.5")) + right(FloatExpr("0.5")),
          0.0,
          0.0,
          1.0,
        ),
      )

    val names = fnNames(program.helperFnsStr)
    assertEquals(names.count(_ == "shared"), 1)
    // shared comes before its first user, which is `left` (registered first)
    assert(
      names.indexOf("shared") < names.indexOf("left"),
      s"shared must appear before left: $names",
    )
    assert(
      names.indexOf("shared") < names.indexOf("right"),
      s"shared must appear before right: $names",
    )

  test(
    "program.fn end-to-end with transitive deps via withDeps: full chain emitted",
  ):
    type Attribs = (position: Vec2)
    val program = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    val a: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("chain_a")("  return x;")
    val b: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("chain_b")("  return chain_a(x);").withDeps(a)
    val c: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("chain_c")("  return chain_b(x);").withDeps(b)

    // Register only the top fn; expect transitive walk via fnRec.
    program.fn(c)

    val names = fnNames(program.helperFnsStr)
    assertEquals(names, Seq("chain_a", "chain_b", "chain_c"))

  test(
    "isolation: two programs sharing one fn + each with a distinct one — " +
      "registries do not leak across programs",
  ):
    type Attribs = (position: Vec2)

    val shared: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("shared_fn")("  return x;")
    val onlyA: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("only_a")("  return x + 1.0;")
    val onlyB: WgslFn[Float *: EmptyTuple, Float] =
      WgslFn.raw("only_b")("  return x - 1.0;")

    val progA = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()
    val progB = Program[Attribs, EmptyTuple, EmptyTuple, EmptyTuple, FragOut]()

    progA.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    progA.frag: ctx =>
      Block(
        ctx.out.color := vec4(
          shared(FloatExpr("0.5")) + onlyA(FloatExpr("0.5")),
          0.0,
          0.0,
          1.0,
        ),
      )

    progB.vert: ctx =>
      Block(ctx.out.position := vec4(ctx.in.position, 0.0, 1.0))
    progB.frag: ctx =>
      Block(
        ctx.out.color := vec4(
          shared(FloatExpr("0.5")) + onlyB(FloatExpr("0.5")),
          0.0,
          0.0,
          1.0,
        ),
      )

    val namesA = fnNames(progA.helperFnsStr).toSet
    val namesB = fnNames(progB.helperFnsStr).toSet
    assertEquals(namesA, Set("shared_fn", "only_a"))
    assertEquals(namesB, Set("shared_fn", "only_b"))
    assert(
      !namesA.contains("only_b"),
      s"progA must not contain progB's fn: $namesA",
    )
    assert(
      !namesB.contains("only_a"),
      s"progB must not contain progA's fn: $namesB",
    )
