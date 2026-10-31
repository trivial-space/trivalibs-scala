package trivalibs.graphics.painter

import trivalibs.utils.js.*

type AnyLayer = Layer[?, ?]

/** Cached bind groups for one layer's static draw (no instances, no panel
  * runtime overrides), keyed by `(panelId, epoch)`.
  *
  * `panelId` in the key is **load-bearing for correctness**, not just cache
  * efficiency: epochs are per-panel counters, so two panels routinely share an
  * epoch value. A layer reused across panels must therefore key-mismatch on
  * `panelId` and rebuild — dropping `panelId` would let panel B reuse a bind
  * group that references panel A's textures (undefined rendering). Never remove
  * it from the key.
  *
  * `valueGroup` / `panelGroup` are null when the layer has no such group (mirrors
  * the build helpers' early-return, so the hit path skips `setBindGroup`).
  */
private[painter] final class LayerBindCache(
    val panelId: Int,
    val epoch: Int,
    val valueGroup: Opt[GPUBindGroup],
    val panelGroup: Opt[GPUBindGroup],
)

/** A full-screen post-processing pass attached to a [[Panel]], built from a
  * [[Painter.layerShade]]. Layers run in order after the panel's shapes, each
  * reading the previous pass's output (the panel auto-injects it as the first
  * panel-texture slot unless you bind that slot to an external panel). Bind
  * uniforms/panels with `.bind(...)`; add per-draw overrides via
  * `instances.add(...)`. Create through [[Painter.layer]]. See [[mipSource]] /
  * [[mipTarget]] / [[blendState]] for mip-chain and accumulation passes.
  *
  * Performance: a *static* draw (no instances, no panel runtime overrides) caches
  * its two GPU bind groups in [[cache]] to skip the per-frame rebuild. The cache
  * is keyed by `(panelId, epoch)` and is invalidated by (1) any `.bind(...)`
  * mutation, via [[onBindingsChanged]]; and (2) an `epoch` bump from
  * `Panel.swapPair` (ping-pong rotates slot 0) or an `ensureSize` realloc (new
  * textures). Auto-pong layers therefore rebuild every frame (their own swap
  * advances the epoch) — only genuinely static layers (e.g. a mip/composite
  * stack on a stable panel) get the fast path. Uniform *value* updates through a
  * shared `BufferBinding.set` don't touch the cache (the group references the
  * stable buffer handle).
  */
class Layer[U, P] private[painter] (
    val painter: Painter,
    val shade: Shade[U, P],
) extends Bindable[U, P]:
  /** Internal: blend mode, set via [[set]] / [[Painter.layer]]. */
  private[painter] var blendState: Opt[BlendState] = null

  /** Internal: source mip level, set via [[set]] / [[Painter.layer]]. */
  private[painter] var mipSource: Int = -1

  /** Internal: target mip level, set via [[set]] / [[Painter.layer]]. */
  private[painter] var mipTarget: Int = -1
  var bindings: BindingSlots = Arr()
  var panelBindings: Arr[Opt[PanelBinding]] = Arr()

  // Static bind-group cache (see [[LayerBindCache]]). Invalidated on binding
  // mutation via `onBindingsChanged`; epoch/panel mismatch invalidates in the
  // draw path.
  private[painter] var cache: Opt[LayerBindCache] = null

  override protected def onBindingsChanged(): Unit = cache = null

  /** Per-draw-call binding overrides; one rendered draw per added instance
    * (e.g. one additive draw per light). See [[InstanceList]].
    */
  val instances: InstanceList[U, P] = InstanceList[U, P](shade, painter)

  /** Whether this layer triggers an auto-pong pass: it has a panel-texture bind
    * group, but its first panel slot is *not* manually bound — so the painter
    * injects the previous pass's output there and ping-pongs. The single static
    * source of truth for the pong-dispatch decision, shared by pong allocation
    * ([[Panel.ensureSize]] via `needsPong`), the config-time MRT invariant, and
    * the `paintPanel` layer-loop dispatcher. The runtime `effectiveSrcView` gate
    * in `renderLayerOnPass` stays independent (a panel runtime binding can
    * override slot 0 even for a pong-dispatched layer).
    */
  private[painter] def autoPongsSlot0: Boolean =
    shade.panelBindGroupLayout != null &&
      (panelBindings.length == 0 || panelBindings(0) == null)

  /** Set [[blendState]] / [[mipSource]] / [[mipTarget]]; returns `this`. */
  def set(
      blendState: Maybe[Opt[BlendState]] = Maybe.Not,
      mipSource: Maybe[Int] = Maybe.Not,
      mipTarget: Maybe[Int] = Maybe.Not,
  ): this.type =
    blendState.foreach(v => this.blendState = v)
    mipSource.foreach(v => this.mipSource = v)
    mipTarget.foreach(v => this.mipTarget = v)
    this
