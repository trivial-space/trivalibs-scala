package trivalibs.utils.js

import scala.annotation.targetName
import scala.scalajs.js

type JS = js.Object

// === Maybe[T] — js.undefined-based optional (for "not provided" semantics) ===

type Maybe[T] = js.UndefOr[T]
object Maybe:
  inline def Not = js.undefined

inline def maybe[A](condition: Boolean, value: A): Maybe[A] =
  if condition then value else Maybe.Not

extension [A](m: Maybe[A])
  inline def orElse(default: => A): A =
    if js.isUndefined(m.asInstanceOf[js.Any]) then default
    else m.asInstanceOf[A]
  inline def orMaybe(default: => Any): Maybe[A] =
    if js.isUndefined(m.asInstanceOf[js.Any]) then
      default.asInstanceOf[Maybe[A]]
    else m
  inline def safe: A =
    m.asInstanceOf[A]

type Opt[+A] = A | Null

extension [A](opt: Opt[A])
  @targetName("opt_isNull")
  inline def isNull: Boolean = opt == null
  @targetName("opt_notNull")
  inline def notNull: Boolean = opt != null
  @targetName("opt_getOr")
  inline def getOr(default: => A): A =
    if opt != null then opt.asInstanceOf[A] else default
  @targetName("opt_get")
  inline def get: A = opt.asInstanceOf[A]

object Obj:
  val literal = js.Dynamic.literal

type Dict[A] = js.Dictionary[A]
object Dict:
  inline def apply[A](): Dict[A] = js.Dictionary[A]()

// Native dictionary access — `js.Dictionary`'s `apply` / `update` route
// through `scala.scalajs.js.WrappedDictionary` (a Scala collection wrapper
// that bloats the bundle). These compile to raw JS property access.
extension [A](dict: Dict[A])
  inline def at(key: String): A =
    dict.asInstanceOf[js.Dynamic].selectDynamic(key).asInstanceOf[A]
  inline def set(key: String, value: A): Unit =
    dict.asInstanceOf[js.Dynamic].updateDynamic(key)(value.asInstanceOf[js.Any])
  inline def has(key: String): Boolean =
    js.DynamicImplicits.truthValue(
      dict.asInstanceOf[js.Dynamic].hasOwnProperty(key),
    )
  inline def delete(key: String): Unit =
    js.special.delete(dict, key)
  inline def keys: Arr[String] =
    js.Object.keys(dict.asInstanceOf[js.Object])

type Arr[A] = js.Array[A]

object Arr:
  /** Returns true if the given value is an array. */
  inline def isArray(arg: scala.Any): Boolean = js.Array.isArray(arg)

  /** <span class="badge badge-ecma2015" style="float: right;">ECMAScript
    * 2015</span>
    *
    * Creates a new array from js.Iterable.
    */
  inline def from[A](iterable: js.Iterable[A]): js.Array[A] =
    js.Array.from(iterable)

  /** Creates a new empty array. */
  inline def apply[A](): js.Array[A] = new js.Array[A]()

  /** Creates a new array with the given items. Concrete-arity overloads avoid
    * Scala varargs (which drag in `ScalaRunTime.wrapRefArray` /
    * `toJSVarArgsImpl`) so `js.Array(...)` stays an intrinsified JS literal.
    */
  inline def apply[A](a: A): js.Array[A] = js.Array(a)
  inline def apply[A](a: A, b: A): js.Array[A] = js.Array(a, b)
  inline def apply[A](a: A, b: A, c: A): js.Array[A] = js.Array(a, b, c)
  inline def apply[A](a: A, b: A, c: A, d: A): js.Array[A] =
    js.Array(a, b, c, d)
  inline def apply[A](a: A, b: A, c: A, d: A, e: A): js.Array[A] =
    js.Array(a, b, c, d, e)
  inline def apply[A](a: A, b: A, c: A, d: A, e: A, f: A): js.Array[A] =
    js.Array(a, b, c, d, e, f)
  inline def apply[A](a: A, b: A, c: A, d: A, e: A, f: A, g: A): js.Array[A] =
    js.Array(a, b, c, d, e, f, g)
  inline def apply[A](
      a: A,
      b: A,
      c: A,
      d: A,
      e: A,
      f: A,
      g: A,
      h: A,
  ): js.Array[A] = js.Array(a, b, c, d, e, f, g, h)
  inline def apply[A](
      a: A,
      b: A,
      c: A,
      d: A,
      e: A,
      f: A,
      g: A,
      h: A,
      i: A,
  ): js.Array[A] = js.Array(a, b, c, d, e, f, g, h, i)
  inline def apply[A](
      a: A,
      b: A,
      c: A,
      d: A,
      e: A,
      f: A,
      g: A,
      h: A,
      i: A,
      j: A,
  ): js.Array[A] = js.Array(a, b, c, d, e, f, g, h, i, j)
  inline def apply[A](
      a: A,
      b: A,
      c: A,
      d: A,
      e: A,
      f: A,
      g: A,
      h: A,
      i: A,
      j: A,
      k: A,
  ): js.Array[A] = js.Array(a, b, c, d, e, f, g, h, i, j, k)
  inline def apply[A](
      a: A,
      b: A,
      c: A,
      d: A,
      e: A,
      f: A,
      g: A,
      h: A,
      i: A,
      j: A,
      k: A,
      l: A,
  ): js.Array[A] = js.Array(a, b, c, d, e, f, g, h, i, j, k, l)

/** Non-mutating append / prepend / concat, each compiling to a bare JS
  * `.concat`. These shadow the same-named `SeqOps` methods that `js.ArrayOps`
  * would otherwise supply via implicit conversion — those go through `View` +
  * `IterableFactory` + builders, i.e. the Scala collections machinery this
  * library keeps out of runtime paths.
  *
  * All three copy. Push onto the array directly when building one up in a hot
  * loop.
  */
extension [A](arr: Arr[A])
  inline def :+(a: A): Arr[A] = arr.concat(Arr(a))
  inline def +:(a: A): Arr[A] = Arr(a).concat(arr)
  inline def ++(other: Arr[A]): Arr[A] = arr.concat(other)

  /** In-place append — mutates `arr`, compiling to a bare JS `.push`. Shadows
    * the `Growable.+=` that `js.ArrayOps` would otherwise supply via implicit
    * conversion (which drags in the Scala collections machinery). Use it to
    * build an array up in a loop: `arr += x`.
    */
  inline def +=(a: A): Unit = arr.push(a)

// Core extensions for for-comprehension support
extension [A](promise: js.Promise[A])
  inline def map[B](f: A => B): js.Promise[B] =
    promise.`then`[B](f)

  inline def flatMap[B](f: A => js.Promise[B]): js.Promise[B] =
    promise.`then`[B](f)

  inline def withFilter(p: A => Boolean): js.Promise[A] =
    promise.`then`[A]: a =>
      if p(a) then a
      else throw jsError("Promise.withFilter predicate failed")

  inline def recover[B >: A](pf: PartialFunction[Any, B]): js.Promise[B] =
    promise.`catch`[B]: (err: Any) =>
      if pf.isDefinedAt(err) then pf(err)
      else throw err.asInstanceOf[Throwable]

  inline def foreach(f: A => Unit): Unit =
    promise.`then`[Unit](f)
    ()

  inline def tap(f: A => Unit): js.Promise[A] =
    promise.`then`[A]: a =>
      f(a)
      a

// Nullable promise helpers — delegate to Opt extensions
extension [A](promise: js.Promise[A | Null])

  inline def orError(message: String): js.Promise[A] =
    promise.`then`[A]: value =>
      if value == null then throw jsError(message)
      else value.asInstanceOf[A]

  inline def orElse(default: => A): js.Promise[A] =
    promise.`then`[A]: value =>
      (value: Opt[A]).getOr(default)

/** `console.log`. Concrete-arity overloads avoid Scala varargs (which drag in
  * `ScalaRunTime.wrapRefArray` / `toJSVarArgsImpl`), so each call compiles to
  * a plain `console.log(…)`.
  */
inline def log(a: js.Any): Unit =
  js.Dynamic.global.console.log(a)
inline def log(a: js.Any, b: js.Any): Unit =
  js.Dynamic.global.console.log(a, b)
inline def log(a: js.Any, b: js.Any, c: js.Any): Unit =
  js.Dynamic.global.console.log(a, b, c)
inline def log(a: js.Any, b: js.Any, c: js.Any, d: js.Any): Unit =
  js.Dynamic.global.console.log(a, b, c, d)
inline def log(a: js.Any, b: js.Any, c: js.Any, d: js.Any, e: js.Any): Unit =
  js.Dynamic.global.console.log(a, b, c, d, e)
inline def log(
    a: js.Any,
    b: js.Any,
    c: js.Any,
    d: js.Any,
    e: js.Any,
    f: js.Any,
): Unit =
  js.Dynamic.global.console.log(a, b, c, d, e, f)

/** Constructs a throwable that surfaces as a plain JavaScript `Error` with the
  * given message when thrown — use as `throw jsError(message)`.
  *
  * Scala.js unwraps the [[scala.scalajs.js.JavaScriptException]] at the throw
  * site, so consumers see a native `Error` in the console rather than a leaked
  * Java/Scala exception namespace (e.g. `java.util.NoSuchElementException`).
  */
inline def jsError(message: String): js.JavaScriptException =
  js.JavaScriptException(js.Error(message))
