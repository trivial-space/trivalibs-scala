# Shader DSL Guide

Write shaders as typed Scala that generates WGSL at build time — no `@group` /
`@binding` / `@location` decorations, no struct declarations (those come from
the schemas). For the full pipeline see
[sketch-authoring-guide.md](sketch-authoring-guide.md); for the exhaustive op
list query Metals (`inspect trivalibs.graphics.math.gpu.Expr`, `get-docs`) or
the Scaladoc site.

## Schemas (named tuples)

A shade is parameterised by named-tuple schemas. **Field order = layout index,
field name = WGSL variable name.**

```scala
type Attribs  = (position: Vec3, uv: Vec2)        // A — vertex attributes
type Varyings = (uv: Vec2)                         // V — vertex → fragment
type Uniforms = (                                  // U — uniforms
  mvp:  VertexUniform[Mat4],                        //   vertex-only
  tint: FragmentUniform[Vec3],                      //   fragment-only
  samp: FragmentUniform[Sampler],                   //   a sampler is just a field
  time: Float,                                      //   bare = both stages
)
type Panels = (tex: FragmentPanel)                 // P — panel textures (optional)
```

- `VertexUniform[T]` / `FragmentUniform[T]` set stage visibility; bare `T` =
  both.
- In `layerShade`, every uniform is fragment-stage, so drop the wrappers.
- Panel textures go in a separate `P` schema as `FragmentPanel` (or
  `VertexPanel`/`SharedPanel`) markers, read via `ctx.textures.<name>`. For a
  **depth** attachment use the `*DepthPanel` markers (`FragmentDepthPanel` / …):
  the field reads as a `DepthTexture2D` and is bound with
  `panel.binding(depth = true)`.

## vert / frag and the context

`p.shade[A, V, U]` (or `[A, V, U, P]`, `[A, V, U, P, FO]`) takes a `program`
builder. Define `program.vert` and `program.frag`; each returns a `Block`:

```scala
val shade = p.shade[Attribs, Varyings, Uniforms, Panels]: program =>
  program.vert: ctx =>
    Block(
      ctx.out.position := ctx.bindings.mvp * vec4(ctx.in.position, 1.0),
      ctx.out.uv       := ctx.in.uv,
    )
  program.frag: ctx =>
    ctx.out.color := ctx.textures.tex.sample(ctx.in.uv, ctx.bindings.samp) * vec4(ctx.bindings.tint, 1.0)
```

`ctx` exposes:

| accessor        | in `vert`                 | in `frag`                          |
| --------------- | ------------------------- | ---------------------------------- |
| `ctx.in`        | attributes `A`            | varyings `V`                       |
| `ctx.out`       | varyings + `out.position` | fragment output `FO` (`out.color`) |
| `ctx.bindings`  | uniforms `U`              | uniforms `U`                       |
| `ctx.textures`  | panels `P`                | panels `P`                         |
| `ctx.fragCoord` | —                         | `@builtin(position)` pixel coords  |

`layerShade[U]` (or `[U, P]`, `[U, P, FO]`) is fragment-only; the vertex stage
is a built-in full-screen triangle and `ctx.in.uv: Vec2` is the screen UV in
`[0,1]`, **origin top-left** (`uv.y == 0` is the top row). A layer with no
uniforms at all takes no type argument — write `p.layerShade: program => …`, not
`p.layerShade[EmptyTuple]`.

## Locals

Declare them in the body. The Scala `val` is the handle — fully typed, with the
same ops as any other expression of that type — and the WGSL declaration is
emitted by its **first** `:=`:

```scala
program.frag: ctx =>
  val n = LetFloat("n")           // immutable WGSL `let`
  val c = VarVec3("c")            // mutable WGSL `var` (first := declares)
  Block(
    n := ctx.in.uv.x,
    c := vec3(0.0),
    c := c + vec3(n),             // reassign a var
    ctx.out.color := vec4(c, 1.0),
  )
```

`Let*` / `Var*` / `Const*` exist for every expression type — `LetVec2`,
`VarMat4`, `ConstBool`, `LetIVec3`, and so on. One Scala `val` per WGSL name:
two instances of the same name each emit a declaration, and WGSL rejects the
redefinition.

### Assignment statements (`:=` and `+= -= *= /=`)

`:=` produces an assignment `Stmt`. Its WGSL depends on the target:

| target                       | first `:=`     | later `:=`     |
| ---------------------------- | -------------- | -------------- |
| `LetFloat` / `LetVec*` …     | `let n = …;`   | `let n = …;`   |
| `VarFloat` / `VarVec*` …     | `var n = …;`   | `n = …;`       |
| `ConstFloat` / `ConstVec*` … | `const n = …;` | `const n = …;` |
| `ctx.out.*`                  | `n = …;`       | `n = …;`       |

**`:=` is type-checked against the target.** A local and an output slot each
carry their element type, so a mismatched assignment is a Scala compile error
rather than a WGSL one:

```scala
val col = VarVec3("col")
col := someFloatExpr        // ✗ Cannot assign a FloatExpr to a shader local of type Vec3Expr.
ctx.out.color := 0.5        // ✗ …of type Vec4Expr.
```

The right-hand side must therefore be a _typed_ expression. `Expr.raw` yields a
bare `Expr`, which carries no element type and so fits no slot — reach for the
typed constructor instead (`Vec3Expr("…")`, `Vec4Expr("…")`), which emits the
same WGSL.

An output slot is an `AssignTarget[E]`, so a helper that takes one writes the
element type: `def shadeInto(color: AssignTarget[Vec4Expr]): Block`.

A `Var*` local is stateful: the **first** `:=` it sees emits the `var`
declaration, every subsequent `:=` emits a plain reassignment. So declare-then-
mutate just works:

```scala
val col = VarVec3("col")
Block(
  col := vec3(0.1),        // var col = vec3<f32>(0.1);
  col := col + vec3(0.2),  // col = (col + vec3<f32>(0.2));
)
```

**Compound assignment** — `Var*` targets also support `+= -= *= /=`, which emit
native WGSL compound assignment and read better for accumulation:

```scala
col += circle(...)         // col += …;   (same as  col := col + …)
col *= falloff             // col *= …;
```

Only reassignable targets (`Var*`) have the compound forms; `let`/`const` are
immutable so they're `:=`-only. A compound op requires the `var` to be **already
declared** — use it after the initial `:=`, never as the variable's first
statement (WGSL has no `col += …` without a prior `var col`).

**Unlike `:=`, the compound ops are unchecked** on the value's category: they
take any expression and let WGSL decide. That is partly by nature — WGSL
broadcasts a scalar across `+ - * /` for vectors, so `vec3Var += floatExpr` is
legal and useful — but it also means a genuine mismatch like
`mat3Var += floatExpr` compiles in Scala and fails in WGSL. Keep the operands in
a combination WGSL accepts.

A compound op never declares a local, so a mismatch here cannot hide behind a
name the way a bad declaration can — WGSL rejects it at the line as written.

## Constants: plain Scala `val`s vs uniforms

A value that is **the same across every draw of a shader** does not need a
uniform. Close over a plain Scala `val` in the shader body — it bakes into the
generated WGSL as a compile-time constant. This is a real advantage of authoring
shaders in Scala: no buffer, no binding, no schema field.

```scala
// global, baked straight into the shader:
val Contrast = 0.85
val Seed     = vec3(140)

val shade = p.shade[A, V, U]: program =>
  program.frag: ctx =>
    Block(
      // `Contrast` lowers to a literal; tweak the val + recompile to change it.
      ctx.out.color := vec4(vec3((n - 0.5) * Contrast + 0.5), 1.0),
    )
```

Reach for a **uniform** (`p.binding[T]` + a `U`-schema field) only when the
value must **vary per shape / per panel** or **change at runtime** (per frame,
from input, animated). Rule of thumb: start with a global `val`; promote it to a
binding the moment you need per-instance or runtime variation — not before.

(`ConstFloat`/`ConstVec*` locals emit a WGSL `const` _inside_ the function body;
a closed-over Scala `val` is simpler still — prefer it for plain global
tunables.)

## Expressions & ops

GPU expressions mirror the CPU math surface, so shader code reads like CPU code:

- **scalars** (`FloatExpr`): `+ - * /`,
  `.sqrt .pow .sin .cos .tan .abs .floor .ceil .fract .exp .log .min .max .clamp .clamp01 .mix .step .smoothstep .smoothstep01 .fit0111 .fit1101`.
  `.smoothstep01` is `.smoothstep(0, 1)` with the edges folded into the equation
  — the common case, since a `t` already in `[0,1]` only wants the ease.
- **vectors** (`Vec2/3/4Expr`): component-wise `+ - * /` (vector or scalar),
  `.dot .cross(Vec3) .length .distance .normalize .mix .clamp .min .max .abs .fract`,
  swizzles `.xy .xyz .rgb .wzyx …`.
- **constructors**: `vec2(x,y)`, `vec3(xy, z)`, `vec4(rgb, 1.0)`,
  `vec3(scalar)`.
- **free-standing `lerp(a, b, t)`** — WGSL's `mix` argument order, as a function
  rather than a method. Reach for it when the **bounds are constants and only
  `t` varies**, which is the case the method form cannot express:
  `0.8.mix(1.0, expr)` does not compile (a CPU `Double` receiver takes `Double`
  arguments), whereas `lerp(0.8, 1.0, expr)` does. With an expression receiver
  the method form reads better — `noise.lerpIn(0.8, 1.0)`.
- **`t.lerpIn(lo, hi)`** — reverse-argument `lerp`, receiver is `t`. The bounds
  may be scalar literals or any GPU value with a `LerpBy[_, FloatExpr]`
  instance, which is every `Vec*Expr` width and `FloatExpr` itself:
  `t.lerpIn(vec3(0), tint)`. A CPU `Vec*` bound is lifted at the call site —
  `t.lerpIn(vec3(SkyTint), vec3(1))`. **`lerpIn` deliberately has no CPU-vector
  overloads**, and that is worth understanding rather than working around:
  `Double` converts to `FloatExpr`, so a `FloatExpr` overload taking CPU `Vec3`
  bounds is also applicable to a CPU call — `0.5.lerpIn(Vec3(0), Vec3(1))` would
  then quietly resolve into the GPU set and hand back a `Vec3Expr` instead of a
  `Vec3`. Keeping each receiver's bounds in its own domain is what stops CPU
  math from turning into shader expressions behind your back.
- **comparisons** → `BoolExpr`: `<  <=  >  >=  ===  !==`, combine with
  `&& || !`.
- **matrices**: `Mat*Expr` `*` (matrix or vector), `.determinant`.
- **textures**: `tex.sample(uv, samp)`, `tex.sampleLevel(uv, samp, lod)`,
  `tex.load(coord, level = 0)` (sampler-free point read), `tex.numLevels`,
  `tex.dimensions`. Depth textures (`DepthTexture2D`) support `.load` and
  `.sample`, both returning a scalar `FloatExpr`. See _Textures: sample vs
  load_.

### Numeric literals

Plain Scala number literals double as GPU expressions — you rarely need to wrap
them. A literal works on **either** side of an operator, with the same result
type as the expression it's combined with:

```scala
expr * 0.5            // FloatExpr
0.5 * expr            // FloatExpr — left side works too
uv + 1.0              // Vec2Expr  — broadcast scalar
2 - uv                // Vec2Expr  — (f32(2) - uv)
0.5 < expr            // BoolExpr  — scalar comparison, either side
```

This covers arithmetic (`+ - * /`) on `FloatExpr` and `Vec2/3/4Expr`, and the
scalar comparisons (`< <= > >= === !==`) on `FloatExpr`.

**As an operand, a bare literal is always a float.** Both `Double` and `Int`
literals convert to `f32` (an `Int` emits `f32(n)`). This matches WGSL, where
most math is `f32`. It holds for Scala `Int`/`Double` **values and expressions**
too, not just literals — a plain `Int` like `(fadeMips - 1)` is accepted
wherever a float expression is expected (e.g. `lod.min(fadeMips - 1)`), so
**never write `.toDouble`** to feed an `Int` into the DSL; it's unnecessary
noise. For an actual integer expression, opt in explicitly:

- `n.i` → `IntExpr` (WGSL `i32`)
- `n.u` → `UInt` → `UIntExpr` (WGSL `u32`)

```scala
count.i + 1.i         // i32 arithmetic — keep both sides explicit
idx.u * 2.u           // u32 arithmetic
```

Integer expressions don't mix with the float-literal sugar: in an **operand**
position write `1.i`, not a bare `1`, when the other operand is an
`IntExpr`/`UIntExpr`.

**In an assignment, the target decides.** `:=` resolves the literal against the
slot's type, so the same `0` lands correctly on either side and `.i` is not
needed to declare an integer local:

```scala
VarInt("seg")   := 0      // var seg = 0;        — i32
VarFloat("f")   := 0      // var f = f32(0);     — f32
VarUInt("n")    := 3      // var n = 3u;         — u32
LetFloat("t")   := 0.5    // let t = 0.5;
```

This is the one place the float default does not apply, because a declaration is
what _fixes_ a local's type: an f32 literal in an `IntExpr`-typed slot would
make the Scala type and the emitted WGSL disagree for the rest of the shader,
and every later use would still type-check.

**Vector comparison is not a literal case.** `v < w` is _component-wise_ and
returns a `Vec` mask (1.0 / 0.0 per lane, lowered to WGSL `step`) rather than a
`BoolExpr`, and it takes another vector only — there's no scalar-literal form on
either side. Use a scalar `FloatExpr` comparison when you want a `BoolExpr` for
control flow.

### CPU `Vec*` / `Mat*` in shader code

A CPU-side `Vec2–4` / `Mat2–4` can be used directly in a shader body — it lowers
to a WGSL **literal** baked in at shader-build time (no uniform, no binding).
This is how a constant gets authored once and used on both sides. Anything
constant in a shader can be precomputed this way, with the full CPU math and
helper libraries (`Vec3(0.08, 0.6, 0.9).hsv2rgb`,
`Mat4.fromTranslation(offset)`), and only the result reaches the WGSL:

```scala
val CeilTint = Vec3(0.86, 0.86, 0.85)      // CPU value, also usable CPU-side

program.frag: ctx =>
  col := ctx.in.light * CeilTint            // CPU value on the right: just works
```

**Right-hand side: no conversion needed.** GPU ops carry CPU overloads, so a
`Vec*`/`Mat*` is accepted wherever an expression operand is expected:

- assignment — `:=`, and the compound `+= -= *= /=`
- arithmetic — `+ - * /` (both `expr op cpuVec` and `floatExpr op cpuVec`)
- vector ops — `.dot .cross .distance .min .max .pow .reflect`
- interpolation / steps — `.mix .lerp .step .smoothstep`
- component-wise comparisons — `< <= > >=`

**Receiver position: convert explicitly.** There is deliberately **no**
`given Conversion[Vec3, Vec3Expr]` (it would make every GPU extension apply to
CPU values, so `v.xy` becomes an ambiguous extension between the CPU and GPU
swizzles in any file importing both — i.e. the standard sketch preamble). So a
CPU value as the _left_ operand does not compile — the compiler tries the CPU
extensions, fails, and does not fall back:

```scala
CeilTint * noise            // ✗ does not compile
CeilTint.toExpr * noise     // ✓ explicit lift
vec3(CeilTint) * noise      // ✓ same thing via the constructor
```

`.toExpr` exists on `Vec2–4` and `Mat2–4`; `vec2`/`vec3`/`vec4` also take a CPU
vector directly. Both run once at build time and produce a WGSL string, so the
choice is purely about readability. Keeping the crossing explicit is the point —
it matches the `Vec3(…)` (CPU) vs `vec3(…)` (GPU) case convention, so the domain
barrier stays visible where it is crossed.

### Textures: sample vs load, and depth

`sample` / `sampleLevel` take normalized UV (`[0,1]`) plus a `Sampler`, with
filtering. `load` takes **integer texel coordinates** and **no sampler** — an
exact point read, ideal for full-screen / `layerShade` passes that read a
texture 1:1 with their target:

```scala
// reading a same-resolution panel 1:1 — the fragment's own pixel is the texel:
ctx.out.color := ctx.textures.src.load(ivec2(ctx.fragCoord.xy))   // level → 0
// off-1:1 (e.g. a half-res source): scale UV by the source size instead:
ctx.out.color := ctx.textures.src.load(ivec2(ctx.in.uv * vec2(ctx.textures.src.dimensions)))
```

`load` coords are texel indices (not UV); out-of-bounds reads return `0` (no
clamp/wrap), and the mip `level` is explicit (defaults to `0`). `ivec2(vec2)`
truncates a float vec to texel coords; `tex.dimensions` gives the size in texels
(use `ctx.fragCoord.xy` when the source is the same resolution as the target).

**Depth attachments.** A `DepthTexture2D` (from a `*DepthPanel` field bound with
`panel.binding(depth = true)`) reads a single scalar. It has no mip pyramid (the
color texture carries one), so there's no depth `sampleLevel` — use `.load` (no
sampler) or `.sample`. A common use is reconstructing world position from
depth + an inverse view-projection in a resolve pass:

```scala
val d   = ctx.textures.depth.load(ivec2(ctx.in.uv * ctx.bindings.res))
val ndc = vec3(ctx.in.uv.x * 2.0 - 1.0, 1.0 - ctx.in.uv.y * 2.0, d)
val wh  = ctx.bindings.invVp * vec4(ndc, 1.0)
val world = wh.xyz / wh.w
```

(For shadow-map PCF you'd want a comparison sampler + `textureSampleCompare`;
that path isn't wrapped in the DSL yet.)

## Naming: uppercase is a type, lowercase is an operation

The DSL follows Scala's own convention, with one deliberate exception, and
knowing which is which tells you what you can write in a type position:

- **Uppercase** names are **types** (and namespace objects): `Block`, `Stmt`,
  `FloatExpr`, `Vec3Expr`, `LetVec2`, `VarVec3`, `WgslFn`, and the shader-lib
  namespaces `Color`, `Hash`, `Simplex`. `LetVec2("p")` looks like a call
  because the companion constructs one — the name is still the type.
- **Lowercase** names are **operations**: `when`, `loop`, `loopIf`, `unroll`,
  `scope`, `select`, `break`, `continue`, `ret`, `mix`, and every extension
  method. None of them names a type.
- **The exception**: `vec2` / `vec3` / `vec4` and `ivec*` / `uvec*` are
  lowercase because WGSL spells them that way. Their CPU counterparts `Vec2` /
  `Vec3` are types, so the pair reads CPU-vs-GPU — but that is a consequence,
  not the rule. Plenty of GPU-side names are uppercase, because they are types.

## Control flow

Every construct has a **function form and an extension form** — pick whichever
reads better at the call site; they emit identical WGSL.

```scala
// branchless select (WGSL select(onFalse, onTrue, cond)):
ctx.out.color := select(blackVec4, color, brightness > threshold)
// or:  (brightness > threshold).select(color, blackVec4)

when(cond)(body)                                  // if        · cond.thenDo(body)
when(c1)(b1).elseIf(c2)(b2).elseDo(b3)            // if / else if / else
loopIf(cond)(body)                                // while     · cond.thenLoop(body)
loop(count)(i => body)                            // for       · count.loop(i => body)
unroll(count)(i => body)                          // no loop   · count.unroll(i => body)
unroll(values)((v, i) => body)                    //           · values.unroll((v, i) => body)
scope(body)                                       // { ... }   — a bare WGSL scope
break                                             // break;
continue                                          // continue;
breakIf(cond)                                     // if (c) { break; }    · cond.thenBreak
continueIf(cond)                                  // if (c) { continue; } · cond.thenContinue
```

Bodies are **by-name and in their own parameter list**, so an indented block is
also a Scala scope — the place to declare the locals that belong to it:

```scala
when(uv.x < 0.5):
  val tint = LetVec3("tint")
  Block(tint := vec3(0.9, 0.4, 0.2), col := col * tint)
.elseDo:
  col := col * 0.5
```

`scope` is the same thing with no condition attached — a plain WGSL `{ ... }`.
Its use is giving repeated code its own scope, so sibling copies may declare the
same local names; see
[gotchas](gotchas.md#a-var-declares-where-its-first-assignment-lands).

### Blocks nest

A `Block` part is itself a `Block`, so a group composes wherever a statement
does — no accumulator array, and a helper may return several statements:

```scala
def plate(i: Int): Block =
  Block(uvs(i) := …, heights(i) := …)

Block(
  uv := …,                       // a single statement
  unroll(4)(plate),              // a group
  when(hit)(col := tint),        // a branch
  Block.empty,                   // nothing at all — contributes no line
  ctx.out.color := vec4(col, 1.0),
)
```

Everything statement-shaped in the DSL is a `Block`: `:=`, `when`, `loop`,
`loopIf`, `unroll`, `scope`, `break`. `Stmt` is the narrower type that says
"exactly one statement" — every `Stmt` is a `Block`, not the other way round.
`Block.empty` is the "emit nothing" part, for the dead branch of a build-time
`if`. To read the emitted WGSL out of a `Block` (in a test, say), use
`Block.unwrap` — the type is opaque.

### Reading a uniform array

A `UniformArray[T, N]` field arrives as an indexable binding — `stops(0)` for a
constant index, `stops(i)` for one computed in the shader:

```scala
val stops = ctx.bindings.stops     // UniformArray[Vec4, 8]  → Vec4Expr elements
val curves = ctx.bindings.curves   // UniformArray[Double, 8] → FloatExpr elements

Block(
  col := stops(0).rgb,
  loop(1, count.toI32): i =>
    col := col.mix(stops(i).rgb, t.pow(curves(i - 1))),
)
```

Indices are **element** indices, `0` to `N-1`. Scalar and `Vec2` arrays are
packed into `vec4` rows on the GPU (see the
[sketch guide](sketch-authoring-guide.md#uniform-arrays)), and the DSL emits the
row/lane arithmetic for you — `curves(i)` becomes `curves[i / 4][i % 4]`. The
one thing worth knowing: a **computed** index into a `Vec2` array repeats the
index expression three times in the emitted WGSL, so bind a complicated index to
a local first.

Arrays are read-only — they are uniforms. For indexed mutable scratch inside a
shader body, there is nothing yet.

### `loop` vs `unroll`: runtime or build time

The two are the same shape, and the bound's type is the whole difference:

```scala
unroll(1, MaxStops)(i => col := col.mix(stops(i).rgb, w(i)))  // Int     → straight-line WGSL
loop  (1, count)   (i => col := col.mix(stops(i).rgb, w(i)))  // IntExpr → a for-loop in WGSL
```

`unroll` runs at **build time**: the index is a Scala `Int`, every use of it
constant-folds, and the emitted WGSL has no loop in it. `loop` emits a real
`for`. A constant bound is allowed on both and is not the same choice —
`unroll(64)` puts 64 copies of the body in the shader source, `loop(64)` keeps
one.

Two things follow from how `unroll` emits:

- all iterations share the **enclosing** scope, so a local declared inside the
  body needs the index in its name — `LetVec4(s"cur$i")`;
- a `var` accumulated across iterations is fine — the first `:=` declares it and
  the rest assign, all in one scope.

A `loop` body is its own WGSL scope, so neither applies: plain `LetVec4("cur")`
inside the callback is correct. What does apply there is the accumulator rule —
see [gotchas](gotchas.md#a-var-declares-where-its-first-assignment-lands).

A bare `Int` as a **receiver** (`64.loop`, `64.unroll`, `4.i`) is a count, not
shader math — the conversion that makes a literal `f32(n)` applies to operands
only.

## Composing shaders with Scala functions

Shader expressions are ordinary Scala values, so a plain `def` that takes and
returns them is already a reusable shader building block: no WGSL function, no
registration. Building and transforming the shader AST is plain Scala code:

```scala
def vignette(uv: Vec2Expr, strength: Double): FloatExpr =
  1.0 - (uv - 0.5).length * strength

def grade(col: Vec3Expr, warm: Boolean): Vec3Expr =
  if warm then col * vec3(1.05, 1.0, 0.92)   // Scala `if`: decided at build time
  else col

program.frag: ctx =>
  ctx.out.color := vec4(grade(ctx.in.color, warm = true) * vignette(ctx.in.uv, 0.8), 1.0)
```

The Scala parameters, `if`s and loops run once, when the shader is built; only
the resulting expression lands in the WGSL. A helper that emits statements works
the same way by returning a `Block` (see _Blocks nest_), and `unroll` is the
loop form of the same idea.

The expression is **inlined at every use**, arguments included: an argument used
twice in the body is emitted twice. Bind an expensive argument to a `Let` local
first, or reach for a `WgslFn` (next section), which emits one WGSL function and
calls it. Rule of thumb: Scala functions for small expressions and for
build-time specialization (a different shader per argument), `WgslFn` for larger
bodies called in many places.

## Helper functions (WgslFn)

Reusable WGSL functions. Calling one in a `program.vert`/`frag` body (or in
another **DSL**-authored fn body) auto-registers it — the DSL records the call.

```scala
// DSL-authored (typed body) — its callees are auto-detected:
val addOffset = WgslFn.dsl[(p: Vec2, off: Vec2), Vec2]("add_offset"):
  (args, ret) => ret(args.p + args.off)

// raw WGSL escape hatch (intrinsics / hand-tuned kernels):
val blur9 = WgslFn
  .raw[(tex: Texture2D, s: Sampler, uv: Vec2, dir: Vec2), Vec4]("blur9"):
    """var col = vec4(0.0);
       // … one statement per line …
       return col;"""
  .withDeps(hash, gaussWeight)   // the WGSL body calls these — must declare
```

**What auto-registers vs what you must declare:**

- Calling a helper through its **typed API** in a DSL body — `program.vert`/
  `frag` or another `WgslFn.dsl` body — auto-registers it, whether its own body
  is raw or DSL. Deps are emitted before dependents, deduped.
- A **`WgslFn.dsl`** helper also auto-collects the helpers _it_ calls (they
  become its deps automatically).
- A **`WgslFn.raw`** helper's body is an opaque WGSL string: the DSL can't see
  the functions it calls _inside that string_, so you **must** declare them with
  `.withDeps(dep1, dep2, …)` — then they travel with it (registered before it)
  wherever it's used.
- `program.fn(f)` is the manual escape hatch — force-register a helper that is
  never reached through a typed call (idempotent).

So: dependency wiring is automatic for DSL-authored helpers; for raw-WGSL
helpers you must declare their callees yourself (`withDeps`, or `program.fn`).

Reusable kernels live in `trivalibs.graphics.shader.lib.*` (`noise`, `color`,
`coords`, `random`, `blur`, `line`) — see the `blur`, `noise_tests` and
`noise_cpu_gpu` examples. Each lib object has three layers:

- **extensions** (from the painter prelude) — the everyday form, the same call
  on a CPU vector and a shader expression: `uv.simplexFbm(octaves = 5)`,
  `p.extendedNoise(tilingPeriod = vec3(8.0, 0.0, 8.0))`, `c.hsv2rgb`, `v.hash`;
- **object API** — `Simplex.fbm2d(pos, gain = 0.8, seed = 3.0)`: wrapper defs
  with named, defaulted parameters that pick the matching WGSL variant;
- **`X.wgsl`** — the raw `WgslFn` values, one per code path. This is what a
  raw-WGSL helper passes to `withDeps` (`myFn.withDeps(Simplex.wgsl.fbm2d)`);
  passing a wrapper def there does not compile.

The CPU side (`trivalibs.graphics.lib.*`) mirrors these libraries with the same
object, member and parameter names, so the extension form is one call for both
domains. The receiver type picks the side at compile time:

```scala
val bg: Vec3    = Vec3(hue, 0.8, 0.5).hsv2rgb // CPU: inlined scalar kernel
val c: Vec3Expr = vec3(hue, 0.8, 0.5).hsv2rgb // GPU: emits color_hsv2rgb(…)
```

`Hash`, `Blur` and `LineCross` are GPU-only; CPU randomness is
`trivalibs.utils.random`.

## Raw WGSL bodies

If you prefer raw WGSL, `p.shade[A, V, U](vertWgsl, fragWgsl)` takes strings.
You still skip the decorations/structs and reference `in.<f>`, `out.<f>`, and
uniforms by name. One statement per line (project convention).

## Builtins

Builtins follow one rule: **input builtins are direct `ctx` members; output
builtins live in `ctx.out`** (alongside your varyings/outputs) — the same on
both stages. They're always declared for DSL shades; an unused one is stripped
by the GPU shader compiler, so it costs nothing unless you reference it.

| builtin              | stage / dir     | access              | type       |
| -------------------- | --------------- | ------------------- | ---------- |
| `@builtin(position)` | vertex **out**  | `ctx.out.position`  | `Vec4`     |
| `vertex_index`       | vertex **in**   | `ctx.vertexIndex`   | `UIntExpr` |
| `instance_index`     | vertex **in**   | `ctx.instanceIndex` | `UIntExpr` |
| `@builtin(position)` | fragment **in** | `ctx.fragCoord`     | `Vec4Expr` |
| `front_facing`       | fragment **in** | `ctx.frontFacing`   | `BoolExpr` |

- `ctx.out.position` — clip-space vertex output; assign it in every `vert`.
- `ctx.fragCoord` — framebuffer pixel coords (`.xy` pixel centers / origin
  top-left, `.z` depth, `.w` = `1/clip.w`). Great for a 1:1
  `tex.load(ivec2(ctx.fragCoord.xy))`.
- `ctx.frontFacing` — `true` on front-facing primitives (two-sided shading).
- In a `layerShade`, the built-in full-screen triangle also gives
  `ctx.in.uv: Vec2Expr` (screen UV in `[0,1]`) — a varying, not a builtin.

**`sample_index` is _not_ a default** — referencing it forces per-sample shading
(the fragment runs once per MSAA sample). Declare it explicitly via the
lower-level `Shader.full[A, V, U, VBI, VBO, FBI, FO]` (put `BuiltinSampleIndex`
in the `FBI` schema) only when you need it. All builtin markers
(`trivalibs.graphics.shader`, for the `Shader.full` route):

| marker                 | `@builtin`       | stage / dir | WGSL type   |
| ---------------------- | ---------------- | ----------- | ----------- |
| `BuiltinPosition`      | `position`       | vertex out  | `vec4<f32>` |
| `BuiltinVertexIndex`   | `vertex_index`   | vertex in   | `u32`       |
| `BuiltinInstanceIndex` | `instance_index` | vertex in   | `u32`       |
| `BuiltinFragCoord`     | `position`       | fragment in | `vec4<f32>` |
| `BuiltinFrontFacing`   | `front_facing`   | fragment in | `bool`      |
| `BuiltinSampleIndex`   | `sample_index`   | fragment in | `u32`       |

See `examples/simple_triangle` for the `Shader.full` route.
