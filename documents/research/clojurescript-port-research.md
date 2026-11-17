# ClojureScript port — feasibility research

**Status: pure research, nothing decided, not part of this library's planning.**
If a port is ever started it gets its own repository and its own planning. Question: what would it take to port
trivalibs (painter, shader DSL, math, buffers) from Scala.js to ClojureScript,
keeping the library's goals, in exchange for a real live-coding loop and a REPL
that AI agents can attach to.

Short answer:

- **Feasible.** Nothing in the goals needs Scala specifically. Every
  compile-time derivation has a ClojureScript counterpart, either a macro or
  plain runtime data.
- **The live loop is the one thing Scala.js cannot match.** Measured here: a
  one-constant edit in `rooms/columns` costs 5–9 s, split about evenly between
  scalac and the Scala.js linker. Neither half has a cheap fix (§2).
- **Two things get better than today**: math and noise kernels written once for
  CPU and GPU (today they are written twice), and a shader AST instead of
  strings (shared subexpressions, real error messages).
- **Three things get worse**: no `.` completion on values, no compile-time
  shader type errors, no refactoring safety across 12k lines of sketches.
  Mitigations exist (§8); none is a full substitute.
- **The hardest part is not the language.** It is hot reconfiguration of GPU
  resources, which the painter does not have in any language yet (§6.6). That
  design is needed for live coding either way.
- **Size**: about 40k lines of Scala are affected (library 27k, `src/` 4k,
  sketches 12k), plus 9k of tests. The library would shrink noticeably (§11).

Recommended next step: a time-boxed vertical slice (§13), not a full port
commitment.

---

## 1. What has to survive

From `README.md`, `CLAUDE.md` and the guides, the properties that define the
library:

| #   | Property                                                                                      | Carried today by                                                         |
| --- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| G1  | One language for CPU and GPU; same math names, values and helper libs on both sides           | `*G` traits, `transparent inline` extensions, `FloatArg` unions          |
| G2  | Shaders composed with ordinary functions; constants baked in; no `@group`/`@binding`/structs  | expression types wrapping WGSL strings, built when the sketch runs       |
| G3  | Typed GPU resources: schemas → WGSL structs, layouts, bind-by-name                            | named tuples + `inline` / given-chain derivation                         |
| G4  | Panel as the single image concept; explicit `paint` / `show`                                  | painter runtime (plain imperative code)                                  |
| G5  | Embeddable: canvas passed in, headless via node-webgpu, host does I/O                         | painter runtime, no DOM assumptions                                      |
| G6  | Lean output: JS objects, arrays, TypedArrays; no persistent collections; no per-frame garbage | discipline + `inline`, verified in `jsMode full` bundles                 |
| G7  | Zero-cost typed buffers shared by vertex data and uniforms                                    | `StructArray` / `StructRef` with match-type offsets                      |
| G8  | High signal-to-noise sketch code                                                              | operators, extension methods, named/default args, the comment discipline |

New goals driving the question:

| #   | Goal                                                                          |
| --- | ----------------------------------------------------------------------------- |
| N1  | Edit a tunable, see it in the running sketch, well under a second             |
| N2  | Same for structural edits (a shader body, a new panel), without a page reload |
| N3  | An AI agent attached to the running sketch: evaluate, inspect, change         |

---

## 2. Where the 10 seconds go

Measured on a scratch copy of `sketches/rooms/columns` (Columns + Pillars,
`src/`, `trivalibs/src`), editing `val ColSpace` between runs. Sandboxed, with a
private bloop, so absolute numbers are somewhat worse than a warm `sketch:watch`
on the host; the split is what matters.

| Step                                             | Time                |
| ------------------------------------------------ | ------------------- |
| Cold `package` (compile everything + link)       | 36 s                |
| `scala-cli compile` after the edit (warm)        | 3.3 – 3.6 s         |
| `package` with nothing to compile (link, `full`) | 4.8 – 5.9 s         |
| Edit + `package` in one go (`full`)              | 4.7 s (best case)   |
| Edit + `package` with `--js-mode dev`            | 6.9 – 7.4 s         |
| `package` with nothing changed at all            | 1.7 s (CLI + check) |

What that says:

- Both halves are multi-second. Recompiling one 1000-line, shader-heavy file is
  about 2 s of scalac beyond the CLI overhead; linking is 3–4 s.
- **The linker mode is not the lever.** `dev` (fastLinkJS) was slower than
  `full` here and emits 1.7 MB instead of 955 KB. With `fewestmodules` the
  linker rewrites one big file either way.
- Not tried: `smallestmodules` + `dev` + Vite reloading only the changed
  modules, which the Scala.js blog reports as "10 s to under one" for an
  sbt/Vite setup. It would not remove the scalac half, and `dev` mode throws on
  the `Double`→`Float` casts the buffer code relies on (`CLAUDE.md`, "Diagnosing
  bundle size").
- A full page reload follows every build, because the painter has no way to tear
  down or reuse GPU resources (`dev.scala` header). `devPreserve` exists to
  paper over that.

So the realistic floor in Scala.js is a few seconds plus a reload. A
ClojureScript watch build recompiles the changed namespace and its direct
dependents and pushes them into the running page; a REPL eval sends one form.
Both are routinely sub-second, though **not measured in this research** (§14).

---

## 3. The ClojureScript toolchain

### 3.1 Compiler and build

| Option                          | What it is                                                                                                                                                                 | Fit                                                                                                                                               |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| **ClojureScript + shadow-cljs** | The standard compiler, JVM-hosted, with shadow-cljs as build tool: watch, hot reload with lifecycle hooks, nREPL, ESM output, npm interop, Closure `:advanced` for release | Mature and the default choice. Analyzer API available to macros. `cljs.core` is always present in dev; Closure removes what is unused in release. |
| **squint**                      | CLJS syntax compiled to plain JS with JS-native data (objects, arrays) as the default collections; Vite plugin with HMR hooks; browser nREPL in progress in 2026           | Matches G6 by construction, but young: source maps and REPL were still being worked on in late 2025 / 2026. No CLJS analyzer for macros to query. |
| **cherry**                      | CLJS semantics as ES modules, `cljs.core` as a library; experimental                                                                                                       | Between the two; no advantage here.                                                                                                               |

Recommendation: **shadow-cljs**. Write the library so that its own runtime paths
never touch persistent collections, which keeps a later move to squint open.

### 3.2 What "live" means there

- **Watch + hot reload**: saving a file recompiles that namespace and the
  namespaces that directly require it, loads them into the page, and calls
  `^:dev/before-load` / `^:dev/after-load` hooks. State in `defonce` survives.
  shadow-cljs swaps code only; re-rendering is the application's job.
- **REPL eval**: one form is compiled and evaluated in the page. No hook fires.
  A redefined function takes effect at its next call, because calls go through
  the namespace object. A reference captured earlier (a callback handed to
  `requestAnimationFrame`, a function stored in a data structure) stays the old
  one.
- **Macros** run on the JVM at compile time. They see forms and the analyzer
  environment, never runtime values. shadow-cljs recompiles users of a changed
  macro namespace, but a macro whose expansion depends on data in a _third_
  namespace is not re-expanded when that data changes.
- **Dev builds are unoptimized.** No Closure inlining, no dead-code removal.
  What a macro emits is what runs, in dev and release alike, so performance has
  to come from macros and plain code shape, not from the optimizer. That is the
  same position `inline` puts us in today.
- **Release**: `:advanced` renames properties. `#js` literals with string keys
  are safe; method calls on WebGPU objects need `^js` hints or externs. The one
  existing CLJS WebGPU project found (`kotoba-lang/webgpu`) notes exactly this.

---

## 4. What the Scala type system does today, and what replaces it

| Today (Scala)                                                               | ClojureScript replacement                                                                                 | Verdict                                                           |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| Named-tuple schema → WGSL struct, vertex layout, bind group layout (inline) | Schema as literal data → the same derivation as ordinary runtime code                                     | Simpler. Runs once per shade; cost irrelevant.                    |
| `bind("name" := value)` checked against the schema at compile time          | Checked when `bind!` runs, with the schema at hand; literal keys resolved to slot indices by a macro      | Error moves from compile to first run (milliseconds after eval).  |
| `:=` type-checked against the target                                        | Type inference over a shader AST when the shade is built                                                  | Same move. Messages can be better than WGSL's.                    |
| Operator overloads `a + b * 0.5` on CPU values and on expressions           | `(m/+ a (m/* b 0.5))`: one generic function, dispatching at runtime                                       | Works on both sides for free; prefix syntax; dispatch cost on CPU |
| `inline` kernels, `transparent inline` CPU/GPU extension dispatch           | A `defop` macro: typed source form → specialized CPU kernel + WGSL function                               | **Better**: one definition instead of two.                        |
| `StructArray[F]` with match-type offsets, `set0` / `set1`                   | `defstruct` macro: offsets at compile time, accessors expand to direct typed-array writes, fields by name | Equivalent, and named.                                            |
| `UniformValue` / `UniformLayout` given chains                               | The same `defstruct` layout table with WGSL alignment rules                                               | One mechanism instead of two.                                     |
| Opaque types for enums (`TextureFormat.Rgba16Float`)                        | Keywords in source, turned into strings by macros or a lookup table                                       | Equivalent; typos caught by key validation, not by the compiler.  |
| Default and named arguments                                                 | Options map; a macro front-end turns the literal into a JS object and validates the keys                  | Equivalent at the call site.                                      |
| `.` completion, hover types, rename refactoring                             | Namespace completion, arglists, REPL inspection                                                           | **Worse.** See §8.                                                |

The central observation: most of what the type system derives is needed **once
per resource**, at setup. Only two places are hot: CPU math in the frame loop,
and buffer writes. Those two get macros. Everything else becomes data that is
interpreted when a resource is built, which is also what makes resources
comparable and therefore reusable across reloads (§6.6).

---

## 5. Architecture rule

> **Data and runtime interpretation on the cold path. Macros only where a frame
> pays for it.**

- **Cold path** (creating shades, panels, layouts, WGSL): plain data in, JS
  objects inside. Composable with ordinary functions, inspectable at the REPL,
  hashable.
- **Hot path** (`paint`, binding updates, vector math, vertex writes): JS
  classes with mutable fields, typed arrays, `loop` / `dotimes`, macros that
  expand to straight-line property and array access. No keywords, no maps, no
  seqs, no varargs.
- **Macro front-ends over function back-ends.** `(p/panel p {:format …})` is a
  macro that emits `(p/panel* p #js {…})`, so sketch code reads idiomatically
  and the library never sees a persistent map. The `*` function stays available
  for programmatic use.

---

## 6. API sketches

Sketches, not specifications. Names are placeholders.

### 6.1 Math

CPU values stay what they are in the linked JS today: small classes with mutable
number fields.

```clojure
(deftype Vec3 [^:mutable x ^:mutable y ^:mutable z] …)

;; tier 1 — generic ops. Work on numbers, Vec*, Mat* and shader expressions.
(m/+ a b)   (m/* v 0.5)   (m/dot a b)   (m/normalize v)   (m/mix a b t)
(m/xy v)    (m/xyz v)     (m/smoothstep 0.0 1.0 t)        (m/fit0111 x)

;; tier 2 — monomorphic, allocation-free, for the frame loop.
(v3/add! out a b)   (v3/normalize! v)   (m4/mul! out a b)   (m4/inverse! out m)

;; constructors: one set, CPU values in, CPU values out
(vec3 0.97 0.97 0.96)   (vec4 rgb 1.0)   (mat4/translation offset)
```

- **Tier 1 is the isomorphic layer.** `m/+` checks `number?` first, otherwise
  calls a method that both `Vec3` and the expression node class implement. A
  function written with tier-1 ops, such as
  `(defn fade [t] (m/* t t (m/- 3.0 (m/* 2.0 t))))`, runs on the CPU and builds
  WGSL, with no macro and no second definition. The `Vec3(…)` / `vec3(…)` case
  distinction, `.toExpr`, the `FloatArg` unions and the "CPU value on the left
  does not compile" gotcha all disappear: there is one constructor, and a CPU
  value meeting an expression is lifted at that point.
- **Cost of tier 1 on the CPU**: one `typeof` test and one method call per
  operation, plus an allocation per vector result. Fine for setup code and
  geometry generation, which is where sketches use it. Not for inner loops.
- **Tier 2 mirrors today's `…To(out)` / `…Self` family** under the usual Lisp
  `!` convention.
- **What is lost**: infix. `ctx.in.light * CeilTint + 0.5` becomes
  `(m/+ (m/* light ceil-tint) 0.5)`. Threading (`->`) recovers some of the
  left-to-right reading of method chains:
  `(-> noise (m/fit1101) (m/smoothstep 0.3 0.7))`.

### 6.2 One definition for CPU and GPU: `defop`

Today a helper domain has three layers on each side, and kernels exist twice:
`graphics/lib/noise/*.scala` as scalar Scala (about 2k lines) and
`graphics/shader/lib/noise/*.scala` as raw WGSL strings (about 1.7k lines), kept
in agreement by hand and by the `noise_cpu_gpu` example.

In a Lisp the source form is data, so one macro can emit both:

```clojure
(m/defop hsv2rgb ^vec3 [^vec3 c]
  (let [k (vec4 1.0 (/ 2.0 3.0) (/ 1.0 3.0) 3.0)
        p (abs (- (* (fract (+ (xxx c) (xyz k))) 6.0) (www k)))]
    (* (z c) (mix (xxx k) (clamp (- p (xxx k)) 0.0 1.0) (y c)))))
```

expands to

- a **CPU kernel**: the body with every operator resolved from the inferred
  operand types to its monomorphic implementation;
- a **WGSL function** `fn hsv2rgb(c: vec3f) -> vec3f { … }` with its callees
  recorded as dependencies (what `WgslFn.dsl` does now);
- **one var** `hsv2rgb` that returns a call node when handed an expression and
  runs the kernel otherwise. That is the `transparent inline` extension trick,
  as an `if`.

What this needs:

- A small typed core language inside the macro: arithmetic, the WGSL builtins,
  swizzles, `let`, `if`, bounded loops, calls to other `defop`s. Not arbitrary
  ClojureScript.
- Bottom-up type inference from the parameter hints. WGSL's own rules are
  bottom-up and `let` infers its type, so only signatures need annotations.
- Optionally, later: scalar replacement of vector temporaries on the CPU side,
  which would give allocation-free kernels from vector-style source. The
  hand-written scalar kernels are the fallback.

The inference code is `.cljc` and is shared with the runtime shader type checker
(§6.3). This is the highest-value and highest-risk macro in the port; it is the
first thing to prototype.

### 6.3 Shader DSL

Expressions become **AST nodes** (JS objects: op, type, args) instead of opaque
strings. Shader code is ordinary functions over them, exactly as now.

```clojure
(def fades {:plane 0.3 :top 0.6 :corner 0.3 :arris 0.2})   ; plain data, baked in

(defn side-fade [dist arris? neighbor-facing]
  (let [arris-r (m/* (m/smoothstep 0.0 vis-band neighbor-facing) (:arris fades))
        radius  (m/+ (m/* (m/- arris-r (:corner fades)) arris?) (:corner fades))]
    (m/smoothstep 0.0 (m/max radius 0.001) dist)))

(def wall-shade
  (p/shade p
    {:attribs  WallVertex                      ; a defstruct, §6.4
     :varyings [uv :vec2, world-pos :vec3, edge-info :vec4, tangent :vec3]
     :uniforms [vp :mat4, eye :vec3, samp :sampler]
     :panels   [tex]}
    (s/vert [in u]
      {:position  (m/* (:vp u) (vec4 (:position in) 1.0))
       :uv        (:uv in)
       :world-pos (:position in)
       :edge-info (:edge-info in)
       :tangent   (:tangent in)})
    (s/frag [in u tex]
      (s/let [amb    (s/sample (:tex tex) (:uv in) (:samp u))
              to-eye (m/normalize (m/- (:eye u) (:world-pos in)))
              edge   (wall-edge (:world-pos in) (:edge-info in) (:tangent in) to-eye)]
        (vec4 (m/* (wall-tint (m/y (:world-pos in)))
                   (wall-light amb edge (m/y (:world-pos in))))
              1.0)))))
```

Differences from the Scala DSL, all following from AST-instead-of-string:

- **Stages return values.** `vert` returns a map of outputs, `frag` returns the
  color (or a map for MRT). No `Block(…)`, no `ctx.out.x := …`.
- **`s/let` replaces `LetVec4("amb")` + `:=`.** The macro names the WGSL local
  after the symbol and makes it unique, so "one `val` per WGSL name", the
  `unroll` naming rule and the builtin-shadowing gotcha go away. `s/var`,
  `s/when`, `s/loop`, `s/unroll` cover the statement forms.
- **Shared subexpressions.** A node used twice is emitted once (automatic
  `let`). The "an argument used twice is emitted twice" rule in the guide
  disappears.
- **Stage visibility is inferred** from which stage reads a uniform.
  `VertexUniform[…]` / `FragmentUniform[…]` wrappers are not needed.
- **Type errors** come from our checker at shade build, naming the operation and
  operand types, before WGSL sees anything. `(:uvv in)` on a schema without that
  field is an error at the same point.
- **`(:uv in)`**: `in`, `u`, `tex` are tiny lookup objects built from the
  schema. This runs at shader build only.
- **Schema order.** Vectors of name/type pairs, like a `let` binding vector,
  because map literals lose their order beyond eight entries and attribute order
  is the layout.
- **Raw WGSL** stays as the escape hatch (`s/raw-fn` with declared deps).

Constants keep working the way the guide describes: a closed-over value is baked
into the WGSL. With hot reload that is what makes a tunable edit a shader
rebuild rather than a binding update, which §6.6 has to make cheap.

### 6.4 Buffers

```clojure
(b/defstruct WallVertex
  [position  :vec3f
   uv        :vec2f
   edge-info :vec4f
   tangent   :vec3f])

(let [verts (b/alloc WallVertex 4)]
  (b/set! WallVertex verts i :position x y z)     ; three typed-array writes
  (b/put! WallVertex verts i :tangent some-vec3)  ; from a Vec3
  (b/get  WallVertex verts i :uv out-vec2))
```

- `defstruct` computes stride and offsets at compile time and stores the layout
  in the var's metadata. The accessor macros resolve the struct symbol through
  the analyzer and expand to constant-offset writes. A wrong field name or
  component count is a **compile error**, with the field list in the message. A
  minimal version of this macro pair was written for this research (scratchpad,
  §14); it is about 30 lines.
- Layout in var metadata rather than a JVM-side registry, so it survives
  shadow-cljs's compile cache and server restarts.
- All-`f32` structs (nearly all vertex data) get a `Float32Array` fast path;
  mixed structs fall back to `DataView`. Today everything goes through
  `DataView` (`f32-array-plan.md` is the open item for this).
- The same macro with `{:layout :uniform}` applies WGSL alignment and padding,
  replacing `UniformValue` / `UniformLayout` / `Mat3PaddedBuffer`. One layout
  table then serves the WGSL struct, the `GPUVertexBufferLayout`, the uniform
  buffer and the accessors, which is goal G7 with less machinery.
- Mesh conversion (`toBufferedGeometry`) takes the struct as an argument instead
  of a type parameter.

### 6.5 Painter

The six entities and the panel model carry over unchanged; they are runtime
design, not type-level design.

```clojure
(defonce cam (scene/perspective-camera {:fov 0.9 :near near-plane :far far-plane
                                        :pos (vec3 0.0 eye-height col-space)}))

(p/defsketch columns [p]
  (let [sampler (p/sampler p {:filter :linear :max-anisotropy 16})
        balk-tex (p/bake p {:size (atlas-size atlas) :shapes [(ambience-shape balk-form)]
                            :clear-color ambience-clear})
        eye     (p/binding p :vec3)
        scene-vp (p/binding p :mat4)
        walls   (-> (p/shape p wall-form wall-shade {:cull-mode :none})
                    (p/bind! :samp sampler :eye eye :tex balk-tex))
        scene   (-> (p/panel p {:format :rgba16float :depth-test true :multisample true
                                :clear-color [0.9 0.95 0.99 1.0] :shapes [walls]})
                    (p/bind! :vp scene-vp))]
    (p/on-resize p (fn [w h] (scene/set-aspect! cam (/ w h))))
    (p/on-frame p
      (fn [tpf]
        (input/update! input tpf)
        (p/set! scene-vp (scene/view-proj cam))
        (p/set! eye (.-pos cam))
        (p/paint! p scene)
        (p/show! p scene)))))
```

- `p/bind!` with literal keywords is a macro: names become slot lookups done
  once, not per call.
- `p/bake` = panel + one paint + `freeze`, as a single memoizable step (§6.6).
- `defsketch` exports the canvas-taking entry function (the
  `@JSExportTopLevel("sketch")` contract stays) and registers the body as the
  thing to re-run on reload.
- Headless rendering, `PixelImage` / `PixelFrames`, `showTo`, `atSize` are
  unaffected: plain JS objects and promises on both sides already.
- The WebGPU facade file (`webgpu.scala`) has no counterpart; interop is direct.

### 6.6 Resource management under hot reload — the real work

Today: no `destroy()` anywhere on the public surface (painter setup guide §6),
and a reload throws the page away. For N1–N3 the painter must keep running while
its description changes. Four kinds of edit, in rising cost:

| Edit                                  | What must happen                                              |
| ------------------------------------- | ------------------------------------------------------------- |
| CPU-side tunable read per frame       | Nothing. The loop reads the var.                              |
| Constant baked into a shader          | New WGSL → new pipeline. Buffers, textures, bind groups stay. |
| Geometry constant                     | Affected forms re-uploaded; bakes that drew them re-painted.  |
| Structure (new panel, format, schema) | New resources created, orphaned ones destroyed.               |

Proposed design: **re-run the setup, reuse by content.**

1. On reload, the `defsketch` body runs again from the top.
2. Every factory call is looked up in a registry by a **hash of its
   description**:
   - shade: generated WGSL + layouts (the pipeline cache is already keyed this
     way);
   - form: hash of the vertex and index bytes + topology;
   - panel: its configuration, plus an occurrence counter among equal ones;
   - bake: shade hashes + form hashes + bound values + the content hashes of the
     panels it samples.
3. A hit returns the existing resource. A miss creates one.
4. After the run, whatever was not claimed is destroyed.
5. State that must survive (camera, input, time) lives in `defonce`, outside the
   body. `devPreserve` remains only for full page reloads.

Why this shape:

- **No keys and no in-place diffing.** Identity is content, so inserting a line
  or reordering code changes nothing. Changing `grime-width` produces one new
  WGSL string, hence one new pipeline; every bake whose inputs are unchanged is
  a registry hit and is not repainted. In `rooms/columns` the bakes (ambience
  strips, balk atlas, light, slabs, paintings) are nearly all of the setup cost.
- **It fits the sketch structure that exists.** Sketches are one setup closure
  with loops; they do not need to be restructured into a graph of reactive
  definitions.
- **CPU setup re-runs in full.** Geometry generation is milliseconds to low
  hundreds of milliseconds. If that ever dominates, a dependency-tracked layer
  (tunables as signal-like cells, resources as computed values) can be added on
  top without changing the registry.
- **REPL eval of a single `def`** triggers no hook. Either a `deftune` macro
  (`def` + "request re-run next frame") or an editor binding that evals and
  pokes the sketch covers it. A file save needs neither.

What the painter needs for it, none of it existing today:

- `destroy` on forms, panels, bindings, samplers; reference tracking for
  sharing.
- Content hashing of buffers and descriptors.
- `bake` as a first-class, memoized operation; frozen panels become replaceable.
- **Error containment.** A shader that fails to compile mid-session must leave
  the previous pipeline in place and surface the message: `pushErrorScope` /
  `getCompilationInfo`, an overlay or REPL print. Today a bad shade is a console
  error and a blank canvas, acceptable only because a rebuild follows.
- Unseeded randomness in setup (`randInRange` in the pillar paintings) re-rolls
  on every re-run. Seeds become part of the sketch.

What already points this way: `form.set` reuses buffers when data fits,
`panel.set` reallocates and bumps `bindEpoch`, `BindCache` validates by version
counters, pipelines are cached by key.

**This section is language-independent.** The same registry could be built in
the Scala painter. Combined with tunables supplied from the host (a JSON module
under Vite HMR, or a slider panel), it would give instant scalar tweaking in
Scala without touching the compiler. Structural edits would still cost the
seconds measured in §2.

---

## 7. Performance rules, translated

| Rule today (`CLAUDE.md`)                   | ClojureScript equivalent                                                                                                     |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| No `scala.collection.*`; use `Arr`, `Dict` | No vectors, maps, sets, seqs in library runtime paths; `#js []`, `#js {}`, `aget` / `aset`, `unchecked-get`                  |
| No `for … <- 0 until n`; use `while`       | No `for` / `doseq` / `map` / `range` in library code; `dotimes`, `loop` / `recur`, an own `doarr` macro for JS arrays        |
| No varargs on hot constructors             | Fixed arities only on hot functions; variadic arities allocate                                                               |
| No `Option`; `Opt` / `Maybe`               | `nil` / `undefined` directly; `some?`, `undefined?`                                                                          |
| No `enum`; opaque types                    | Keywords in source, strings at runtime via macro; never keyword comparison in a frame loop                                   |
| `inline` defs erase to a kernel call       | Macros; plain small functions otherwise (the JIT inlines monomorphic ones, the compiler does not)                            |
| No typeclass dispatch on the CPU hot path  | No protocols or multimethods on the hot path; `deftype` methods and direct field access                                      |
| Throw plain JS `Error`                     | `(throw (js/Error. …))`, never `ex-info` in library code (it pulls in maps and printing)                                     |
| Verify in a `jsMode full` link             | Verify the emitted JS of representative call sites in a dev build (it is readable, one file per namespace) and under release |

ClojureScript-specific traps to add:

- A condition the compiler cannot prove boolean is wrapped in a truthiness call.
  Hint returns with `^boolean`, or compare explicitly.
- Calling a function held in a local goes through an arity check. Irrelevant
  outside inner loops.
- `(:key js-object)` does not work; keyword lookup is for CLJS collections.
  Field access on library objects is `(.-field o)` or a generated accessor.
- Printing a library object at the REPL shows `#object[…]` unless it implements
  the print protocol. Worth doing for every entity, since REPL inspection is the
  point.

Numbers are all JS doubles with no `Float` type in between, so the `jsMode fast`
cast failures and the `.toFloat` rules have no counterpart.

Bundle size was not measured. Structure: `cljs.core` is one large namespace that
Closure prunes in release; a library that avoids persistent collections keeps
most of it out, but keywords, the printer and protocol plumbing tend to stay.
The current `rooms/columns` bundle is 955 KB unminified, 128 KB gzipped, as the
reference point.

---

## 8. Discoverability

The honest part. In Scala, `shape.` lists what a shape can do, in context, with
types. ClojureScript tooling (clojure-lsp, clj-kondo, Calva) completes **symbols
in namespaces**, not operations on a value, and its completion for JS interop
members is a known gap.

What can be done by design:

| Measure                                                                                                | Recovers                                                       |
| ------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------- |
| One namespace per entity: `panel/`, `shape/`, `form/`, `v3/`, `m4/`. The alias prefix is the `.` menu. | Listing "what can I do with a panel", with docstrings          |
| No interop in sketch code: every field and method a sketch needs has a named function                  | Completion and docs for everything a sketch touches            |
| Options destructured in the arglist (`{:keys [format width height mips …]}`), shown by signature help  | Named-parameter discovery                                      |
| Macro front-ends validate literal option keys and field names at compile time, with "did you mean"     | Typo detection at save time, inline in the editor              |
| Ship clj-kondo config with the library so its macros lint correctly                                    | Unresolved symbols and wrong arity inside DSL forms            |
| `(p/describe x)` and readable printing for every entity; schemas are data you can look at              | "What is this thing", answered live                            |
| Dev-mode runtime checks (argument shapes, schema match), compiled out of release                       | Early, located errors instead of `undefined is not a function` |
| Headless smoke run of every sketch (node-webgpu is already in the repo)                                | Part of what "it still compiles" gives after a library change  |

What stays lost:

- Completion driven by the type of an expression in the middle of a chain.
- Errors in code paths that have not run.
- Rename and signature changes checked across all sketches by the compiler. With
  26 sketch files today and a library still moving, this is the cost that grows
  with time.
- The `: FloatExpr`-free guarantee cuts both ways: there are no ascriptions to
  get wrong, and none to read.

---

## 9. AI agents on the REPL

Two MCP bridges exist for this today:

- **clojure-mcp** (Bruce Hauman): a standalone MCP server connected to a running
  nREPL; eval plus structure-aware editing tools. Alpha-stage by its own
  description. shadow-cljs exposes an nREPL server that can be switched into the
  browser REPL; whether clojure-mcp drives that path smoothly was not verified.
- **Calva Backseat Driver**: an MCP server inside VS Code that evaluates through
  Calva's connected REPL, per project, with evaluation off by default.

What it would change for work on this codebase, concretely:

- **The feedback loop for the agent.** In this repo an agent's check of a
  library change is a sandboxed scala-cli run with a cold private bloop, about a
  minute, and sketches are deliberately left to the human to run. With a REPL
  the agent evaluates the changed function and calls it.
- **Inspection instead of inference.** Generated WGSL, a shade's layout, a
  panel's size and format, the registry's contents, a binding's current value:
  all are one eval away, on the real running sketch.
- **Eyes.** The painter can already read a panel back into a `PixelImage`
  (`showTo`). A `(p/snapshot panel)` that writes a PNG lets the agent look at
  any intermediate panel of the live sketch, not just the final frame. That
  pairing (eval + readback) is the strongest argument in this section, and half
  of it exists.
- **Types as agent feedback go away.** Today the compiler tells an agent about
  every call site it broke. On a REPL it learns only about the code it ran.
  Agents compensate by running more, which the REPL makes cheap, but it is a
  different safety model, not a free upgrade.

A partial equivalent on the Scala side: expose the painter on a debug global and
attach a browser-automation MCP. That gives inspection and screenshots of the
running sketch, not code changes; those still go through the compiler.

---

## 10. Prior art

| Project                             | What it is                                                                                      | Takeaway                                                                                                                      |
| ----------------------------------- | ----------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `thi-ng/geom`, `thi-ng/shadergraph` | CLJ/CLJS geometry and a GLSL function-dependency library; WebGL era                             | Dependency-tracked shader function libraries in CLJS worked. The author's later work moved to TypeScript (`thi.ng/umbrella`). |
| `kovasb/gamma`                      | GLSL as ClojureScript data: expressions are maps, programs compile to GLSL strings; 2015, alpha | Precedent for the AST-as-data approach of §6.3.                                                                               |
| `Ella-Hoeppner/hollow` + `kudzu`    | WebGL2 graphics library with a CLJS shader DSL; work in progress                                | Closest in spirit. Small, WebGL-only.                                                                                         |
| `Ella-Hoeppner/easl`                | A Lisp that compiles to WGSL, written in Rust, with its own CPU runtime and LSP                 | The same author left ClojureScript for a dedicated typed language. Worth reading for how a typed Lisp maps onto WGSL.         |
| `kotoba-lang/webgpu`                | WebGPU driven from EDN in ClojureScript; WGSL authoring still on its roadmap                    | Confirms the `:advanced` / externs caveat. Not a base to build on.                                                            |
| `squint`, `cherry`                  | Alternative CLJS-syntax compilers (§3.1)                                                        | A possible later target if library runtime stays collection-free.                                                             |

No maintained ClojureScript WebGPU library with a shader DSL was found. The port
would be on its own, as the Scala library is.

---

## 11. Scope

| Area                                                          | Scala lines | Port character                                                                                                        |
| ------------------------------------------------------------- | ----------- | --------------------------------------------------------------------------------------------------------------------- |
| Painter (`painter/`, without the facade)                      | ~5,300      | Mechanical translation of imperative code, plus the redesign of §6.6. The facade (350 lines) disappears.              |
| Shader types, derivation, layouts, DSL plumbing               | ~2,300      | Rewritten as runtime data + AST; expected much smaller.                                                               |
| Math, CPU and GPU (`math/`)                                   | ~6,600      | Rewritten. Overload matrices and the three-representation trait stack collapse into an op table and two tiers.        |
| Helper libs (`lib/`, `shader/lib/`: noise, color, hash, blur) | ~5,100      | Rewritten once via `defop` instead of twice; raw-WGSL kernels can be carried over as-is first.                        |
| Geometry (`geometry/`)                                        | ~2,600      | Straight port; type parameters become struct arguments.                                                               |
| Buffers (`bufferdata`, `buffers/`)                            | ~1,150      | Replaced by `defstruct`; a few hundred lines.                                                                         |
| Scene, input, utils, dev                                      | ~2,800      | Straight port. `utils/js.scala` (`Arr`, `Dict`, `Opt`) has no counterpart.                                            |
| Preact bindings                                               | ~900        | Dropped (not a focus per README).                                                                                     |
| `src/` sketch utilities (bloom, mirror, bake, room)           | ~3,700      | Straight port on top of the new API.                                                                                  |
| Sketches (26 files)                                           | ~12,300     | Rewritten by hand or agent, one at a time; the look has to be re-verified by eye.                                     |
| Tests                                                         | ~9,400      | `cljs.test`; the headless painter tests keep their node-webgpu setup. Math tests gain value: no compiler behind them. |
| Examples                                                      | ~3,900      | Rewritten; they double as the documentation.                                                                          |
| Guides, CLAUDE.md rules, write-sketch skill                   | —           | Rewritten.                                                                                                            |

The rust-painter → Scala port is the reference for effort: the runtime model
transferred, the API surface was redesigned around the host language.

---

## 12. Risks and open questions

| Risk                                                                                                   | Why it matters                                                      | How to find out                                          |
| ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------- | -------------------------------------------------------- |
| `defop` typed core: is the inference small and predictable enough, are its errors readable?            | G1 rests on it; without it, kernels are written twice again         | Prototype with `hsv2rgb`, simplex 2D and fbm             |
| Prefix math in long shader bodies: does `rooms/columns` still read well?                               | G8; this is taste, and only real code answers it                    | Port the Columns wall + floor shades by hand             |
| Hot reconfiguration (§6.6): hashing cost, destroy ordering, bind-group invalidation, error containment | N1/N2 rest on it; it is new design in any language                  | Build registry + `bake` in the slice; edit tunables live |
| Tier-1 dispatch cost in geometry generation (line2d, mesh building)                                    | G6; setup re-runs on every reload, so setup speed is now loop speed | Benchmark a ported `line2d` against the Scala build      |
| Loss of static checking across sketches                                                                | Grows with the sketch count                                         | Headless smoke run; judge after porting three sketches   |
| `:advanced` with WebGPU: externs, renaming                                                             | Release builds only; could force `:simple` and a larger bundle      | Release-build the slice early                            |
| Bundle size against the current 128 KB gzipped                                                         | G6                                                                  | Same                                                     |
| shadow-cljs reload granularity with macros that read other namespaces' data                            | Stale expansions are silent                                         | Keep macro inputs in the form itself or in var metadata  |
| Agent REPL bridges are young                                                                           | N3 depends on third-party alpha tooling                             | Try clojure-mcp and Backseat Driver against the slice    |
| Second rewrite in a row (TS → Rust → Scala → CLJS)                                                     | Sketch work pauses during a port                                    | Time-box; keep Scala as the working tree until parity    |

---

## 13. Options and recommendation

**A. Stay on Scala, shorten the loop.** Try `smallestmodules` + Vite module
reload; build the §6.6 registry in the Scala painter; feed tunables from the
host. Result: instant scalar tunables, a few seconds for code edits, no page
reload. Keeps types, tooling and all existing code. Does not deliver N2 in full
or N3.

**B. Port to ClojureScript.** Delivers N1–N3. Costs §8 and §11.

**C. Vertical slice first, then decide (recommended).** Build, in a separate
directory, the thinnest path through every risky part:

1. Tier-1 math for `Float`/`Vec2–4`/`Mat4`, as CPU values and AST nodes.
2. `defop` for one color helper and 2D simplex + fbm.
3. Shader AST → WGSL with type checking, `s/let`, uniforms, one texture.
4. `defstruct` with vertex and uniform layouts.
5. Painter: shade, form, shape, layer, panel, binding, `paint` / `show`, on the
   content registry with `bake` and `destroy`.
6. Port `base-triangle`, one layer/bake example, and the wall + floor of
   `rooms/columns`.
7. shadow-cljs watch + REPL + one MCP bridge + `snapshot`.

Then answer, from use rather than from this document: does a tunable edit land
in under a second without a reload; does the Columns shader code still read
well; how much does missing completion hurt after a week; is the agent loop as
good as hoped. Item 5 is reusable for option A if the answer is no.

My read: the port is justified **if** live coding and the agent REPL are primary
goals rather than conveniences, because §2 shows Scala.js cannot get there and
§6 shows the DSL goals do not depend on Scala. The loss in §8 is real and
permanent, so it should be experienced on real sketch code before 40k lines
move.

---

## 14. Not verified in this research

- **ClojureScript build and reload times.** A shadow-cljs spike project
  (`deftype` vector, generic `add`, the `defstruct` / accessor macros of §6.4)
  was written but could not be run: the sandbox blocked the JVM's dependency
  download, and a workaround was denied. The files are in the session scratchpad
  under `cljs-spike/`. Sub-second reload is the commonly reported figure, not a
  measurement on this machine.
- Emitted JS for the macro expansions and tier-1 dispatch; bundle size under
  `:advanced`.
- Whether Closure ships WebGPU externs.
- clojure-mcp against a shadow-cljs browser REPL.
- The Scala `smallestmodules` + Vite experiment.
- Host-side timings: §2 was measured in the sandbox with a private bloop.

---

## Sources

- [shadow-cljs: hot reload in ClojureScript](https://code.thheller.com/blog/shadow-cljs/2019/08/25/hot-reload-in-clojurescript.html)
- [shadow-cljs user guide](https://shadow-cljs.github.io/docs/UsersGuide.html)
- [Scala.js blog: faster development with front-end tooling](https://scala-lang.org/blog/2023/04/18/faster-scalajs-development-with-frontend-tooling.html)
- [squint](https://github.com/squint-cljs/squint),
  [cherry](https://github.com/squint-cljs/cherry)
- [clojure-mcp](https://github.com/bhauman/clojure-mcp),
  [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver)
- [kovasb/gamma](https://github.com/kovasb/gamma),
  [thi-ng/shadergraph](https://github.com/thi-ng/shadergraph),
  [thi-ng/geom](https://github.com/thi-ng/geom)
- [Ella-Hoeppner/hollow](https://github.com/Ella-Hoeppner/hollow),
  [kudzu](https://github.com/Ella-Hoeppner/kudzu),
  [easl](https://github.com/Ella-Hoeppner/easl)
- [kotoba-lang/webgpu](https://github.com/kotoba-lang/webgpu)
- [clojure-lsp capabilities](https://cljdoc.org/d/com.github.clojure-lsp/clojure-lsp/2026.07.06-14.34.19/doc/capabilities)
