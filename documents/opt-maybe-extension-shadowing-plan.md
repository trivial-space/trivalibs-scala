# `Opt` / `Maybe` extensions shadow dynamic and `Selectable` members

**Status: planned, not implemented.** Found during M1.1 of
[headless-painter-and-texture-sources-plan.md](headless-painter-and-texture-sources-plan.md)
(§9, "Library trap"). It was worked around there with a typed facade; this plan
fixes the cause, and enables explicit nulls (`-Yexplicit-nulls`) for trivalibs
and the consuming repo in the same change, because the fix depends on it.

## 1. Problem

`trivalibs.utils.js` defines extensions on two transparent aliases:

```scala
type Opt[+A] = A | Null          // isNull, notNull, getOr, get
type Maybe[T] = js.UndefOr[T]    // = T | Unit — orElse, orMaybe, safe
```

Every type `T` conforms to `T | Null` and to `T | Unit`, so **these extensions
apply to a value of any type**, not only to values declared `Opt` or `Maybe`. On
most types that is harmless: `x.get` is an identity cast, and `x.isNull` is a
plain null check. It breaks wherever the name is meant for something else. Scala
resolves a selection `x.name` in this order:

1. a real member of `x`'s type;
2. an extension method or an implicit conversion;
3. `selectDynamic` / `applyDynamic` (`js.Dynamic`, and `Selectable` fields).

So an imported extension **silently wins** over the dynamic member and over the
`Selectable` field of the same name. The compiler issues no error and no
warning.

### Evidence

From a standalone experiment (Scala 3.9.0, Scala.js 1.22.0, Node 24):

| Expression                                         | Today                                       | Expected     |
| -------------------------------------------------- | ------------------------------------------- | ------------ |
| `m.get("k")`, `m: js.Dynamic` (a JS `Map`)         | `m("k")` → `TypeError: m is not a function` | JS `Map.get` |
| `acc.get`, `acc` a `Selectable` with a field `get` | returns `acc` itself                        | the field    |
| `s.get`, `s: String`                               | compiles, as an identity                    | (harmless)   |

The first case hit `Readback` in M1.1 (`imageWrites.get(image)` on a `WeakMap`
built as `js.Dynamic`). The generated code was `(0, this.l1)(target)`.

### Exposure

- **`js.Dynamic`:** any JS method or property named `get`, `isNull`, `notNull`,
  `getOr`, `orElse`, `orMaybe` or `safe`. `get` is common in JS: `Map`,
  `WeakMap`, `Headers`, `URLSearchParams`, `FormData`, `Cache`, IDB stores. The
  preact layer (`jsAttribs`) and the painter use `js.Dynamic` heavily.
- **`Selectable`:** the shader DSL's accessors (`TypedExprAccessor`,
  `TypedAssignAccessor` and `TypedPanelAccessor` in `shader/dsl/context.scala`)
  resolve `ctx.in.<name>`, `ctx.uniforms.<name>` and `ctx.textures.<name>`
  through `selectDynamic`. A varying, uniform or panel field named `get` or
  `safe` would silently resolve to the extension.
- **Not affected:**
  - the nullable-promise extensions (`orError`, `orElse` on
    `js.Promise[A | Null]`), whose receiver must be a `js.Promise`;
  - the `Dict` / `Arr` / `Promise` extensions, which are tied to concrete types.
- **Also universal, low risk:** `extension [T](t: T) asBinding`
  (`buffers/binding.scala`). It requires a `UniformValue[T, F]`, and its name is
  unlikely to collide. It is out of scope; see §6.

Usage today (`\.name\b` matches, which also include non-`Opt` uses):

| Name      | trivalibs | consuming repo |
| --------- | --------- | -------------- |
| `get`     | 233       | 4              |
| `notNull` | 71        | 2              |
| `isNull`  | 30        | 2              |
| `getOr`   | 18        | 1              |
| `orElse`  | 9         | 4              |
| `safe`    | 6         | 0              |
| `orMaybe` | 3         | 0              |

## 2. Options

**A. Rename the colliding methods** (e.g. `get` → something no JS API uses).

- This lowers the odds of a collision, but doesn't remove them. The extensions
  still apply to every type, so any other name can collide with a `Selectable`
  field.
- It also means touching ~240 call sites for a partial fix.
- Rejected.

**B. Make `Opt` / `Maybe` opaque.**

- With a lower bound (`opaque type Opt[+A] >: A | Null = A | Null`), plain
  values still conform to `Opt[A]`, so the extensions stay universal. That
  changes nothing.
- Without one, every assignment needs a wrapper, which is invasive across all
  nullable fields, and `Maybe` has to stay assignable from `js.UndefOr`.
- Rejected: high cost, and with the bound it doesn't even fix the problem.

**C. Guard the extensions with `NotGiven`.**

```scala
extension [A](opt: Opt[A])(using
    NotGiven[A <:< js.Dynamic],
    NotGiven[A <:< Selectable],
)
```

- Verified: `m.get("k")` reaches `Map.get`, `acc.get` returns the field,
  `Opt[String]` still unwraps, and the guard is erased in `jsMode full`.
- It only blocks the two receiver kinds it names. The extensions stay universal
  everywhere else.
- **The `Opt[js.Dynamic]` hazard:** `Opt[js.Dynamic]` and `js.Dynamic` are the
  same type to the compiler, so a nullable dynamic value loses the `Opt`
  operations. `od.get` silently becomes the JS property `get`.
- Superseded by D, which has none of these limits once explicit nulls is on.

**D. Explicit nulls plus a receiver-evidence guard** (proposed).

Explicit nulls on its own does not narrow the current extensions:
`T <: T | Null` still holds, so `extension [A](opt: A | Null)` still applies to
a `String`. What changes is that `Null` is no longer a subtype of reference
types. That makes a guard on the **receiver's own type** meaningful:

```scala
extension [O, A](opt: O)(using Null <:< O, O <:< (A | Null))
```

- `Null <:< O` holds only when the receiver's static type includes `Null`. So
  the `Opt` operations exist on values declared nullable, and only there.
- `O <:< (A | Null)` recovers `A` for `get` / `getOr`.
- `js.Dynamic` and `Selectable` are Scala-defined, so under explicit nulls they
  are non-nullable. On them the guard fails and the selection falls through to
  the dynamic member.
- A nullable `Opt[js.Dynamic]` is `js.Dynamic | Null`, which is not a `Dynamic`.
  Member selection on it is rejected, so the `Opt` extension wins, which is
  correct. **The hazard from C goes away.**
- `Maybe` gets the same shape with `Unit` in place of `Null`. That part works
  with or without explicit nulls, because `Unit` was never a subtype of
  reference types.

## 3. Proposal: D

```scala
type Opt[+A] = A | Null

extension [O, A](opt: O)(using Null <:< O, O <:< (A | Null))
  // isNull, notNull, getOr, get — bodies unchanged

type Maybe[T] = js.UndefOr[T]

extension [O, A](m: O)(using Unit <:< O, O <:< (A | Unit))
  // orElse, orMaybe, safe — bodies unchanged (orMaybe's `else m` needs a cast)
```

plus `//> using option -Yexplicit-nulls` in **both** `trivalibs/project.scala`
and the consuming repo's `project.scala`.

### Verified

From a standalone experiment (Scala 3.9.0, Scala.js 1.22.0, Node 24,
`-Yexplicit-nulls`):

| Expression                                             | Result                                          |
| ------------------------------------------------------ | ----------------------------------------------- |
| `m.get("k")`, `m: js.Dynamic` (a JS `Map`)             | `42`: `Map.get`                                 |
| `acc.get`, `acc.isNull`, `acc` a `Selectable`          | the fields                                      |
| `od.notNull`, `od.get.get("k")`, `od: Opt[js.Dynamic]` | `true`, `42`: `Opt` ops, then `Map.get`         |
| `Opt[String]`, `Opt[Foo]`, `Opt[Int]`: `get`, `getOr`  | unwrap as before                                |
| `def gen[T](x: Opt[T], d: T) = x.getOr(d)`             | compiles, `A` inferred as `T`                   |
| `"x".isNull`, `Foo(1).get`, `t.isNull` (`t: T`)        | compile error: `Cannot prove that Null <:< …`   |
| `d.notNull` as `Boolean`, `d: js.Dynamic`              | compile error: `Found: js.Dynamic`              |
| `Maybe[Int]`, `Maybe[String]`, `Maybe[js.Dynamic]`     | `orElse` / `orMaybe` / `safe` as before         |
| `d.safe`, `acc.safe` (dynamic / `Selectable`)          | the dynamic member / field                      |
| `"x".safe`                                             | compile error: `Cannot prove that Unit <:< …`   |
| same `Opt` code **without** the flag                   | `m.get("k")` → `TypeError: m is not a function` |

`jsMode full` output for `x.getOr(Foo(9)).n + y.get.length`:

```js
return ((x !== null ? x : new $c_LFoo(9)).g + y.length) | 0
```

Neither guard leaves anything in the bundle.

### Consequences

- **The flag is load-bearing for `Opt`.** Without it, `Null <:< AnyRef`-types
  holds again, so the `Opt` guard passes for every reference type, `js.Dynamic`
  included. That is today's behavior, not worse, but the fix is gone. Every
  consumer of trivalibs has to compile with explicit nulls. This holds here,
  because sketches and `trivalibs/src` build in one scala-cli run. For a future
  published artifact it has to be documented as a requirement. The `Maybe` guard
  doesn't depend on the flag.
- **`Opt` / `Maybe` operations need a value typed as optional.** Calling `.get`
  / `.notNull` on a non-nullable value is now a compile error rather than a
  no-op. Sites that do it on purpose either drop the redundant call or ascribe
  the value (`val e: Opt[Element] = …`). This mostly matters for scalajs-dom
  facades, which declare nullable returns such as `getElementById` as
  non-nullable.
- **`.notNull` does not flow-type.** Flow typing only recognizes a literal
  `x != null` / `x ne null`, and the inline extension is expanded after the
  typer. So `if x.notNull then use(x)` fails, because `x` is still `T | Null`.
  Either write `x != null` (works on local `val`s and unmutated local `var`s) or
  unwrap with `x.get`. Class fields never flow-type, so `.get` is the form
  there.
- **Avoid the stdlib `.nn`.** It throws `NullPointerException`, a Java exception
  (see "No Scala/Java exceptions" in `CLAUDE.md`). `.get` is the unchecked
  unwrap, `getOr` the checked one.
- **Receivers typed `Any`** still satisfy both guards (`Null <: Any`,
  `Unit <: Any`). Harmless: `Any` has no dynamic members.

### Migration size

The current code was compiled with the flag on and the **current** extensions
(typer errors only, so this is a lower bound):

- trivalibs `src` + `examples`: **50 errors in 3 files.** 44 are in
  `painter/painter.scala`, 4 in `lib/noise/extended.scala`, 2 in
  `painter/form.scala`. They fall into three kinds:
  - member selection on an `Opt[...]` field (`opt.panel`, `encoder.finish()`);
  - an `Opt[X]` passed where an `X` is expected after an `if x.notNull` guard;
  - `null` assigned to, or passed as, a non-nullable type
    (`var data: ArrayBuffer = null`, the `null` `Vec3` / `Vec4` arguments in
    `extended.scala`). These need `Opt[...]` declarations.
- trivalibs `test`: not measured. It compiles only after main does.
- consuming repo (`sketches` + `src`): **no further errors** in the same run.

Swapping in the guarded extensions then adds a second, loud round: every
`Cannot prove that Null <:< X` / `Unit <:< X` marks an `Opt` / `Maybe` operation
on a value that isn't typed optional.

## 4. Implementation

1. **Enable explicit nulls** in `trivalibs/project.scala` and the consuming
   repo's `project.scala` (`//> using option -Yexplicit-nulls`). Fix the errors
   with the extensions still unchanged:
   - nullable fields, vars and parameters get `Opt[...]` types;
   - `if x.notNull then use(x)` becomes `use(x.get)`, or `x != null` where `x`
     is a local;
   - no `import scala.language.unsafeNulls` escape hatches.
2. **Audit dynamic receivers.** Add a temporary extension that turns every
   affected name on a `js.Dynamic` receiver into a compile error:

   ```scala
   extension (d: js.Dynamic)
     inline def get: Nothing = compiletime.error("audit: get on js.Dynamic")
     // … same for isNull, notNull, getOr, orElse, orMaybe, safe
   ```

   - Under D, a non-nullable `js.Dynamic` used with an `Opt` / `Maybe` operation
     silently becomes a dynamic call. That is the one case the compiler won't
     flag by itself.
   - Every hit is either a real dynamic call (fine after the guard) or an `Opt`
     / `Maybe` operation on a dynamic value. The latter needs an
     `Opt[js.Dynamic]` / `Maybe[js.Dynamic]` type or, better, a facade.
   - Known so far: `Shade.vertexBufferLayout: Opt[js.Dynamic]` is already typed
     nullable, so it keeps working under D.
   - Remove the audit extension afterwards.

3. **Swap in the guarded extensions** in `utils/js.scala` (§3). Fix every
   `Cannot prove that Null <:< …` / `Unit <:< …`: drop the redundant operation,
   or ascribe the value as `Opt` / `Maybe`. Leave the `@targetName`s as they
   are.
4. **Tests** in `test/utils/OptMaybeShadowing.test.scala`:
   - `js.Dynamic` over a JS `Map`: `.get("k")` and `.set` reach the JS methods;
   - a `Selectable` with fields named `get`, `isNull`, `orElse` and `safe`
     returns the fields;
   - `Opt[js.Dynamic]`: `.notNull` and `.get` are the `Opt` operations;
   - `Opt[String]` / `Opt[SomeClass]` / `Opt[Int]` and `Maybe[Int]` keep every
     operation, including inside a generic `def f[T](x: Opt[T])`;
   - `compileErrors("\"x\".get")` / `compileErrors("\"x\".safe")` are non-empty.
5. **Revisit the M1.1 workaround.** `Readback`'s `WeakIntMap` facade stays:
   typed facades are the house style anyway. Its comment and the
   implementation-log entry no longer call it a workaround.
6. **Docs.**
   - `trivalibs/CLAUDE.md`:
     - "No `Option`" bullet: `Opt` / `Maybe` operations exist only on values
       typed `Opt` / `Maybe`; `x != null` for flow typing; never `.nn`.
     - Note that trivalibs and every consumer compile with `-Yexplicit-nulls`,
       and that the `Opt` guard depends on it.
   - consuming repo `CLAUDE.md`, `project.scala` section: the flag, and why it
     must stay.
   - Scaladoc of `Opt` / `Maybe` in `utils/js.scala` says the same.
7. **Verify.**
   - `bun run check` and `bun run test`;
   - all examples build;
   - the headless example still passes;
   - all sketches of the consuming repo compile;
   - one `jsMode full` diff of a representative bundle shows no size change.

## 5. Open questions

- Should scalajs-dom calls that can return `null` (`getElementById`,
  `querySelector`, `getContext`) get typed `Opt` wrappers in trivalibs? Only if
  the migration turns up more than a handful of sites.

## 6. Out of scope

- **Renaming `get` for readability** (it reads like `Option.get`, but never
  throws). That is a separate naming question, not needed for this fix.
- **Guarding `asBinding`.** It needs `UniformValue` evidence and has an unusual
  name. Add the same guard only if it ever collides.
