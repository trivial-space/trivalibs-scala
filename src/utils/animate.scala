package trivalibs.utils.animation

import trivalibs.utils.js.*

import scala.scalajs.js
import scala.scalajs.js.JSNumberOps.*

class Animator(
    val frame: js.Function1[Double, Unit],
    var onFpsCallback: Opt[js.Function1[Double, Unit]] = null,
):
  private var frameCount = 0
  private var lastFpsTime = 0.0
  private var lastFpsLog = 0.0
  private var lastFrameTime = -1.0
  private var running = false

  def render(time: Double): Unit =
    // FPS calculation
    frameCount += 1
    if lastFpsTime == 0.0 then
      lastFpsTime = time
      lastFpsLog = time
    val fpsElapsed = time - lastFpsTime
    if fpsElapsed >= 1000.0 then
      val fps = frameCount * 1000.0 / fpsElapsed
      if time - lastFpsLog >= 1000.0 then
        log(fps.toFixed(1) + " FPS")
        lastFpsLog = time
        if onFpsCallback != null then onFpsCallback.get(fps)
      frameCount = 0
      lastFpsTime = time

    val delta = if lastFrameTime < 0.0 then 0.0 else time - lastFrameTime
    lastFrameTime = time

    frame(delta)

    if running then js.Dynamic.global.requestAnimationFrame(t => render(t))

  def start() =
    running = true
    js.Dynamic.global.requestAnimationFrame(t => render(t))

  def stop() =
    running = false
    lastFpsTime = 0.0
    lastFpsLog = 0.0
    lastFrameTime = -1.0
    frameCount = 0

  def onFps(newOnFps: Opt[js.Function1[Double, Unit]]) =
    onFpsCallback = newOnFps

/** Start a `requestAnimationFrame` loop calling `frame(tpf)` each frame, where
  * `tpf` is **milliseconds** since the last frame. Returns the [[Animator]] —
  * keep it if you want to `stop()` later (it logs FPS automatically). The
  * standard per-frame body updates uniforms then `painter.paint(...)` /
  * `painter.show(...)`.
  */
def animate(frame: js.Function1[Double, Unit]) =
  val animator = Animator(frame)
  animator.start()
  animator
