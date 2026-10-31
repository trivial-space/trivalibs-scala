package trivalibs.utils.events

import trivalibs.utils.js.*
import trivalibs.utils.numbers.NumExt.given

// ---------------------------------------------------------------------------
// Gestures — Layer 3: DOM-free interpreters built on the pointer snapshot of an
// `InputState`. Each is constructed over an InputState and advanced once per
// render frame via `update()`; the query members (`delta`, `holding`) are then
// pure, idempotent reads of the last frame's result. Driving `update()` from the
// render loop (rather than mutating on read) keeps the reads safe to call any
// number of times and from multiple consumers. Adding new multi-finger gestures
// (pan / pinch / rotate) means adding more of these, with no change to the relay
// or InputState.
//
// The "driver" of single-pointer gestures is the front-most pointer in the
// appearance-ordered active list whose initiating button is `Primary` (which
// covers the left mouse button and every touch contact). This gives free,
// jump-free hand-off: when the driving pointer lifts while others remain down,
// the next one is promoted and the gesture continues from its current position.
// ---------------------------------------------------------------------------

/** The current drag/hold driver: the first `Primary`-button pointer in the
  * active list, or `null` when none is down.
  */
private[events] def drivingPointer(pointers: Arr[Pointer]): Opt[Pointer] =
  var i = 0
  while i < pointers.length do
    val p = pointers(i)
    if p.button == PointerButton.Primary then return p
    i += 1
  null

/** Half-life (ms) of the drag-velocity smoothing that feeds the glide. Short
  * enough to follow a flick, long enough that one jittery final frame can't
  * fling the glide. Structural — not worth exposing.
  */
private inline val VelocitySmoothHalfLife = 40.0

/** Floor (px/ms) on the glide cut-off, so a `glideMinSpeed` of 0 still
  * terminates instead of trailing off asymptotically.
  */
private inline val MinGlideCutoff = 0.001

/** Drag gesture: the movement of the driving pointer during the last [[update]]
  * frame. Hand-off is seamless — when the driver changes (lift or promote),
  * that frame's delta is zero (a reseed) and movement resumes from the new
  * driver, so a hand-off never produces a jump.
  *
  * With `glideHalfLife > 0` the drag gets a swipe-like tail: releasing above
  * `glideMinSpeed` keeps [[delta]] flowing from the release velocity and fades
  * it out exponentially, instead of stopping dead. Pressing again cancels the
  * glide, and parking the pointer before releasing decays the velocity, so a
  * park-then-lift does not fling.
  *
  * Call [[update]] once per render frame, then read [[delta]] / [[dragging]].
  * Construct with `DragGesture(input)` for the plain hard-stop drag.
  *
  * @param glideMinSpeed
  *   px per second below which no glide starts, and at which a running glide
  *   ends.
  * @param glideHalfLife
  *   ms for the glide speed to halve. `0` disables the glide entirely.
  */
final class DragGesture private[events] (
    pointersOf: () => Arr[Pointer],
    glideMinSpeed: Double,
    glideHalfLife: Double,
):
  def this(
      input: InputState,
      glideMinSpeed: Double = 60.0,
      glideHalfLife: Double = 0.0,
  ) = this(() => input.pointers, glideMinSpeed, glideHalfLife)

  private var lastId: Opt[Double] = null
  private var lastX: Double = 0.0
  private var lastY: Double = 0.0
  private var _dx: Double = 0.0
  private var _dy: Double = 0.0
  // Smoothed drag velocity in px/ms, kept alive after release to drive the
  // glide. Only tracked when the glide is enabled.
  private var velX: Double = 0.0
  private var velY: Double = 0.0
  private var _gliding: Boolean = false

  private val glides = glideHalfLife > 0.0
  private val cutoff =
    val s = glideMinSpeed / 1000.0
    if s < MinGlideCutoff then MinGlideCutoff else s

  /** Whether a drag-eligible pointer is currently down. */
  def dragging: Boolean = drivingPointer(pointersOf()) != null

  /** Whether [[delta]] is currently coming from the post-release glide rather
    * than from a pointer. Always false without a `glideHalfLife`.
    */
  def gliding: Boolean = _gliding

  /** The driving pointer's movement during the last [[update]] frame; `(0, 0)`
    * when no driver is down or on the frame a driver change reseeded. With a
    * glide configured, this keeps reporting the fading movement after release.
    */
  def delta: (dx: Double, dy: Double) = (dx = _dx, dy = _dy)

  /** Advance one frame by `tpf` (ms): recompute [[delta]] from the driver's
    * movement, advance the baseline, and step the glide. Call once per render
    * frame before reading [[delta]].
    */
  def update(tpf: Double): Unit =
    val d = drivingPointer(pointersOf())
    if d == null then
      lastId = null
      if glides && (velX * velX + velY * velY).sqrt >= cutoff then
        val f = 0.5.pow(tpf / glideHalfLife)
        velX *= f
        velY *= f
        _gliding = true
        _dx = velX * tpf
        _dy = velY * tpf
      else
        velX = 0.0
        velY = 0.0
        _gliding = false
        _dx = 0.0
        _dy = 0.0
    else
      _gliding = false
      val p = d
      val sameDriver =
        lastId != null && p.id != null && lastId.get == p.id.get
      if sameDriver then
        _dx = p.x - lastX
        _dy = p.y - lastY
        if glides && tpf > 0.0 then
          // Frame-rate independent EMA toward this frame's instant velocity.
          val k = 1.0 - 0.5.pow(tpf / VelocitySmoothHalfLife)
          velX += (_dx / tpf - velX) * k
          velY += (_dy / tpf - velY) * k
      else
        // Reseed. A hand-off (lastId still set) keeps the velocity so the
        // glide survives it; a fresh press drops it, so a tap stops the glide.
        if lastId == null then
          velX = 0.0
          velY = 0.0
        _dx = 0.0
        _dy = 0.0
      lastId = p.id
      lastX = p.x
      lastY = p.y

/** Hold gesture: the driving pointer stayed within `holdRadius` px of its
  * origin for the initial `holdDelay` ms (measured by accumulating `tpf`),
  * which activates the hold. The stray check only gates that initialization
  * window — once activated, the hold stays held until release no matter how far
  * the pointer then moves, so a consumer can drive movement and look/drag
  * simultaneously. Straying during the init window disqualifies the hold for
  * that press. A fresh press or driver hand-off restarts the timer.
  *
  * Call [[update]] once per render frame with the frame's `tpf`, then read
  * [[holding]]. Construct with `HoldGesture(input, holdDelay, holdRadius)`.
  */
final class HoldGesture private[events] (
    pointersOf: () => Arr[Pointer],
    holdDelay: Double,
    holdRadius: Double,
):
  def this(
      input: InputState,
      holdDelay: Double = 400.0,
      holdRadius: Double = 5.0,
  ) =
    this(() => input.pointers, holdDelay, holdRadius)

  private var lastId: Opt[Double] = null
  private var heldMs: Double = 0.0 // time the current driver has been down
  private var strayed: Boolean = false // strayed during the init window
  private var activated: Boolean = false // hold has activated (until release)
  private var _holding: Boolean = false

  /** Whether the driver is currently held, as of the last [[update]]. */
  def holding: Boolean = _holding

  /** Advance one frame by `tpf` (ms): accumulate the driver's held-time and
    * resolve the init-window stray / activation. Call once per render frame
    * before reading [[holding]].
    */
  def update(tpf: Double): Unit =
    val d = drivingPointer(pointersOf())
    if d == null then
      lastId = null
      heldMs = 0.0
      strayed = false
      activated = false
      _holding = false
    else
      val p = d
      val pid = p.id
      val sameDriver = lastId != null && pid != null && lastId.get == pid
      if !sameDriver then
        // fresh press or hand-off: restart the hold timer
        lastId = pid
        heldMs = 0.0
        strayed = false
        activated = false
      heldMs += tpf
      if activated then _holding = true
      else if heldMs < holdDelay then
        // init window: stray beyond the radius disqualifies this press
        val dx = p.x - p.downX
        val dy = p.y - p.downY
        if (dx * dx + dy * dy).sqrt > holdRadius then strayed = true
        _holding = false
      else if strayed then _holding = false
      else
        activated = true
        _holding = true
