package trivalibs.utils.events

import org.scalajs.dom
import trivalibs.utils.js.*

import scala.scalajs.js

/** A single tracked pointer (touch contact, pen, or mouse). Instances are
  * reused across presses, so only read them while the pointer is in
  * [[InputState.pointers]] (i.e. currently down) — the fields are stale once it
  * lifts. Positions are in client (CSS) pixels.
  */
final class Pointer:
  /** The DOM `pointerId` while this slot is down, `null` when the slot is free.
    * Internal — used to match move/up events and to detect drag hand-off.
    */
  private[events] var id: Opt[Double] = null

  /** The button that initiated the press (touch contacts report `Primary`). */
  var button: PointerButton = PointerButton.Primary

  /** Timestamp (ms, `Date.now()`) the pointer went down. */
  var downSince: Double = 0.0

  /** X of the press origin, kept for gestures that measure stray (e.g. hold).
    */
  var downX: Double = 0.0

  /** Y of the press origin. */
  var downY: Double = 0.0

  /** Current X position. */
  var x: Double = 0.0

  /** Current Y position. */
  var y: Double = 0.0
  // future: pressure, tiltX, tiltY

// ---------------------------------------------------------------------------
// InputState — Layer 2: the pollable snapshot a render loop queries each frame.
// It records raw input state only (no gesture interpretation): which keys are
// down and since when, which pointer buttons are down, and the set of currently
// active pointers as an appearance-ordered list (front = main pointer). Drag,
// hold and other gestures are built on top of this in the gesture layer.
//
// Keyboard listens on `keyTarget` (window by default, so keys are caught
// regardless of focus); pointers listen on `el` (typically the canvas).
//
// `onActivity` (if provided) fires on any state-changing event (key/button down
// or up, pointer down/up) — not on bare pointer moves. Together with `isIdle`
// it lets a scene skip a fixed render loop: start one on the first activity and
// stop it once idle, so static scenes cost nothing.
// ---------------------------------------------------------------------------

/** The pollable input snapshot for a canvas (Layer 2). Construct directly for
  * full control, or via [[interactiveCanvas]] / `Painter.input` for the
  * opinionated preset that also wires the default gestures.
  *
  * @param el
  *   element pointer presses are captured on (typically the canvas).
  * @param keyTarget
  *   element keyboard events are captured on (the window by default).
  * @param suppressContextMenu
  *   prevent the native context menu on secondary-button press.
  * @param onActivity
  *   optional callback fired on any state-changing event (not bare moves).
  * @param focusOnPointerDown
  *   focus `keyTarget` on pointer-down (useful for a focusable canvas).
  * @param maxPointers
  *   maximum number of simultaneous pointers tracked; extras are ignored.
  */
class InputState(
    el: dom.EventTarget,
    keyTarget: dom.EventTarget = dom.window,
    suppressContextMenu: Boolean = true,
    onActivity: Maybe[js.Function0[Unit]] = Maybe.Not,
    focusOnPointerDown: Boolean = false,
    maxPointers: Int = 4,
):
  private inline def fireActivity(): Unit =
    if !js.isUndefined(onActivity.asInstanceOf[js.Any]) then onActivity.get()

  /** code -> timestamp (ms) when the key was pressed. */
  val keysDown: Dict[Double] = Dict()
  // pointer-button value (as string) -> timestamp (ms) while down
  private val buttonsDown: Dict[Double] = Dict()

  // Reusable pointer slots (size `maxPointers`) and the appearance-ordered view
  // of the currently-down ones (front = oldest still down = main pointer).
  private val slots: Arr[Pointer] = Arr()
  private val order: Arr[Pointer] = Arr()
  private var lastMainX: Double = 0.0
  private var lastMainY: Double = 0.0

  private var disposeKeyboard: () => Unit = () => ()
  private var disposePointer: () => Unit = () => ()

  // Track focus of the key target so complex UIs can render a focus hint even
  // though the default focus outline is cleared on interactive canvases.
  private var _hasFocus =
    if keyTarget == dom.window then dom.document.hasFocus()
    else keyTarget == dom.document.activeElement

  private val focusFn: js.Function1[dom.Event, Unit] = _ =>
    _hasFocus = true
    fireActivity()
  private val blurFn: js.Function1[dom.Event, Unit] = _ =>
    _hasFocus = false
    fireActivity()

  install()

  // ---- internal pointer-slot helpers ----

  private def freeSlot(): Opt[Pointer] =
    var i = 0
    while i < slots.length do
      if slots(i).id == null then return slots(i)
      i += 1
    null

  private def slotById(id: Double): Opt[Pointer] =
    var i = 0
    while i < order.length do
      val p = order(i)
      if p.id != null && p.id.get == id then return p
      i += 1
    null

  private def removePointer(id: Double): Unit =
    val p = slotById(id)
    if p != null then
      val pt = p
      pt.id = null
      val idx = order.indexOf(pt)
      if idx >= 0 then order.splice(idx, 1)

  private def install(): Unit =
    var i = 0
    while i < maxPointers do
      slots.push(new Pointer())
      i += 1

    disposeKeyboard = keyboardRelay(
      keyTarget,
      onDown = k =>
        if !keysDown.has(k.code) then
          keysDown.set(k.code, js.Date.now())
          fireActivity(),
      onUp = k =>
        js.special.delete(keysDown, k.code)
        fireActivity(),
    )

    disposePointer = pointerRelay(
      el,
      onDown = (button, id, x, y, _) =>
        if focusOnPointerDown then
          keyTarget.asInstanceOf[dom.HTMLElement].focus()
        buttonsDown.set(button.key, js.Date.now())
        val slot = freeSlot()
        if slot != null then
          val pt = slot
          pt.id = id
          pt.button = button
          pt.downSince = js.Date.now()
          pt.downX = x
          pt.downY = y
          pt.x = x
          pt.y = y
          order.push(pt)
          if order.length == 1 then
            lastMainX = x
            lastMainY = y
        fireActivity()
      ,
      onMove = (id, x, y) =>
        val p = slotById(id)
        if p != null then
          val pt = p
          pt.x = x
          pt.y = y
          if order.length > 0 && (order(0) eq pt) then
            lastMainX = x
            lastMainY = y
      ,
      onUp = (button, id, _, _) =>
        js.special.delete(buttonsDown, button.key)
        removePointer(id)
        fireActivity()
      ,
      onCancel = id =>
        removePointer(id)
        fireActivity()
      ,
      suppressContextMenu = suppressContextMenu,
    )

    keyTarget.addEventListener[dom.Event]("focus", focusFn)
    keyTarget.addEventListener[dom.Event]("blur", blurFn)

  // ---- queries ----

  /** Whether the given key is currently held. */
  def isKeyDown(key: Key): Boolean = keysDown.has(key.code)

  /** Milliseconds the key has been held, or 0 if it is not currently down. */
  def keyHeldMs(key: Key, now: Double = js.Date.now()): Double =
    if keysDown.has(key.code) then now - keysDown.at(key.code) else 0.0

  /** Whether the given pointer button is currently down. Button-level (mouse);
    * for multi-touch counting use [[pointersDown]].
    */
  def isDown(button: PointerButton): Boolean = buttonsDown.has(button.key)

  /** Milliseconds the pointer button has been held, or 0 if it is not down. */
  def buttonHeldMs(button: PointerButton, now: Double = js.Date.now()): Double =
    if buttonsDown.has(button.key) then now - buttonsDown.at(button.key)
    else 0.0

  /** The currently-down pointers, main first (appearance order). Read-only — do
    * not mutate. Entries are reused across presses, so only read them this
    * frame.
    */
  def pointers: Arr[Pointer] = order

  /** How many pointers are currently down. */
  def pointersDown: Int = order.length

  /** The i-th currently-down pointer (0 = main), or `null` when `i` is out of
    * range. Callers must null-check.
    */
  def pointer(i: Int): Opt[Pointer] =
    if i >= 0 && i < order.length then order(i) else null

  /** Milliseconds the i-th pointer has been down, or 0 if there is no such
    * pointer.
    */
  def pointerDownMs(i: Int, now: Double = js.Date.now()): Double =
    val p = pointer(i)
    if p != null then now - p.downSince else 0.0

  /** X of the main (first) pointer, or its last position when none is down. */
  def pointerX: Double = if order.length > 0 then order(0).x else lastMainX

  /** Y of the main (first) pointer, or its last position when none is down. */
  def pointerY: Double = if order.length > 0 then order(0).y else lastMainY

  /** Whether the key target currently has focus. Useful for rendering a focus
    * affordance when the native outline has been cleared.
    */
  def hasFocus: Boolean = _hasFocus

  /** True when nothing is driving the scene: no keys and no pointers down. A
    * render loop can stop itself once this holds (after any easing has settled)
    * and restart from the `onActivity` callback.
    */
  def isIdle: Boolean =
    order.length == 0 &&
      js.Object.keys(keysDown.asInstanceOf[js.Object]).length == 0

  /** Remove all DOM listeners. Call when the canvas / scene goes away. */
  def dispose(): Unit =
    disposeKeyboard()
    disposePointer()
    keyTarget.removeEventListener[dom.Event]("focus", focusFn)
    keyTarget.removeEventListener[dom.Event]("blur", blurFn)
