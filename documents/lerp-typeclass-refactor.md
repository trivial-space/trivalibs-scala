# Lerp as the one interpolation type class

## Status (2026-10-17)

**Implemented, not archived.** Steps 1 and 2 landed in trivalibs `7278824`. Step
3 is still open, and deciding it is what keeps this plan in `documents/` and out
of `documents/done/`. See [What comes next](#what-comes-next).

| step                                                    | state                               |
| ------------------------------------------------------- | ----------------------------------- |
| 1. delete `NumExt.lerpIn`                               | done                                |
| 2. `LerpBy` + concrete instances + `lerpIn`             | done, with deviations (below)       |
| 3. move `numbers` / `interpolation` to `trivalibs.math` | open, not decided                   |
| follow-up: linear-algebra indirection audit             | not started, separate investigation |

Covered by `test/math/LerpIn.test.scala`. User-facing usage is in
`docs/guide/shader-dsl-guide.md`.

### Deviations from the original plan

- **No derived scalar instance.** Step 2 originally derived `LerpBy[P, P]` from
  `NumExt`. That parameterized given put an anonymous class at every call site,
  so it was replaced by concrete `doubleLerp` (`LerpBy` companion) and
  `floatExprLerp` (`FloatExpr` companion). See
  [Zero-cost givens](#zero-cost-givens).
- **No generic vector instances.** The plan had a
  `[V] => Vec*Base[V] => Vec*ImmutableOps[V]` instance for the tuple and buffer
  representations. It is gone. The tuples got concrete instances in `LerpBy`'s
  companion, and the buffer representations (`StructRef[Vec*Buffer]`) have no
  `Lerp`, as before the refactor.
- **Instance `lerp` methods are `inline def`.** Without that, the scalar path
  stays a module call and does not fold into arithmetic.
- **An extra `FloatExpr.lerpIn` overload takes `LetExpr[T]` bounds.** Shader
  locals (`LetFloat`, `Var*`, `Const*`) are subtypes of their expression type,
  and `LerpBy` is invariant, so the generic form inferred `T` as the local's
  type and found no instance.
- **A new step-3 blocker.** The tuple instances refer to `Vec*ImmutableOps`, so
  `interpolation.scala` now imports `graphics.math.cpu`. See
  [Step 3's open question](#step-3s-open-question-implicit-scope).

## Motivation

Before this refactor, interpolation was spread over three unrelated definitions:

| where                               | what                                 | parameter type          |
| ----------------------------------- | ------------------------------------ | ----------------------- |
| `utils/numbers.scala` (`NumExt`)    | `mix`, `lerp`, `lerpIn` on scalars   | `P` (the scalar itself) |
| `graphics/math/interpolation.scala` | `Lerp[T]` — the abstracted-over form | `Double`                |
| `graphics/math/vec*.scala` (ops)    | `mix`/`lerp` on every vector width   | `Num` or `Vec`          |

They overlap, and the overlap is not benign: **an extension method is resolved
by name from a single source**, so two `lerpIn` definitions never overload with
each other — one silently hides the other. Measured precedence (Scala 3.8.4):

1. lexically nearest wins (a local definition beats any import);
2. among wildcard imports of plain extensions, the **later import** wins — no
   ambiguity error, just a silent winner;
3. a plain imported extension outranks one reached through a **given in scope**
   (`NumExt`), regardless of import order;
4. once a source is picked there is **no fallback** — arguments that don't fit
   are a type error, not a retry against the hidden definition.

That made `NumExt.lerpIn` dead code for `Double` and `FloatExpr` receivers: the
definitions in `interpolation.scala` and `gpu/float_expr.scala` hid it.
Documenting that would have been worse than removing it, so it was removed.

A second, sharper hazard sits on the GPU side. `Double` converts to `FloatExpr`,
so any `FloatExpr` overload that accepts **CPU** `Vec*` bounds is also
applicable to a CPU call: `0.5.lerpIn(Vec3(0), Vec3(1))` resolves into the GPU
overload set and returns a `Vec3Expr` where the author wrote CPU math. Silent,
not an error. Hence each receiver keeps its bounds in its own domain.

## Design

### `LerpBy[T, P]`, with `Lerp[T]` as the CPU alias

`Lerp[T]`'s parameter is a `Double`, and that was a deliberate choice, not an
oversight: on Scala.js every number is a double, `Float` only adds conversion
boilerplate on the Scala↔JS boundary, and CPU code here does not use it. So
hardcoding `Double` cost nothing — until the shader side, where `t` is a
`FloatExpr` varying per fragment. **The second type parameter exists for
`FloatExpr` alone.** Splitting the value type from the parameter type covers
both:

```scala
trait LerpBy[T, P]:
  extension (a: T) def lerp(b: T, t: P): T

type Lerp[T] = LerpBy[T, Double]   // every existing `[T: Lerp]` site is unchanged
```

Instances:

| instance                                    | covers                                                                                  |
| ------------------------------------------- | --------------------------------------------------------------------------------------- |
| `doubleLerp`, `floatExprLerp`               | the two scalars that exist in practice — concrete, not derived (see "Zero-cost givens") |
| `lerpInstance` in `Vec2`/`Vec3`/`Vec4`      | the CPU vector classes — concrete modules, no allocation                                |
| `vec2TupleLerp` / `vec3…` / `vec4…`         | the tuple representations — concrete, in `LerpBy`'s companion                           |
| `unitLerp`                                  | `Line[Unit]`                                                                            |
| `lerpInstance` in the `Vec*Expr` companions | GPU vectors with a `FloatExpr` parameter                                                |

The buffer representations get no instance (see "Zero-cost givens").

The GPU instances live in the **`Vec*Expr` companions**, not in `LerpBy`'s, so
`graphics.math` never has to import `graphics.math.gpu`. Implicit search finds
them either way: the implicit scope of `LerpBy[Vec3Expr, FloatExpr]` includes
the companions of `LerpBy`, `Vec3Expr` and `FloatExpr`.

### What stays where it is

- **`NumExt.mix` stays.** It is the primitive the vector ops are built on
  (`vec3.scala`: `create(v.x.mix(b.x, t), …)`), it is `inline`, and it sits in
  subdivision loops.
- **`NumExt.lerp` stays.** Removing it would delete `.lerp` from concrete
  `Double`/`FloatExpr` receivers, because `LerpBy`'s givens are reachable only
  through implicit scope (see below) — `n.lerp(0.2, t)` in a shader would stop
  compiling, and the guide documents scalar `.lerp` as part of the mirrored
  CPU/GPU surface.
- **The `Vec*` ops keep their own `lerp`/`mix` overload sets.** They are richer
  than a type class can express — component-wise `t`, CPU-value bounds, literal
  bounds — and `LerpBy` must not compete with them (next point).

### `LerpBy`'s givens are never exported into identifier scope

This is the load-bearing constraint. If the prelude exported `LerpBy.given`,
those givens would become imported identifiers and, by rule 3 above, **shadow
the vector ops' `.lerp`** — collapsing a rich overload set to one signature. So
the givens stay in companions, reachable by implicit search only:

- `[T: Lerp]` context bounds resolve (implicit scope);
- `summon`/`using` inside `lerpIn` resolves (implicit scope);
- `someVec.lerp(other, t)` keeps resolving to the ops traits, unshadowed.

### `lerpIn`

One generic definition per receiver, plus the literal-bound overloads that
generic inference cannot serve:

```scala
extension (t: Double)
  inline def lerpIn[T](lo: T, hi: T)(using inline l: Lerp[T]): T

extension (t: FloatExpr)                       // gpu/float_expr.scala
  inline def lerpIn[T](lo: T, hi: T)(using inline l: LerpBy[T, FloatExpr]): T
  inline def lerpIn[T <: Expr](lo: LetExpr[T], hi: LetExpr[T])(using
      inline l: LerpBy[T, FloatExpr]): T
  def lerpIn(lo: Double, hi: Double): FloatExpr
  def lerpIn(lo: Int, hi: Int): FloatExpr
```

The `LetExpr` overload covers bounds held in shader locals
(`t.lerpIn(stopPrev, stopNext)`). `LerpBy` is invariant, so the generic form
would infer `T = LetFloat` and find no instance.

### Why not one `lerpIn` on `LerpBy` itself?

Tried, and it does not work — the two definitions are not duplication kept for
speed:

```scala
trait LerpBy[T, P]:
  extension (t: P) def lerpIn(lo: T, hi: T): T = lo.lerp(hi, t)
```

- **Not found.** The receiver is a `Double`, whose implicit scope does not
  include `LerpBy`'s companion, so the extension resolves only if the givens are
  imported into identifier scope — the one thing that must not happen, since it
  hides the ops traits' `lerp`.
- **Ambiguous even then.** The receiver fixes `P` and leaves `T` free, so every
  instance with the same parameter type offers the same extension: _"both object
  doubleLerp and object unitLerp provide an extension method `lerpIn` on (0.5d :
  Double)"_.

Taking the receiver as the _parameter_ and inferring `T` from the arguments is
what makes a call site resolvable at all. Hence one `lerpIn` per receiver
domain.

This replaces the hand-enumerated `Vec2Expr`/`Vec3Expr`/`Vec4Expr` block — the
generic form dispatches to their concrete `LerpBy` instances instead.

The two literal overloads are not equals, measured by deleting each and
rebuilding clean:

| variant                       | result                              |
| ----------------------------- | ----------------------------------- |
| generic + `Double` + `Int`    | compiles                            |
| generic + `Int` (no `Double`) | **fails** on `t.lerpIn(0.81, 1.0)`  |
| generic + `Double` (no `Int`) | compiles — `Int` widens to `Double` |
| generic only                  | fails on both literal forms         |

So `Double` is load-bearing (Scala will not apply
`Conversion[Double, FloatExpr]` through an overloaded method set) and `Int` is
kept only for emit consistency: every other DSL op lowers an `Int` literal to
`f32(n)`, and without the overload `lerpIn` alone would emit `0.0`.

**Measurement hygiene, learned the hard way in this work:** scala-cli's
incremental state produced false results twice — a "failure" that was a stale
build, and a "regression" that did not exist. Delete `.scala-build` before every
comparative compile, and never conclude from a probe that carries a type
ascription: an expected type steers overload resolution and inference, hiding
exactly the failures a bare call site would hit.

## Zero-cost givens

Routing an operation through a type class is only acceptable here if it compiles
to what the hand-written call compiles to. Measured, six shapes of the same
function, `jsMode full`:

| shape                                                        | emitted JS                                                 |
| ------------------------------------------------------------ | ---------------------------------------------------------- |
| parameterized given, anonymous-class **body** form           | `new given_L_V(Ops_V2()).k.e(a,b,t)` — **allocates**       |
| same, with `inline given`                                    | `new given_L_V(Ops_V2()).l.e(a,b,t)` — **still allocates** |
| non-parameterized given (object form), no `inline` anywhere  | `Ops_V2().e(a,b,t)` — free                                 |
| `inline given` **alias** (`= new L[V]: …`) + inline use site | `Ops_V2().e(a,b,t)` — free                                 |
| direct call, no type class (baseline)                        | `Ops_V2().e(a,b,t)`                                        |

Conclusions that govern the implementation:

- What allocates is a **parameterized given written as an anonymous class body**
  — it compiles to a class taking its dependencies as constructor arguments,
  `new`-ed at each summon site. That was the pre-refactor `vec2Lerp` shape, and
  the only type-class allocation in any example bundle at the time.
- `inline given` on the body form changes nothing.
- `inline given` as an **alias**, combined with an `inline def` use site taking
  `using inline`, erases completely. Cost: the compiler warns that the anonymous
  class is duplicated at each inline site — fine for a one-line `lerp`, not for
  a large body.
- A **non-parameterized** given needs no `inline` at all; the Scala.js optimizer
  already devirtualizes it to a direct singleton call, and it can never produce
  a per-call-site class. This is the reliable shape.
- Making the type class _method_ `inline` does not substitute for the alias.
  Four combinations, measured on a parameterized scalar instance via
  `0.5.lerpIn(a, b)`:

  | given shape | method       | emitted                                                       |
  | ----------- | ------------ | ------------------------------------------------------------- |
  | alias       | plain `def`  | one anonymous class **per call site**, `new`-ed on every call |
  | body        | plain `def`  | `+new LerpBy$scalarLerp(NumExt_Double$()).t(…)` — alloc + box |
  | body        | `inline def` | `new LerpBy$scalarLerp(…).h.n(…)` — box gone, alloc stays     |
  | alias       | `inline def` | does not compile: nested inline methods are not supported     |

  The cost lives in the given's shape, not the method's.

### Why there are no parameterized givens (the alias form is not an escape hatch)

The design went through a parameterized phase — `inline given … = new Lerp[V]:`
aliases abstracting over the representation. They were removed because the alias
form folds away **only when the consumer is small enough for the optimizer to
inline first**. That is not a property to rely on, and it took three
measurements to pin down:

- a small generic consumer (`def midpoint[T: Lerp]`) over `Vec3`: folds
  completely — same `mixScalar` call as a direct `.mix`, no class emitted;
- a parameterized **scalar** instance (`[P: NumExt] => LerpBy[P, P]`) in the
  same alias form: **does not fold**. Two call sites produced two distinct
  anonymous classes, `new`-ed on every call;
- a generic consumer too big to inline (the shape a real algorithm has —
  `Grid.subdivide`, `splitByPlane`, the `Line` transforms) over `Vec3`: **does
  not fold** either. One anonymous class per call site, allocated per call, and
  two call sites for the _same_ type produced two separate classes:

  ```js
  $p.v = (x, n) => …(new Vec3(x,x,x), new Vec3(x,x,x), n, new $anon$1()).g;
  $p.w = (x, n) => …(new Vec3(x,x,x), new Vec3(x,x,x), n, new $anon$2()).h;
  ```

So **concrete, non-parameterized givens are the only reliably free shape**, and
every instance in the library is one:

Resolution, read off a running build (`summon[…].getClass.getName`) — **every
instance is a module, so nothing allocates at any call site**:

| type                     | instance that wins                  | lives in                    |
| ------------------------ | ----------------------------------- | --------------------------- |
| `Double`                 | `LerpBy$doubleLerp$`                | `LerpBy` companion          |
| `Unit`                   | `LerpBy$unitLerp$`                  | `LerpBy` companion          |
| `Vec2` / `Vec3` / `Vec4` | `cpu.Vec*$lerpInstance$`            | each vector's companion     |
| `Vec2Tuple` / `3` / `4`  | `LerpBy$vec*TupleLerp$`             | `LerpBy` companion          |
| `FloatExpr`              | `gpu.Expr$FloatExpr$floatExprLerp$` | the opaque type's companion |
| `Vec2Expr` / `3` / `4`   | `gpu.Expr$Vec*Expr$lerpInstance$`   | the opaque type's companion |
| `StructRef[Vec*Buffer]`  | **none** — no `Lerp` exists         | —                           |

Two placement rules are doing the work:

- an instance goes in **the type's own companion** where the type has one that
  implicit search reaches — the `Vec*` classes, and the opaque `*Expr` types;
- it goes in **`LerpBy`'s companion** where the type does not. `Vec3Tuple` is an
  alias for `(Double, Double, Double)`, so the implicit scope of
  `Lerp[Vec3Tuple]` is the companions of `Tuple3` and `Double` — never
  `object Vec3Tuple`. `LerpBy`'s own companion is always in scope, so that is
  the home for those.

The buffer representations are `Vec*Mutable`, not `Vec*ImmutableOps`, so they
have no `lerp` to delegate to and get no instance. That was true before this
refactor too; they have never had a `Lerp`.

**There are no generic instances left.** The trade-off is explicit: a new
representation does not get a `Lerp` for free, it needs its instance written by
hand — which is the same discipline the rest of the ops traits already follow,
and it buys a guarantee that no call site anywhere materialises a class.

With concrete instances the same non-inlinable probe emits a module reference
and allocates nothing:

```js
$p.q = (x, n) => …(new Vec3(x,x,x), new Vec3(x,x,x), n, Vec3$lerpInstance$()).g;
```

Note that the companion instances must be kept **out of identifier scope** —
`cpu/package.scala` exports `Vec3.given` wholesale, which would put their `lerp`
extension in scope and hide the ops traits' richer overload sets. The export
excludes them by name (`export Vec3.{lerpInstance as _, given}`); implicit
search still finds them in the companion.

That warning the alias shape produced
(`New anonymous class definition will be duplicated at each inline site`) is
literal, and it is about _compile-time call sites_ — one class per place the
given is summoned at an inline site, when it does not fold. With every instance
concrete the warning is gone, and so is the `@nowarn` that used to suppress it.

## Was the old design faster? (A/B against the pre-refactor tree)

"Old" is the parent of `7278824`; "new" is the refactor.

The question is fair: the previous design had an unparameterized `Lerp[T]` and
`inline` `lerp`/`lerpIn` on `NumExt`, which sounds cheaper than a type class.
Measured by compiling the same probe against both trees, `jsMode full`:

| path                         | old                                                 | new                                |
| ---------------------------- | --------------------------------------------------- | ---------------------------------- |
| `t.lerpIn(a, b)`, scalar     | `a * (1.0 - t) + b * t`                             | same                               |
| generic `[T: Lerp]`, scalar  | module call                                         | same                               |
| generic `[T: Lerp]`, `Vec3`  | **`new Lerp$vec3Lerp(…)` per call** + 2 field loads | direct `mixScalar` — no allocation |
| `Lerp` classes in the bundle | 2 (`doubleLerp$` module + `vec3Lerp` class)         | 1 (`doubleLerp$` module)           |

The generic vector path is the one that matters — it is what `Grid.subdivide`,
`Quad.subdivide*`, `splitByPlane` and the `Line` transforms run per element:

```js
// old
var evidence$1 = new Lerp$vec3Lerp(Vec3Mutable$(), Vec3$().l())
return mixScalar(evidence$1.j, a, evidence$1.i, b, 0.5).d
// new
return mixScalar(Vec3$().j(), a, Vec3Mutable$(), b, 0.5).d
```

So the new design is equal on every scalar path and strictly better on the
generic vector path. `Lerp[T]` being unparameterized was never the cost — its
_givens_ were parameterized anonymous-class bodies, so every summon site
allocated. `NumExt`'s inline methods were genuinely free, and the new code
matches them rather than beating them.

That parity is not automatic, and was reached only after two corrections: the
derived `[P: NumExt] => LerpBy[P, P]` had regressed the scalar path to a
per-call-site anonymous class, and the concrete given alone still left a module
call. Concrete given **plus** `inline def lerp` is what restores the arithmetic.

## Steps

1. **Delete `NumExt.lerpIn`.** Already shadowed for every receiver that matters.
2. **Introduce `LerpBy[T, P]` + `type Lerp[T]`,** add the GPU instances in the
   `Vec*Expr` companions, collapse the enumerated GPU `lerpIn` block to one
   generic definition + literal overloads, and give every representation that
   has `lerp` ops a concrete, non-parameterized instance. (The draft derived
   `LerpBy[P, P]` from `NumExt`. That was dropped, see
   [Deviations](#deviations-from-the-original-plan).)
3. **Move `numbers` and `interpolation` to a top-level `trivalibs.math`**
   package, next to `utils`/`graphics`. Vector math stays in `graphics` — those
   types are tailored to the painter API.

Steps 1 and 2 are done (see [Status](#status-2026-10-17)). Step 3 is open, and
carries one unresolved cost:

### Step 3's open question: implicit scope

The concrete-instance work changed the shape of this problem. `LerpBy`'s
companion no longer holds anything that mentions `Vec*Base` — the vector and
expression instances live in their own companions. What it _does_ hold is the
three tuple instances, and those have to be there (a tuple alias has no
companion in its implicit scope) while referring to `Vec*ImmutableOps` to do the
work. So `interpolation.scala` now imports `graphics.math.cpu`.

That is the one thing blocking a clean move to `trivalibs.math`: the tuple
instances would drag a `graphics.math.cpu` dependency into the new package.
Options to weigh when step 3 is taken up:

- leave the tuple instances behind in `graphics.math` (a small module there,
  with `LerpBy` itself in `trivalibs.math`) — implicit scope then no longer
  finds them, so they would need an import or a re-home;
- drop the tuple instances and accept that tuple vertices have no `Lerp`;
- keep `LerpBy` where it is and move only `numbers`.

## Follow-up: linear-algebra indirection audit

Separate investigation, after this refactor. The goal is that the CPU math in
render loops carries as little indirection as possible, and the method above is
the tool: compile, read the emitted call, diff against hand-written arithmetic.

Early signal, from before the refactor: scanning every example bundle for
surviving allocations, the only type-class instance allocated anywhere was
`Lerp$vec2Lerp`, and this refactor removed it. The ops traits are summoned
through object-form givens, which the optimizer already reduces to direct
singleton calls. Two limits on that signal, both of which the audit should
address:

- **Wrong corpus.** `examples/out/` is library examples, not render-loop CPU
  math, and may be stale against `src`. The sketches repo holds the real hot
  paths.
- **Allocation is one axis; dispatch is the other.** An extension reached
  through a given becomes a _call_ on a singleton rather than inlined
  arithmetic, and that never appears as a `new`. Scala.js devirtualizes it —
  whether it then inlines the body is unmeasured. For `v.x.mix(b.x, t)`, three
  calls per vector op inside a subdivision loop, that is the number that
  matters.

## What comes next

The type class itself is finished and in use. What keeps the plan active is one
undecided step. The audit is separate work.

1. **Decide step 3.** Choose one of the three options in
   [Step 3's open question](#step-3s-open-question-implicit-scope), or drop the
   move entirely. Either way, record the outcome here. This is the only thing
   blocking archival.
2. **Archive.** Once step 3 is implemented or explicitly rejected, move this
   file to `documents/done/`.
3. **Split out the audit.** The
   [linear-algebra indirection audit](#follow-up-linear-algebra-indirection-audit)
   does not depend on step 3 and does not block archival. When archiving, move
   it to its own plan or to `documents/independent-todos.md` so it is not buried
   in a done document.
