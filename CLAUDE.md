# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in the **trivalibs** library repository.

trivalibs is a standalone Scala.js library, consumed by downstream projects as a
git submodule (or, eventually, a published artifact). This repo can be opened
directly as its own workspace — `project.scala` at the root is the single source
of truth for lib + tests + examples.

## Build & Dev Commands

```bash
bun run check            # Type-check the library in isolation, from a clean build
bun run test             # Run all tests
bun run examples:build   # Build all examples → examples/out/
bun run examples:watch   # Incremental examples build with file watching
bun run examples:dev     # Bun static dev server for examples
bun run examples:dist    # Assemble the deployable examples site → dist/
bun run deploy           # examples:dist + wrangler deploy → trivalibs-examples.trivialspace.net
bun run publish:local    # Publish a Scala artifact to ~/.ivy2/local
```

`check` deletes the build state before compiling, on purpose. scala-cli's
incremental cache reports stale results often enough to matter: a green check
over genuinely broken code, and failures that no longer exist. `check` is the
correctness gate, so it buys certainty for ~6s; fast iteration is what
`sketch` / `sketch:watch` in the consuming repo are for. **Any comparative
measurement — emitted JS, does-this-still-compile — must clear
`.scala-build` too, or the answer cannot be trusted.**

Examples are deployed manually (`bun run deploy`), not by CI — unlike the
consuming sketch repo, building them needs the Scala toolchain, and library
examples change far less often than sketches. Run it once after an example is
added or changed.

All scripts run from the repo root and pass `src` / `examples` / `test` plus
`project.scala` explicitly as scala-cli inputs (never a bare `.` — that pulls in
unrelated sources and can hang). Never use sbt.

## Standalone workspace

`project.scala` at the root carries every `using` directive for the whole repo
(lib + tests + examples) as a single scala-cli project. Opening `trivalibs/`
directly in VS Code / Metals gives a fully type-checked view of everything.

Downstream consumers exclude this file
(`//> using exclude trivalibs/project.scala`) so their own `project.scala` is
the only config Metals sees when the consumer repo is the workspace root.

## Tests

Tests live in `test/`, picked up automatically by the `.test.scala` suffix. No
per-folder setup file — directives come from the root `project.scala`.

**Adding a new test file**: create `test/<area>/<Name>.test.scala`, extend
`munit.FunSuite`.

```scala
// test/shader/MyFeature.test.scala
package trivalibs.graphics.shader.dsl

import munit.FunSuite

class MyFeatureTest extends FunSuite:
  test("description"):
    assertEquals(...)
```

## Project Overview

A Scala.js library for web and graphics programming, evolving toward a type-safe
painting framework. The long-term vision is a unified CPU/GPU programming model
where Scala types and math work identically on both sides — replacing WGSL
strings with a Scala shader DSL.

The library spans two integrated halves: the **graphics** stack (math, shader
DSL, buffers, geometry, scene, painter) for GPU rendering, and **preact** —
type-safe Preact bindings with signals and an HTML DSL — for the interactive DOM
layer that wraps and drives the graphics canvases (UI controls, overlays, layer
composition on top of the rendered output).

Imperative style throughout: it targets JavaScript APIs directly and avoids
functional patterns and the Scala standard library to minimize overhead. Scala 3
is used for its metaprogramming and contextual abstraction — great tailored DSLs
with minimal low-level overhead.

## Architecture

### Source Layout

- `src/graphics/math/` — Vec2–4, Mat2–4 with three representations each (mutable
  class, immutable tuple, buffer type). Operations via type class traits
  (`Vec3Base[Num, Vec]`, `Vec3Mutable`, `Vec3ImmutableOps`). Also
  `interpolation.scala` (the `Lerp` type class, for code generic in the value it
  interpolates).
- `src/graphics/lib/` — the helper library's CPU side plus the shared
  CPU/GPU extensions: `color.scala`, `coords.scala`,
  `noise/{simplex, extended, worley, extensions}.scala`, and `args.scala` (the
  `FloatArg` / `IntArg` / `VecArg` parameter unions). Mirrors `shader/lib/`
  path for path — see "Lib helpers: three layers" below.
- `src/graphics/shader/` — `ShaderDef` with 7 type parameters. Named tuple type
  params → compile-time WGSL structs + WebGPU layouts. Key files: `types.scala`
  (WGSLType type class), `derive.scala` (WGSL generation), `layouts.scala`
  (vertex/bind group layouts), `builtins.scala`. `shader/lib/` holds reusable
  WGSL function libraries (`noise/` simplex / extended (psrdnoise) / worley,
  `color`, `coords`, `random/hash`, `blur`, `line`).
- `src/graphics/buffers/` — `BufferBinding[T, F]` (CPU↔GPU uniform sync),
  `AttribLayout` + `allocateAttribs` (typed vertex data).
- `src/graphics/geometry/` — mesh / plane / grid / polygon / shape geometry.
- `src/graphics/scene/` — transform / camera / scene graph.
- `src/graphics/painter/` — the Painter abstraction. `webgpu.scala` holds the
  manual WebGPU JS facade traits — painter-internal, not a generic binding.
- `src/utils/` — generic JS helpers: `bufferdata.scala`
  (`StructArray`/`StructRef`, zero-cost typed binary buffers), `numbers.scala`
  (numeric extensions), `js.scala` (interop helpers), `animate.scala`
  (`requestAnimationFrame` lifecycle), `misc.scala`, `random.scala`.
- `src/preact/` — type-safe Preact bindings with signals and HTML DSL; the
  interactive DOM layer that integrates with and drives the graphics layers.
- `examples/` — one example per feature, each with `index.html` + `main.js` +
  `*.scala`. Compiled JS lands in `examples/out/`. Examples double as
  idiomatic-usage docs and ship with the library. Files an example loads at
  runtime (images, data) go in its own `assets/` folder, committed and copied
  into `dist/` by `build-dist.ts`; headless drivers write their output there.
- `examples/example_helpers/` — JS/TS and Scala utils shared between examples
  that are not library usage (e.g. `png.ts` for the headless drivers), kept out
  so each example shows only the library. No `index.html`, so not an example.

### Critical Type-Level Patterns

**Named tuples as schema**: `type Attribs = (position: Vec2, color: Vec4)` —
tuple position = layout index, field name = WGSL variable name, type = WGSLType
mapping.

**Named tuple aliases don't reduce in match types**: The compiler can't prove a
type alias is a `NamedTuple`. Use `given` chains (implicit search) instead of
match types — implicit search unfolds aliases correctly. See
`src/graphics/buffers/attributes.scala` for the pattern.

**`transparent inline` + `summonFrom`**: When a function must return a concrete
`StructArray[F]` derived from a type class, declare return type `Any` with
`transparent inline`. The compiler substitutes the concrete type at each call
site. Used in `allocateAttribs`.

### Design Documents

- `documents/*.md` — Living blueprints for feature designs and implementations,
  for not yet completed features.
- `documents/done/*.md` — Completed designs, for reference and historical
  record, including base designs and decisions that shaped the current codebase.
- `documents/scala-reference/*.md` — Scala language features and conventions,
  for reference when writing new code or reviewing PRs. Advanced recent features
  not yet present in general language model knowledge.
- `documents/rust-painter/*.md` — Reference of the Rust Painter implementation,
  that the Scala port followed. Our scala implementation has mostly reached
  feature parity.

## JS Bundle Size Rules

**This is the library — every byte here is inlined into every downstream
bundle.** Optimization is critical: zero Scala stdlib at runtime, only the type
system and compile-time features. Every runtime construct must compile to
minimal JS:

- **No `enum`**: Use `opaque type Foo = String` with `val` constants in the
  companion. Add `extension (x: Foo) inline def toJs: js.Any` if the opaque type
  must be passed to `js.Dynamic.literal`. Note: `inline val` is not allowed on
  opaque types — use plain `val`.
- **No `scala.collection.*`**: Use `Arr` (`js.Array`), `Dict` (`js.Dictionary`),
  or manual loops. `Dict[V]` works as a string-keyed cache (plain JS object).
  For integer-indexed sparse data, use `Arr[T | Null]`.
- **No `Option`**: Two substitutes in `utils/js.scala`, pick by semantics:
  - `Opt[T]` (`T | Null`) — nullable value. Empty is `null`. Check with
    `.isNull` / `.notNull`, unwrap with `.getOr(default)` / `.get`.
  - `Maybe[T]` (`js.UndefOr[T]`) — "not provided" semantics. Empty is
    `Maybe.Not`. Unwrap with `.orElse(default)` / `.safe`, chain with
    `.orMaybe(...)`. Build conditionally with `maybe(condition, value)`.
- **No `case class` for keys**: Build string keys manually with `s"..."` for
  cache lookups in `Dict`.
- **`.map/.filter/.sortBy` must delegate to JS**: Use `inline` extension methods
  on `Arr` that compile to raw `js.Array` methods — never Scala collection
  traits. Add new helpers as needed.
- **JS-native classes for structured data**: `class Foo(...) extends js.Object`
  preserves field names in JS output with zero overhead. But try pure scala
  classes without js.Object first, to let the scala compiler apply its full set
  of optimisations. If we observe compile size regressions or need runtime field
  names, we can resolve to js.Object extension.
- **`js.Dynamic` / `Obj.literal`** fine internally; user-facing API typed.
- **No Scala/Java exceptions**: never `throw new NoSuchElementException`,
  `IllegalArgumentException`, `RuntimeException`, etc. — Scala.js renders these
  with their Java namespace (e.g. `java.util.NoSuchElementException`) in the JS
  console, leaking implementation details to consumers. Throw a plain JS `Error`
  instead via `throw jsError(message)` (`jsError` from `utils/js.scala` only
  constructs the throwable — always pair it with `throw`). Use `js.Error(...)`
  directly when rejecting a `js.Promise`.
- **Trivalibs helpers everywhere**: `Arr`, `Dict`, `Obj.literal`, `Opt`,
  `Maybe`, `maybe()`.

### Library code vs. example code

Optimisation aggressiveness depends on _where_ the code lives:

- **Library code (`src/`)**: optimise for native JS APIs aggressively. Library
  code is inlined into every downstream bundle, so any Scala-stdlib leak
  multiplies across every consumer. Prefer raw JS calls, `while` loops, and
  native helpers even when more verbose. Add `inline` extension methods so the
  ergonomic call site still compiles to native JS.
- **Example code (`examples/`)**: Scala convenience shorthands are allowed.
  `for`-comprehensions, string interpolation etc. are fine here — readability
  wins, and the cost is local to one example bundle.

### CPU vs GPU cost model

Shader-side and CPU-side helpers are judged by different costs:

- **GPU (shader DSL, `WgslFn`, `shader/lib/`)**: Scala-level boilerplate and JS
  bundle growth are acceptable — wrapper defs with defaults, build-time
  branching — **as long as the emitted WGSL is free of it** (only the needed fn
  calls, no dead code).
- **CPU (`math/`, `graphics/lib/` — the CPU mirrors of shader helpers)**:
  helpers get called in hot
  paths and render loops. The linked JS for a call must be the most direct form
  — ideally one call to a plain scalar kernel. No surviving Scala wrapper frame,
  typeclass / given dispatch, per-call allocation or runtime option check. Get
  convenience (defaults, named params, CPU/GPU-shared extension names) from
  `inline` / `transparent inline` defs with compile-time branching, and verify
  representative call sites in a `jsMode full` link.

Note: `Arr(...)` literals are safe in library code too — `Arr.apply` has
concrete-arity `inline` overloads (0..12) that compile to native `js.Array(...)`
with no varargs pipeline. The library-vs-example split is about Scala-stdlib
constructs that have _no_ native-compiling helper, not about trivalibs helpers.

### Known stdlib-leak traps (verified bundle-size wins)

These all _look_ harmless but pull large Scala-stdlib subtrees. In library code,
avoid them; in `inline` library helpers, route through native JS:

- **`f"...%.1f..."` interpolator** → links the whole `java.util.Formatter`
  ecosystem (~1.9k lines: Formatter, Locale, BigInteger, BigDecimal, every
  `IllegalFormat*Exception`). Use `js.JSNumberOps`'s `.toFixed(n)` instead.
- **Scala varargs (`xs: T*`)** → every call site goes through
  `ScalaRunTime.wrapRefArray` → `ArraySeq` → `toJSVarArgsImpl`, dragging in
  `scala.-Array$` (~4k lines) + WrappedArray + WrappedVarArgs + Tuple2-4. For
  hot-path / frequently-called library constructors, provide concrete-arity
  `inline` overloads (see `Arr.apply`, `Painter.paint`). One-shot
  shader-build-time varargs (e.g. `Block(stmts*)`) may keep varargs.
- **`.mkString(sep)`** → `IterableOnceOps.mkString` + StringBuilder + collection
  traversal. On an `Arr`/`js.Array` use the native `.join(sep)`.
- **`0 until n` / `for i <- 0 until n`** → allocates `Range$Exclusive`; its
  `.foreach` pulls in the `IndexedSeq` iterator machinery → the entire
  `Vector1-6` + `VectorBuilder` + `NewVectorIterator` family (~76k chars in one
  observed chunk). Use a plain `var i = 0; while i < n do … i += 1` loop.
- **`String.contains(char)` / `.nonEmpty`** → `scala.collection.StringOps`. Use
  native `.indexOf(c) >= 0` and `.length > 0`.
- **`js.Dictionary` `apply` / `update` / `dict(k) = v`** → routes through
  `scala.scalajs.js.WrappedDictionary` (a Scala collection wrapper). Use the
  `Dict` extensions `.at(key)` / `.set(key, value)` / `.has(key)` from
  `trivalibs.utils.js` — they compile to raw JS property access. Prefer the
  `Dict` alias over `js.Dictionary` everywhere; the extensions are in scope
  wherever `Dict` is imported.

### Diagnosing bundle size

Temporarily set `jsModuleSplitStyleStr smallestmodules` in `project.scala` to
split the bundle into one module per class — module filenames then reveal
exactly which classes (and which stdlib subtrees) are pulled in. Compare a
`jsMode full` build (DCE applied, the deployment target) against `jsMode fast`
(strict runtime checks, no DCE) to attribute weight precisely. Restore
`fewestmodules` + `full` when done.

Note: `jsMode fast` enables strict runtime type checks (`$uF`, `$isFloat`, etc.)
that `full` mode erases. Code that casts a `Double` to `Float` (or any narrowing
`asInstanceOf`) compiles fine under `full` but throws `ClassCastException` under
`fast`. `full` is the deployment target; treat `fast` as a diagnostics-only
mode.

## Conventions

- **Examples**: Each implementation step gets a new example in `examples/`.
  Previous examples are never deleted and must keep compiling.
- When writing new shader code in examples, prefer the scala shader DSL over raw
  WGSL strings.
- **No props / options classes.** Rust needs a props struct (plus a `Default`
  impl and `..default()` spread) because it has no optional arguments; Scala
  does. When porting such an API, expand the struct into default parameters on
  the method itself, or into overloads — don't carry the wrapper class over.
  E.g. Rust's `LineGeometryProps` became the default parameters of
  `Line.toBufferedGeometry`. Drop fields the Rust original never reads.
- **Lib helpers: three layers, mirrored CPU / GPU.** Helper domains (color,
  coords, noise) exist on both sides with identical object, member and
  parameter names; the package prefix picks the side
  (`trivalibs.graphics.lib.noise.Simplex` CPU,
  `trivalibs.graphics.shader.lib.noise.Simplex` GPU). Per domain:

  | Layer      | GPU (`graphics/shader/lib`)                                                                                                | CPU (`graphics/lib`)                                                                                                                       |
  | ---------- | -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
  | definition | `X.wgsl`: `WgslFn` values, one per code path, every arg explicit — the layer for `.withDeps` and raw WGSL                  | `X.kernel`: plain `def`s over scalar components                                                                                            |
  | object API | ordinary wrapper defs with named, defaulted params; pick the `wgsl` fn at shader-build time                                | `inline def`s; only the optional params (`seed`, `tilingPeriod`, `rot`) are `inline`, to pick the kernel at compile time; erase completely |
  | extensions | one `transparent inline` def per name in `graphics/lib` for **both** sides, branching on the receiver type at compile time | (same def)                                                                                                                                 |

  ```scala
  val bg: Vec3     = Vec3(hue, 0.8, 0.5).hsv2rgb   // CPU: inline → scalar kernel
  val c: Vec3Expr  = vec3(hue, 0.8, 0.5).hsv2rgb   // GPU: → color_hsv2rgb(…)
  n := uv.simplexFbm(octaves = 5, seed = 7.0)      // same call on a Vec2
  ```

  Why one extension def for both sides: Scala forbids default arguments on
  more than one overloaded alternative, so same-named extensions for `Vec2`
  and `Vec2Expr` (or CPU and GPU exported into one prelude) cannot both carry
  defaults. Their parameters take `FloatArg` / `IntArg` / `VecArg` unions
  (`graphics/lib/args.scala`), narrowed per side at compile time with explicit
  conversions (`d.toExpr`, `i.i`). Optional behavior is a parameter
  defaulting to `null` (`seed`, `tilingPeriod`, `rot`), never a suffixed name.
  The full convention set (naming, parameter order, ranges, WGSL names) is in
  `documents/done/cpu-gpu-lib-plan.md` (C1–C14). GPU-only helpers (`Hash`, `Blur`,
  `LineCross`) have the `wgsl` layer and wrappers but no CPU side; `Hash` has
  GPU-only extensions (`x.hash`, `u.hashU`, `u.hash1`), and CPU randomness is
  `utils/random`.

- **Shader DSL: no type-ascription casts in user-facing code.** Write shader
  expressions naturally — `0.5`, `(1.0 - uv.y)`, `band * vec3(...)`,
  `lod.min(4.0)` etc. — without `: FloatExpr` (or similar) annotations. If a
  natural-looking expression fails to compile, that's a **library-API gap**: it
  usually points to a missing overload, conversion, or scalar/vector broadcast.
  Ask the user whether to fix it library-side (add the missing overload /
  conversion / extension) or accept the annotation locally; default is the
  library fix. Common gap shapes:
  - missing left-operand overloads (e.g. `FloatExpr * Vec3Expr` — fixed by
    sibling overloads in the same NumOps extension block, see
    `gpu/float_expr.scala`);
  - missing `Double`/`Int` literal overloads when introducing more parameter
    overloads on the same operator (Scala won't apply implicit conversions
    through an overloaded method set);
  - missing scalar form of an existing vector op (e.g. `.min(0.5)` next to
    `.min(floatExpr)`).

## Documentation

- **`docs/` vs `documents/`**: `docs/` holds **consumable** documentation for
  _using_ the library (the public reference — `docs/guide/` manuals, `docs/api/`
  generated/reference, `docs/skills/`). `documents/` holds **internal**
  feature-planning / contribution docs for _extending_ the library.
- **Doc-comments**: write Scaladoc (`/** … */`) on public APIs — one-line
  purpose, params, and any non-obvious behaviour / gotcha. Comments are stripped
  before JS bundling — document freely. Group-level comments (one per op family)
  are fine where per-method comments would be noise. Use `[[Symbol]]` wiki-links
  only for symbols Scaladoc can resolve in scope.
- **Generate the API site**: `bun run docs`. Output (`docs/api/html/`) is
  gitignored and published by CI; never commit it.

## Scala Conventions

- make use of named tuples @documents/scala-reference/named-tuples.md
- use new given syntax: @documents/scala-reference/given-syntax.md
- never put multiple statements on the same line, even if they are short. We
  don't want semicolons anywhere in Scala.
- the same one-statement-per-line rule applies to WGSL bodies in shader strings
  (`WgslFn.raw` bodies, `ShaderDef` bodies, etc.). Each statement gets its own
  line — no collapsing pairs of statements onto a single line.
- when working with typeclasses, use [T: Typeclass] notation instead of
  [T](using Typeclass[T]) where possible
- When doing floating point math, prefer trivalibs NumExt extensions instead of
  math library methods if possible. I.e. `x.sin` instead of `math.sin(x)`,
  `x.sqrt` instead of `math.sqrt(x)`, etc.
- **Per-frame `update` methods take `tpf`** (milliseconds since the last frame,
  as `animate` provides) — not an absolute timestamp. E.g. `CanvasInput.update`,
  `HoldGesture.update`, `BasicFirstPersonCameraController.update`. This keeps
  stepping consistent, pauses/resumes cleanly with the render loop, and avoids
  wall-clock coupling. Time-since-event _queries_ are the exception (e.g.
  `keyHeldMs`, `pointerDownMs`, `buttonHeldMs`): they take an optional
  `now = js.Date.now()` because they measure real elapsed time.
