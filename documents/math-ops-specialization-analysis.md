# Math ops specialization: inline traits vs. `@inline`

Status: **analysis only, nothing changed in `src/`**. Measured with Scala 3.9.0
/ 3.10.0 and Scala.js 1.22.0, `--js-mode full`, ES modules.

## Question

Scala 3.10 ships two experimental features, **inline traits** and the
**`Specialized`** type class, pitched as making trait-based abstractions
zero-cost (no allocations, no virtual dispatch). The math lib is built from
exactly such abstractions (`Vec*BaseG` / `Vec*ImmutableOpsG` → CPU `Vec*Base` /
`Vec*ImmutableOps` → per-type givens). Would they improve runtime speed or
bundle size?

## The feature (Scala 3.10, Pre-SIP)

See the [3.10 release notes](https://www.scala-lang.org/news/3.10/), section on
the Pre-SIP features.

- Enabled with `-language:experimental.specializedTraits`.
- An `inline trait` is not emitted as a shared superclass whose members erase to
  `Object`. Its members are **copied into every class that extends it**, with
  the type parameters substituted.
- `T: Specialized` additionally emits a specialized interface for calls made
  through a `Trait[Int]` reference, so primitives are not boxed. It succeeds
  Scala 2's `@specialized`.

```scala
inline trait Cell[T: Specialized](val value: T):
  def get: T = value
```

The two things it addresses are JVM costs: virtual dispatch through traits, and
boxing of primitives in generic code.

## Current state of the math lib on Scala.js

Probe: `(a + b * 2.0).normalize.cross(b)` on `Vec3`, with `Vec3` and `Vec3Tuple`
both reachable. Every generic op becomes one shared helper that dispatches
through the given dictionary for each accessor, and through `$thiz` for
`create`:

```js
function $f_…Vec3ImmutableOps__addVec__O__…Vec3Base__O__O($thiz, v, x$2, other) {
  return $thiz.w(((+x$2.e(v)) + (+x$2.e(other))), ((+x$2.f(v)) + (+x$2.f(other))), …);
}
```

So `a + b` costs 6 virtual accessor calls plus a virtual `create`, and the call
sites in a sketch hold the dictionaries:
`$f_…addVec($m_Vec3$().given_…(), a, $m_Vec3$given_Vec3Mutable_Vec3$(), b)`.

The `inline def x` accessors in the givens do not help here: an `inline def`
that implements an abstract `def` keeps a non-inline copy for dynamic dispatch,
and that copy is what the generic op calls.

The Scala.js optimizer removes the dispatch only when **a single implementor is
reachable**. In the probe, `Vec3MutableOps.add` was specialized to direct field
access (`v.t + other.t`) because `Vec3` was the only `Vec3Mutable`. A real
sketch that also uses `Vec3Buffer` loses that.

## Why the talk's motivation mostly doesn't apply

- **`Specialized` / boxing: not an issue.** A `Double` is a JS number; in full
  link `$uD(x)` is just `+x`. On the CPU side `Num` is already fixed to `Double`
  by hand (`Vec3Base extends Vec3BaseG[Double, Vec]`), and the `Vec` parameter
  is always a reference type (class, tuple, `StructRef`).
- **Dispatch: real**, as shown above.
- **Bundle size: likely worse, not better.** Inline traits copy every reachable
  member once per implementing type (`Vec3`, `Vec3Tuple`, …), where there is one
  shared helper today.
- **GPU side: irrelevant.** `*Expr` ops build WGSL strings once at pipeline
  build time.

## Measurements

The same minimal pattern (`Base[V]` accessors, `Ops[V]` with `create` and a
`plus` extension, implemented by a class `P` and a tuple `T`), compiled in four
variants. Full-link output:

| Variant                                                                              | Emitted for `plus` on `P`                                                                         |
| ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------- |
| plain `def` (today's lib)                                                            | `$f_Ops__plain(…)($m_P$(), a, $m_P$given_Base_P$(), b)`, a shared helper with dispatch            |
| `inline trait Ops[V]` (3.10), base via `(using Base[V])`                             | per-type method `new $c_P((x$2.r(v) + x$2.r(o)), …)`: `create` direct, accessors still dispatched |
| `inline trait Ops[V] extends Base[V]` (3.10), accessors implemented in the companion | per-type method `new $c_P((v.x + o.x), (v.y + o.y))`: fully specialized, still a call             |
| `@inline def` (Scala.js hint, 3.9)                                                   | **inlined at the call site**: `new $c_P((a.r + b.r), (a.s + b.s))`                                |
| `inline def` (Scala 3 keyword, 3.9)                                                  | same as `@inline`                                                                                 |

Tuples behave the same: `new $c_T2$mcDD$sp((a.w() + b.w()), …)`.

Takeaways:

- Inline traits work on Scala.js 3.10, but only fully pay off if the ops trait
  stops taking the base as a per-call `using` parameter. As long as the evidence
  is passed per call, the copied body still calls through it. That is a
  structural refactor of all `*ImmutableOps` / `*MutableOps` traits and their
  givens.
- An `@inline` hint already gets the best result today, on 3.9, with no
  structural change.

## `@inline` vs. the `inline` keyword

|           | `inline` keyword (Scala 3)                                                          | `@inline` annotation (Scala.js)                                    |
| --------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| when      | compile time, in the Scala 3 frontend                                               | link time, in the Scala.js optimizer, with whole-program knowledge |
| guarantee | always inlined; recursion is a compile error                                        | a hint, which the optimizer may decline                            |
| knowledge | static types at the call site only                                                  | the set of instantiated classes; devirtualizes, then inlines       |
| platform  | JVM and JS                                                                          | Scala.js only; ignored by Scala 3 on the JVM                       |
| semantics | not virtually callable (a retained copy is kept when it implements an abstract def) | none; it stays a plain method                                      |

With the keyword, the frontend only copies the body. The resulting `create(…)` /
`x$2.x(v)` calls on the given module are still virtual, and the Scala.js
optimizer then devirtualizes them because the receiver's class is known. Both
routes end with the optimizer.

## Recommendation

1. Leave inline traits alone for now. They are experimental, the gain on
   Scala.js comes from dispatch rather than boxing, and getting it requires
   restructuring every ops trait. Revisit if they stabilize, and if `@inline`
   turns out to be insufficient.
2. Experiment with `@inline` on the **small** CPU ops: component-wise
   arithmetic, `dot`, `cross`, `length*`, `mix`, `min` / `max`, `clamp`,
   swizzles. The inlined body is roughly the size of the call it replaces in
   minified output, and the shared `$f_` helpers and given dictionaries can be
   dead-code-eliminated when no non-inlined caller remains. So bundle size
   likely goes down too.
3. Keep **large** bodies as plain `def`: `Mat4` `inverse` / `*`, Bézier, and
   quaternion ops. Inlining them duplicates the code at every call site.
4. Measure per file. Start with `vec3.scala`, rebuild one math-heavy sketch,
   then compare `main.js` size and the emitted op call sites before and after.
