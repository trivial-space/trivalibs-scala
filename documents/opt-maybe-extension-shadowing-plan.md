# `Opt` / `Maybe` extensions shadow dynamic and `Selectable` members

**Status: planned, not implemented.** Found during M1.1 of
[headless-painter-and-texture-sources-plan.md](headless-painter-and-texture-sources-plan.md)
(§9, "Library trap"). It was worked around there with a typed facade; this plan
fixes the cause.

## 1. Problem

`trivalibs.utils.js` defines extensions on two transparent aliases:

```scala
type Opt[+A] = A | Null          // isNull, notNull, getOr, get
type Maybe[T] = js.UndefOr[T]    // = T | Unit — orElse, orMaybe, safe
```

Every type `T` conforms to `T | Null` and to `T | Unit`, so **these
extensions apply to a value of any type**, not only to values declared `Opt` or
`Maybe`. On most types that is harmless: `x.get` is an identity cast, and
`x.isNull` is a plain null check. It breaks wherever the name is meant for
something else. Scala resolves a selection `x.name` in this order:

1. a real member of `x`'s type;
2. an extension method or an implicit conversion;
3. `selectDynamic` / `applyDynamic` (`js.Dynamic`, and `Selectable` fields).

So an imported extension **silently wins** over the dynamic member and over the
`Selectable` field of the same name. The compiler issues no error and no
warning.

### Evidence

From a standalone experiment (Scala 3.9.0, Scala.js 1.22.0, Node 24):

| Expression                                             | Today                               | Expected        |
| ------------------------------------------------------ | ----------------------------------- | --------------- |
| `m.get("k")`, `m: js.Dynamic` (a JS `Map`)             | `m("k")` → `TypeError: m is not a function` | JS `Map.get` |
| `acc.get`, `acc` a `Selectable` with a field `get`     | returns `acc` itself                | the field       |
| `s.get`, `s: String`                                   | compiles, as an identity            | (harmless)      |

The first case hit `Readback` in M1.1 (`imageWrites.get(image)` on a `WeakMap`
built as `js.Dynamic`). The generated code was `(0, this.l1)(target)`.

### Exposure

- **`js.Dynamic`:** any JS method or property named `get`, `isNull`,
  `notNull`, `getOr`, `orElse`, `orMaybe` or `safe`. `get` is common in JS:
  `Map`, `WeakMap`, `Headers`, `URLSearchParams`, `FormData`, `Cache`, IDB
  stores. The preact layer (`jsAttribs`) and the painter use `js.Dynamic`
  heavily.
- **`Selectable`:** the shader DSL's accessors (`TypedExprAccessor`,
  `TypedAssignAccessor` and `TypedPanelAccessor` in `shader/dsl/context.scala`)
  resolve `ctx.in.<name>`, `ctx.uniforms.<name>` and `ctx.textures.<name>`
  through `selectDynamic`. A varying, uniform or panel field named `get` or
  `safe` would silently resolve to the extension.
- **Not affected:**
  - the nullable-promise extensions (`orError`, `orElse` on
    `js.Promise[A | Null]`), whose receiver must be a `js.Promise`;
  - the `Dict` / `Arr` / `Promise` extensions, which are tied to concrete
    types.
- **Also universal, low risk:** `extension [T](t: T) asBinding`
  (`buffers/binding.scala`). It requires a `UniformValue[T, F]`, and its name
  is unlikely to collide. It is out of scope; see §5.

Usage today (`\.name\b` matches, which also include non-`Opt` uses):

| Name        | trivalibs | consuming repo |
| ----------- | --------- | -------------- |
| `get`       | 233       | 4              |
| `notNull`   | 71        | 2              |
| `isNull`    | 30        | 2              |
| `getOr`     | 18        | 1              |
| `orElse`    | 9         | 4              |
| `safe`      | 6         | 0              |
| `orMaybe`   | 3         | 0              |

## 2. Options

**A. Rename the colliding methods** (e.g. `get` → something no JS API uses).

- This lowers the odds of a collision, but doesn't remove them. The extensions
  still apply to every type, so any other name can collide with a
  `Selectable` field.
- It also means touching ~240 call sites for a partial fix.
- Rejected as the main fix.

**B. Make `Opt` / `Maybe` opaque.**

- With a lower bound (`opaque type Opt[+A] >: A | Null = A | Null`), plain
  values still conform to `Opt[A]`, so the extensions stay universal. That
  changes nothing.
- Without one, every assignment needs a wrapper, which is invasive across all
  nullable fields, and `Maybe` has to stay assignable from `js.UndefOr`.
- Rejected: high cost, and with the bound it doesn't even fix the problem.

**C. Explicit nulls (`-Yexplicit-nulls`).**

- `T <: T | Null` still holds, so the extensions stay universal.
- It is a large, unrelated migration.
- Rejected.

**D. Guard the extensions with `NotGiven`** (proposed):

```scala
extension [A](opt: Opt[A])(using
    NotGiven[A <:< js.Dynamic],
    NotGiven[A <:< Selectable],
)
  @targetName("opt_get") inline def get: A = opt.asInstanceOf[A]
  …
```

- For a `js.Dynamic` or `Selectable` receiver the guard fails. The extension is
  then not applicable, and the selection falls through to
  `applyDynamic` / `selectDynamic`.
- Verified in the experiment: `m.get("k")` returns `42`, and `acc.get` returns
  the field. `Opt[String]` still unwraps.
- **Zero runtime cost:** `NotGiven` does not appear in the fully linked JS
  (`--js-mode full`). The extensions stay `inline`.
- One guard per extension block covers every name, now and later.

## 3. Proposal: D, for both `Opt` and `Maybe`

```scala
extension [A](m: Maybe[A])(using NotGiven[A <:< js.Dynamic], NotGiven[A <:< Selectable])
  // orElse, orMaybe, safe — unchanged
extension [A](opt: Opt[A])(using NotGiven[A <:< js.Dynamic], NotGiven[A <:< Selectable])
  // isNull, notNull, getOr, get — unchanged
```

The two guards may be wrapped as one `NoDynamicMembers[A]` evidence, if that
reads better at the declaration. Either way it is erased.

### The one hazard: `Opt[js.Dynamic]`

With the guard, a value typed `Opt[js.Dynamic]` (or `js.Dynamic`) **loses
the `Opt` operations**. `od.get` then becomes the JS property `get`, and it
compiles. The experiment confirms it: `od.get` returned the `Map.get`
function. The type `Opt[js.Dynamic]` and the type `js.Dynamic` are the same to
the compiler, so this is inherent to any fix that lets dynamic members
through.

How it breaks, per operation:

- `isNull` / `notNull` turn into dynamic reads typed `js.Dynamic`. Where they
  are used as a condition this **fails to compile** (no `Boolean`), so those
  sites are found automatically.
- `get`, `getOr`, `safe`, `orElse` and `orMaybe` **can compile silently**
  wherever a `js.Dynamic` is expected.

Rule after the fix: **`Opt` / `Maybe` operations need a typed value.** A nullable
`js.Dynamic` gets a facade type, or a plain `== null` check and a cast.

## 4. Implementation

1. **Audit before guarding.** Add a temporary extension that turns every
   affected name on a `js.Dynamic` receiver into a compile error:

   ```scala
   extension (d: js.Dynamic)
     inline def get: Nothing = compiletime.error("audit: get on js.Dynamic")
     // … same for isNull, notNull, getOr, orElse, orMaybe, safe
   ```

   - Compile trivalibs (`src`, `examples`, `test`) and all sketches of the
     consuming repo. Every hit is either a real dynamic call (fine after the
     guard) or an `Opt` / `Maybe` operation on a dynamic value (needs a type).
   - Known so far: `Shade.vertexBufferLayout: Opt[js.Dynamic]`, used with
     `.notNull` in `painter.scala`. `dev.scala`'s `Opt[js.Any]` is unaffected,
     since `js.Any` isn't `Dynamic`.
   - Remove the audit extension afterwards.
2. **Fix the audited sites.** Give the value a facade type (e.g. a
   `GPUVertexBufferLayout` trait), or use `== null` / `!= null` and an explicit
   cast.
3. **Add the guards** to both extension blocks in `utils/js.scala`.
4. **Tests** in `test/utils/OptMaybeShadowing.test.scala`:
   - `js.Dynamic` over a JS `Map`: `.get("k")` and `.set` reach the JS
     methods;
   - a `Selectable` with fields named `get`, `isNull` and `orElse` returns the
     fields;
   - `Opt[String]` / `Opt[SomeClass]` and `Maybe[Int]` keep every operation;
   - an inline check that a guarded `.get` on `Opt[String]` still links to a
     plain read. A `jsMode full` spot check of one call site is enough; no
     test needed for that.
5. **Revisit the M1.1 workaround.** `Readback`'s `WeakIntMap` facade stays:
   typed facades are the house style anyway. Its comment and the
   implementation-log entry no longer call it a workaround.
6. **Docs.**
   - `trivalibs/CLAUDE.md` "Known traps": "`Opt` / `Maybe` operations are
     unavailable on `js.Dynamic` and `Selectable` values, by design. Type the
     value first."
   - The Scaladoc of `Opt` / `Maybe` in `utils/js.scala` says the same.
7. **Verify.**
   - `bun run check` and `bun run test`;
   - all examples build;
   - the headless example still passes;
   - all sketches of the consuming repo compile;
   - one `jsMode full` diff of a representative bundle shows no size change.

## 5. Out of scope

- **Renaming `get` for readability** (it reads like `Option.get`, but never
  throws). That is a separate naming question, not needed for this fix.
- **Guarding `asBinding`.** It needs `UniformValue` evidence and has an unusual
  name. Add the same guard only if it ever collides.
- **Explicit nulls** for the codebase.

## 6. Open questions

- Is `Selectable` the right second guard, or should it be narrowed to the DSL
  accessor classes? `Selectable` is broader, and also covers structural types
  and future accessors. The proposal is `Selectable`.
- Should the guard also cover `js.Any` subtypes with JS-native members? No:
  those members are real members and already win over extensions.
