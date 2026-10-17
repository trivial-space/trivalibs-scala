# TODOs that don't require full feature documents.

## Panel / Textures

### 🔄 `with_static_texture_data(bytes)` on Panel

> Now planned as Milestone 2 of
> [headless-painter-and-texture-sources-plan.md](headless-painter-and-texture-sources-plan.md)
> (`Pixels` / `TextureSource` panel sources). Remove this entry when M2.0 lands.

**Gap:** Rust's `Panel::with_static_texture_data(bytes)` is the canonical way to
load an image / baked texture into a panel's render target. The Scala Panel has
no direct equivalent — callers must build a `GPUTexture` with usage
`COPY_DST | TEXTURE_BINDING` via the raw device and bind it by hand.

**Required changes:**

- Add a `staticTextureData` param (or a dedicated
  `setStaticTextureData(bytes, format, width, height)` method) on `Panel` /
  `painter.panel(...)`.
- Internally: create the `GPUTexture` with `COPY_DST | TEXTURE_BINDING`, call
  `queue.writeTexture`, and wire the texture view as the panel's output so
  downstream `Layer`s sampling the panel see the baked pixels.
- Default format / dimensions from `bytes` or require explicit args — match
  Rust's signature.

**Doc follow-up:** Once added, remove the gap bullet from
[scala-port-comparison.md §12](rust-painter/scala-port-comparison.md) and add
the param row to the Panel table in §3.

**Priority:** Medium — unblocks any example that needs a baked image texture
(image-based sketches, reference LUTs, etc.).

---

## GPU resources

### 🔄 Explicit GPU resource freeing across the painter

Independent, tangential workstream — a generally useful library capability for
any sketch that allocates transient GPU resources (e.g. the texture baker's
per-bake `model` binding). trivalibs is still work-in-progress, so filling this
gap is fair game. **Not scheduled** — prebaking will not free its binding for
now; capture the design here until it's worth doing.

Add a public `destroy(): Unit` to the painter classes that own GPU resources:

- **`Form`** (`form.scala`) — destroy the `vertexBuffer` / `indexBuffer` of
  every entry in `buffers`. The teardown already exists internally (it runs on
  `set` when an upload outgrows its allocation); expose it as a public sweep
  over all buffers.
- **`BufferBinding`** (`buffers/…`) — destroy the underlying `GPUBuffer` (the
  WebGPU facade already has `GPUBuffer.destroy()`, `webgpu.scala:139`). This is
  the one the baker would actually use (free the transient `model` binding after
  `p.paint`).
- **`Panel`** (`panel.scala`) — destroy color / pong / msaa / depth textures;
  reuse the existing texture-teardown logic (`panel.scala:133-134, 450-458`).

Each class carries a `private var destroyed = false` flag:

- `destroy()` is **idempotent** (no-op if already destroyed) and sets the flag.
- Use-after-destroy **throws** `throw jsError("<Resource>: use after destroy")`
  (plain JS `Error`, per the trivalibs no-Scala-exceptions rule) from the public
  use/mutation entry points — `Form` render/buffer access, `BufferBinding`
  `set`/`update`/bind, `Panel` paint/bind/show/`binding`. The guard is a single
  boolean branch.

**Needs deeper thought — who owns which bindings:** the simple "destroy what I
hold" rule above is only correct for resources the object allocated itself. The
painter has several cases where ownership is conditional:

- **`Shape` / instances** allocate and manage their **own** bindings when handed
  a **raw value** instead of a pre-existing `BufferBinding`. Those
  self-allocated bindings are owned by the shape/instance and are candidates to
  destroy on its teardown; a binding **passed in** by the caller is not —
  destroying it would pull the rug out from under the owner. So `destroy()` must
  track provenance (allocated-here vs. supplied) and only free the former.
- **`Panel` bindings** (not only its textures) fall under the same rule — a
  panel similarly owns bindings it created from raw values but not ones it was
  given.

So the full design needs an ownership/provenance flag per binding before any
class can safely cascade `destroy()` into its bindings. Until that's worked out,
keep `destroy()` scoped to unambiguously-owned resources.

Feasibility / cost notes:

- Guarding only the **public** entry points (not every internal access) keeps it
  cheap and avoids per-inner-loop checks. If a guard would land on a genuine hot
  path, limit it to the outermost call.
- Bundle-size discipline applies (library code): boolean field +
  `throw jsError(...)`, no Scala exception types, no stdlib.
- A small test can assert `destroy()` is idempotent and that a guarded call
  after destroy throws.

**Priority:** Low — transient resources are negligible for the handful-of-bakes
scale; revisit when a sketch churns GPU resources at runtime.

---

## Painter

### 🔄 Precompute the invariant parts of the pipeline cache key

**Gap:** `Painter.getPipeline` builds its `Dict` lookup key from scratch on
**every draw call** — a nested `blendKeyStr(...)` string, a `formats.join(",")`,
then a nine-part interpolation into a ~60-char string that then gets hashed. Two
of those three allocations are recomputable-once values:

- `blendKeyStr(bs)` is a pure function of an immutable `BlendState` (a
  `js.Object` of `val`s). Compute it lazily on first use and cache it on the
  `BlendState` itself.
- the `formats.join(",")` is stable per render target — cache it on the `Panel`
  (or wherever the format `Arr` is owned) rather than re-joining per shape.

That leaves a single interpolation of ready-made pieces per draw call, with no
change to the cache's structure or semantics.

**Deliberately not doing (yet):** memoizing the resolved pipeline on the `Shape`
(all nine key inputs are stable per shape/target pair, so nine cheap identity
comparisons could skip the string entirely, with the `Dict` still the
authoritative fallback), and the endgame of a bit-packed integer key over a
`js.Map[Int, _]` — that one needs interned numeric ids for `BlendState` and for
format sets. Both are real wins only at thousands of draw calls per frame.

**Priority:** Low — at current draw-call counts (tens of shapes per frame) the
key building is far below measurable. Revisit if `getPipeline` ever shows up in
a profile, e.g. if a sketch moves a large paint loop from `init` into the frame
loop.

**Already done:** the lookup itself is a single `at` + undefined check, not the
`has` + `at` pair it used to be.

---

## Uniforms

### 🔄 Integer uniforms — `Int` / `UInt` / `IVec*` / `UVec*` cannot be bound

**Note only, no plan.** All eight integer types have a `WGSLType`
([shader/types.scala:260-323](../src/graphics/shader/types.scala#L260-L323)), so
they can be written in a uniform schema — but none has a `UniformValue`
([buffers/binding.scala:46-104](../src/graphics/buffers/binding.scala#L46-L104)
covers `Float`, `Double`, `Vec2-4`, `Mat2-4` only), so nothing can actually bind
one. Counts and indices therefore travel as `Double`/f32 and get `.toI32`'d in
the shader, which is what every consumer does today.

If a sketch ever needs a genuine integer uniform, **add all forms at once** —
scalar and vector, signed and unsigned — rather than the one shape that happened
to be needed; a half-covered set is worse than none. `UniformArray` of them then
follows from one `UniformArrayElem` instance each, under the lane rule in
[done/uniform-scalar-arrays-plan.md](done/uniform-scalar-arrays-plan.md) (4
lanes for the scalars, 2 for `IVec2`/`UVec2`, 1 for the rest).

**Priority:** Low — no consumer, and the f32 route works.

---

## Shader DSL

### 🔄 Local arrays — indexable `var` inside a shader body

Lifted out of [uniform-arrays-plan.md](done/uniform-arrays-plan.md) as its
milestone 3 when milestones 1 (`UniformArray[T, N]`) and 2 (loop primitives)
shipped. **Deferred, and not committed to** — the API shape below is decided,
the decision to build it is not.

**Gap:** there is no way to declare an indexable `var` inside a shader body.
`UniformArray` is a binding — CPU-written, read-only in the shader — and a local
is a single scalar/vector/matrix. So anything needing _indexed mutable scratch_
is unreachable: sorting a small set, gather-then-scan, a running history of the
last N taps, any loop whose accumulator is per-slot rather than a single value.
It is also what stops `sketches/textures/lines/` from being loop-shaped at all:
its three lines are three separate `VarVec4` locals because `lines[i]` does not
exist.

**Shape.** WGSL wants `var lines: array<vec4<f32>, 3>;` at function scope
(zero-initialised by default). The DSL pieces line up:

```scala
val lines = VarArray[Vec4, 3]("lines")   // ad-hoc, like LetVec4("cur")

Block(
  lines.decl,                            // var lines: array<vec4<f32>, 3>;
  lines.set(0, vec4(…)),                 // element write
  …,
  loop(0, 3): i =>
    col := col.mix(lines(i).xyz, …),
)
```

**Required changes:**

- **Reads reuse `ArrayExpr[E]`** — the indexing surface milestone 1 shipped,
  constant and `IntExpr` forms both. That type is already
  address-space-agnostic, which was the one thing milestone 2 had to preserve;
  it did.
- **Writes are new.** `a(i)` returns the element expression `E`, which carries
  no `:=`. Either a method (`a.set(i, value): Stmt`) or an assignable accessor
  (`a.at(i) := value`, returning an `AssignTarget`). The second matches how
  `ctx.out.color := …` already reads; the first is one fewer concept.
- **`Var` only, not `Let`/`Const`.** Whether WGSL permits a runtime index into a
  _value_ (non-reference) array is to be verified with `naga` rather than
  assumed; restricting local arrays to `var` sidesteps it.
- The local marker's name (`VarArray` / `LocalArray[T, N]`) and whether it is a
  second marker or one array type with an address-space parameter can be settled
  when it is built.

**What it costs, honestly:** an **explicit declaration statement**, which no
other local needs. Every local today declares itself on first `:=`
(`expr.scala:64-68`); an array has no single first assignment, so
`var lines: array<vec4<f32>, 3>;` has to be emitted on its own — one extra
statement in the `Block` and nothing else, and the only local that needs one,
worth a line in the docs. Plus one thing to document rather than discover:
**dynamically indexing a function-local array is a known performance cliff** —
it can push the array out of registers into scratch memory on some GPUs.
Constant indices are free; a loop-variable index is not always.

**Priority:** Low — nothing depends on it, and a capability waits for a real
consumer here. The two `sketches/textures/` bodies are not it (they should stay
unrolled). Revisit when a shader genuinely needs indexed mutable scratch.

---

## JS helpers

### 🔄 `Arr.++=` delegating to native `push`

`Arr` has an inline `+=` that compiles to `push` (`src/utils/js.scala`), but no
`++=`. So `arr ++= x` silently falls back to Scala.js's `Growable.++=`, which
goes through collection machinery, and on an `Arr[String]` appends a string
argument **character by character**. Add inline overloads on `Arr`: one
appending another `Arr` (native `push(...other)` / a push loop), and one for a
single element as a `+=` alias, so the natural builder spelling
(`sb ++= "line"`) is safe and allocation-free. Noticed while writing the WGSL
generators in `shader/lib/noise/worley.scala`, which use `push` for now.

**Priority:** Low — convenience; `push` / `+=` work today.

---

## ✅ Completed

### ✅ CPU noise and CPU / GPU mirrored lib helpers

Done in [`done/cpu-gpu-lib-plan.md`](done/cpu-gpu-lib-plan.md): simplex (2D–4D,
fbm, torus), extended (psrdnoise, 2D / 3D, tiling / rotation / gradient, fbm)
and worley (2D / 3D) on CPU and GPU, one hashed optional `seed`, normalized
fbms, `graphics/lib` (CPU + shared `transparent inline` extensions) mirroring
`graphics/shader/lib`, and the C1–C14 lib conventions.

---

### ✅ Random — missing `Random` helpers from `trivalibs_core`

[trivalibs/src/utils/random.scala](../src/utils/random.scala) now carries
`randInt` / `randIntInRange`, `randBool`, `randSign`, `randNormal01` /
`randNormal11`, `randVec2/3/4()` plus scalar- and per-component-bounded
`randVec*InRange`, and the `Arr` extensions `.pick()`, `.shuffle()` (in place,
Fisher–Yates) and `.shuffled()` (copying). Rust's `rand_in_unit_sphere` /
`rand_vec3_unit` are not ported — no consumer yet.

### ✅ CPU color / coordinate conversions mirroring the shader lib

[math/cpu/color.scala](../src/graphics/math/cpu/color.scala) and
[math/cpu/coords.scala](../src/graphics/math/cpu/coords.scala) — CPU `hsv2rgb`
(+ `Smooth` / `Smoother`), `hsl2rgb`, `rgb2hsv`, `rgb2hsl`, `polarToCart`,
`cartToPolar`, same IQ formulation as the WGSL versions. Both sides are receiver
extensions with identical names (see the CPU/GPU mirroring note above); the
shader lib gained matching `Vec3Expr` / `Vec2Expr` extensions.

### ✅ Math — `quadraticBezier` / `cubicBezier` on Vec2 / Vec3

Ported from Rust `math/interpolation` as statics on the CPU immutable-ops
traits: `Vec2.cubicBezier(t, a, c1, c2, b)`, matching Rust's
`Vec2::cubic_bezier` argument order. Available on `Vec2`/`Vec3` and their
`*Tuple` siblings.

---

### ✅ Form — multiple geometry buffers + grow-only buffer reuse

`Form` now holds an `Arr[FormBuffers]`, each record tracking
`vertexBuffer/indexBuffer` with separate `maxSize` (allocated) and `currentSize`
(in use). Uploads reuse the existing `GPUBuffer` while the new data fits and
only reallocate when it grows; the draw binds the live slice, so a smaller
upload can't leak stale geometry. `form(geometries = …)` /
`form.set(verticesAll = …)` take an `Arr` of buffers, drawn in sequence by one
shape sharing pipeline, bind groups, topology and front face (Rust
`Form::update_all` / `currently_active_buffers`). Verified by
`examples/random_lines`.

### ✅ Documentation — `painter.shape` arg order in docs

Doc note about the arg-order flip has been removed now that the API and all
examples are updated to `painter.shape(form, shade)`.

---

### ✅ API Design — `painter.shape(shade, form)` arg order should match Rust

Swapped to `painter.shape(form, shade)` in `painter.scala` and `shape.scala`.
All example call sites updated.

---

### ✅ Examples — `painter.form().set()` / `.panel().set()` / `.layer().set()`

Collapsed chained `.set()` calls into the factory constructor across all example
files. Pattern: `painter.form().set(vertices = v)` →
`painter.form(vertices = v)`.

---

### ✅ API Design — singular param shortcuts for plural `Arr` params

Added `shape`, `layer`, `format` singular params to `Panel.set()` and
`painter.panel()`. Plural wins when both are supplied. All single-item `Arr(x)`
call sites in examples simplified to use the singular form.

---

### ✅ Math — `IntExt` dedicated Int extension trait

Added `trait IntExt[P]` with `min`, `max`, `clamp`, and step predicates to
`trivalibs/src/utils/numbers.scala`. `given IntExt[Int]` provides the CPU
instance. Standalone `Int` extensions for `abs` and `sign` co-located.

---

### ✅ Math — vector overloads for `fit0111` / `fit1101`

Added `fit0111` / `fit1101` to `Vec2/3/4ImmutableOpsG` (shared trait), CPU
`Vec2/3/4ImmutableOps` (componentwise scalar delegation), and all three GPU
`Vec_Expr` given instances (direct WGSL `v * 2.0 - 1.0` / `v * 0.5 + 0.5`).
Port-comparison doc updated.
