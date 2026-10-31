package trivalibs.dev

import org.scalajs.dom
import trivalibs.graphics.math.cpu.Quat
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.cpu.Vec4
import trivalibs.graphics.scene.PerspectiveCamera
import trivalibs.utils.js.*

import scala.scalajs.js

// ===========================================================================
// devPreserve — persist a piece of state across full page reloads while live
// coding. Vite reloads the whole page after each recompile (we avoid HMR so
// GPU resources are torn down cleanly), which resets all runtime state. This
// snapshots the value to sessionStorage on unload and re-applies it on the
// next load, overriding the sketch's initial value.
//
// Dev-only: everything is gated on `import.meta.hot` (truthy under Vite dev,
// `undefined` in production builds), so production paths are inert no-ops.
//
//   devPreserve(cam)              // restore cam if stored; save on reload
//   devPreserve(cam).reset        // preservation OFF: wipe + always initial
//   val speed = devPreserve("speed", 3.0)   // generic simple values (a cell)
// ===========================================================================

// ---------------------------------------------------------------------------
// Dev guard + storage primitives (private to this module)
// ---------------------------------------------------------------------------

// Typed facade so member access compiles to dot notation (`import.meta.hot`,
// not `import.meta["hot"]`). The literal `.hot` form is what Vite / Rollup
// statically replace + tree-shake out of production builds.
@js.native
private trait ImportMeta extends js.Object:
  val hot: js.UndefOr[js.Any] = js.native
  val url: String = js.native

private inline def importMeta: ImportMeta =
  js.`import`.meta.asInstanceOf[ImportMeta]

/** True under the Vite dev server, false in a built sketch.
  *
  * `import.meta.hot` is injected by Vite in dev and absent in prod builds, so
  * this is statically replaced and tree-shaken out of production.
  *
  * Beyond this module's own gating, use it for any **development affordance
  * that must not ship** — a free-flying camera, a debug overlay, a bypass of a
  * constraint the finished piece depends on. Preferable to a hand-rolled
  * constant, which a sketch can commit in the wrong state.
  */
def devMode: Boolean = importMeta.hot.isDefined

// `import.meta.url` namespaces keys per sketch module, avoiding collisions.
// Vite appends a cache-busting `?t=<timestamp>` that changes on every reload,
// so strip the query (and any hash) to keep the key stable across reloads.
private def metaUrl: String =
  val u = importMeta.url
  var end = u.indexOf("?")
  if end < 0 then end = u.indexOf("#")
  if end >= 0 then u.substring(0, end) else u

private def storageKey(label: String): String =
  s"trivalibs:dev:$metaUrl:$label"

private def storage: dom.Storage = dom.window.sessionStorage

private def readJson(key: String): Opt[js.Any] =
  val raw = storage.getItem(key).asInstanceOf[Opt[String]]
  if raw == null then null
  else
    try js.JSON.parse(raw)
    catch case _: Throwable => null

private def writeJson(key: String, json: js.Any): Unit =
  storage.setItem(key, js.JSON.stringify(json))

private def removeKey(key: String): Unit =
  storage.removeItem(key)

// ---------------------------------------------------------------------------
// Flush registry — one `pagehide` listener writes every registered value's
// latest state exactly once, right before the reload. Avoids per-frame writes.
// ---------------------------------------------------------------------------

private val flushers: Arr[() => Unit] = Arr()
private var listenerAttached = false

private def ensureListener(): Unit =
  if !listenerAttached then
    listenerAttached = true
    dom.window.addEventListener[dom.Event](
      "pagehide",
      (_: dom.Event) =>
        var i = 0
        while i < flushers.length do
          flushers(i)()
          i += 1,
    )

// Registers a flusher and returns a function that unregisters it again.
private def register(flush: () => Unit): () => Unit =
  ensureListener()
  flushers.push(flush)
  () =>
    val idx = flushers.indexOf(flush)
    if idx >= 0 then flushers.splice(idx, 1)

// ===========================================================================
// DevCodec[T] — JSON codec for the simple values handled by the generic
// `devPreserve(key, init)` overload. `decode` returns `Opt[T]` (null on a
// shape/type mismatch) so callers fall back to the initial value.
// ===========================================================================

trait DevCodec[T]:
  def encode(t: T): js.Any
  def decode(json: js.Any): Opt[T]

object DevCodec:
  private def asNum(json: js.Any): Opt[Double] =
    if js.typeOf(json) == "number" then json.asInstanceOf[Double] else null

  // Reads a JSON array of at least `n` numbers, else null.
  private def asNums(json: js.Any, n: Int): Opt[Arr[Double]] =
    if js.Array.isArray(json) then
      val a = json.asInstanceOf[Arr[Double]]
      if a.length >= n then a else null
    else null

  given DevCodec[Double]:
    def encode(t: Double): js.Any = t.asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Double] = asNum(json)

  given DevCodec[Float]:
    def encode(t: Float): js.Any = t.toDouble.asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Float] =
      val n = asNum(json)
      if n != null then n.toFloat else null

  given DevCodec[Int]:
    def encode(t: Int): js.Any = t.asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Int] =
      val n = asNum(json)
      if n != null then n.toInt else null

  given DevCodec[Boolean]:
    def encode(t: Boolean): js.Any = t.asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Boolean] =
      if js.typeOf(json) == "boolean" then json.asInstanceOf[Boolean] else null

  given DevCodec[String]:
    def encode(t: String): js.Any = t.asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[String] =
      if js.typeOf(json) == "string" then json.asInstanceOf[String] else null

  given DevCodec[Vec2]:
    def encode(t: Vec2): js.Any = Arr(t.x, t.y).asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Vec2] =
      val a = asNums(json, 2)
      if a != null then Vec2(a(0), a(1)) else null

  given DevCodec[Vec3]:
    def encode(t: Vec3): js.Any = Arr(t.x, t.y, t.z).asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Vec3] =
      val a = asNums(json, 3)
      if a != null then Vec3(a(0), a(1), a(2)) else null

  given DevCodec[Vec4]:
    def encode(t: Vec4): js.Any = Arr(t.x, t.y, t.z, t.w).asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Vec4] =
      val a = asNums(json, 4)
      if a != null then Vec4(a(0), a(1), a(2), a(3)) else null

  given DevCodec[Quat]:
    def encode(t: Quat): js.Any = Arr(t.x, t.y, t.z, t.w).asInstanceOf[js.Any]
    def decode(json: js.Any): Opt[Quat] =
      val a = asNums(json, 4)
      if a != null then Quat(a(0), a(1), a(2), a(3)) else null

// ===========================================================================
// Handles
// ===========================================================================

/** A preserved simple value. Read/write `value`; it is restored on load and
  * saved on reload. `.reset` wipes storage and reverts to the initial value.
  */
final class DevVar[T] private[dev] (var value: T):
  private[dev] var resetFn: () => Unit = () => ()
  def reset: DevVar[T] =
    resetFn()
    this

/** Handle for an in-place preserved object (e.g. a camera). `.reset` wipes
  * storage and restores the object's initial state.
  */
final class DevHandle private[dev] (resetFn: () => Unit):
  def reset: DevHandle =
    resetFn()
    this

// ===========================================================================
// Public API — `devPreserve` with overloaded `apply`
// ===========================================================================

object devPreserve:

  /** Preserve a simple value across reloads. Returns a `DevVar[T]` cell whose
    * `.value` is the restored (or initial) value; mutate it as the value
    * changes and the latest is saved on the next reload.
    */
  def apply[T](key: String, init: T)(using codec: DevCodec[T]): DevVar[T] =
    if !devMode then new DevVar[T](init)
    else
      val sk = storageKey(key)
      val stored = readJson(sk)
      val restored =
        if stored != null then codec.decode(stored).getOr(init) else init
      val cell = new DevVar[T](restored)
      val unregister = register(() => writeJson(sk, codec.encode(cell.value)))
      cell.resetFn = () =>
        unregister()
        removeKey(sk)
        cell.value = init
      cell

  /** Preserve a camera's position + orientation (`pos`, `rotH`, `rotV`) across
    * reloads, restoring into `cam` in place. `fov`/`near`/`far` stay from the
    * sketch config and `aspect` is left to `onResize`.
    */
  def apply(cam: PerspectiveCamera, label: String = "camera"): DevHandle =
    if !devMode then new DevHandle(() => ())
    else
      val sk = storageKey(label)
      val initPos = cam.pos
      val initRotH = cam.rotH
      val initRotV = cam.rotV

      val stored = readJson(sk)
      if stored != null then applyCam(cam, stored)

      val unregister = register(() => writeJson(sk, encodeCam(cam)))
      new DevHandle(() =>
        unregister()
        removeKey(sk)
        cam(pos = initPos, rotH = initRotH, rotV = initRotV),
      )

  private def encodeCam(cam: PerspectiveCamera): js.Any =
    Arr(cam.pos.x, cam.pos.y, cam.pos.z, cam.rotH, cam.rotV).asInstanceOf[js.Any]

  private def applyCam(cam: PerspectiveCamera, json: js.Any): Unit =
    if js.Array.isArray(json) then
      val a = json.asInstanceOf[Arr[Double]]
      if a.length >= 5 then
        cam(pos = Vec3(a(0), a(1), a(2)), rotH = a(3), rotV = a(4))
