package trivalibs.graphics.painter

import trivalibs.graphics.buffers.BufferBinding
import trivalibs.graphics.buffers.UniformArray
import trivalibs.graphics.buffers.UniformArrayElem
import trivalibs.graphics.buffers.UniformValue
import trivalibs.graphics.math.cpu.Vec4
import trivalibs.graphics.painter.*
import trivalibs.utils.js.*

import scala.compiletime.summonFrom
import scala.scalajs.js

/** RGBA clear color for a render pass. A `(r, g, b, a)` tuple converts
  * implicitly, so `clearColor = (0.5, 0.6, 0.7, 1.0)` is still accepted
  * alongside `clearColor = Vec4(...)` or a shared `Vec4` constant.
  */
type ClearColor = Vec4

/** The eager view bundle for one panel texture:
  *   - `perMip` — one single-level view per mip level (length = `mipCount`).
  *     `perMip(0)` (aliased [[attach]]) is the render-attachment view; higher
  *     entries feed hand-built mip chains and `generateMipmaps`.
  *   - `sampling` — a full-chain view for shader sampling
  *     (`textureSampleLevel`).
  * Built once alongside the texture; travels with it through swaps.
  *
  * `blitGroup` is the `show` blit's bind group for this texture, built on
  * first use. It travels with the bundle too, so pong swaps keep one group per
  * texture and a reallocation (new bundles) starts empty — no keys or epochs.
  */
private[painter] final class TextureViewBundle(
    val perMip: Arr[GPUTextureView],
    val sampling: GPUTextureView,
):
  var blitGroup: Opt[GPUBindGroup] = null
  inline def attach: GPUTextureView = perMip(0)

/** A reference to one texture view of a [[Panel]], used as a shader input. Pick
  * which view via `panel.binding(index = …, mipLevel = …, depth = …)`:
  *   - `index` — render-target index (for MRT panels with multiple `formats`).
  *   - `mipLevel` — a specific mip (`-1` = the full sampling view / base).
  *   - `depth = true` — bind the panel's depth texture instead of color.
  *
  * A bare `Panel` passed as a binding value is shorthand for `panel.binding()`.
  */
class PanelBinding(
    val panel: Panel,
    val index: Int = 0,
    val mipLevel: Int = -1,
    val depth: Boolean = false,
) extends js.Object

type PanelBindingValue = BufferBinding[?, ?] | GPUSampler | Panel | PanelBinding

// Monotonic per-panel id source. Global (not device-scoped) so it needs no
// `painter` — every panel gets a process-unique id. Load-bearing for the layer
// bind-group cache key (see `LayerBindCache`): a layer reused across panels must
// key-mismatch and rebuild, never reuse another panel's bind group.
private var _panelIdSeq: Int = 0

/** A render target: owns one or more GPU textures and renders its ordered
  * [[shapes]] then ordered [[layers]] into them when passed to
  * [[Painter.paint]]. Create via [[Painter.panel]]; mutate later with [[set]].
  * Its output texture can be sampled by other passes (bind the panel itself, or
  * [[binding]] for a specific view). Present it with [[Painter.show]].
  *
  * Ping-pong (auto-pong layers) uses a second texture slot; slot 0 always holds
  * the live result, so samplers, `show()`, and mip generation read it directly.
  * MRT panels can't host auto-pong layers (ping-pong is single-target) — see
  * the gotchas guide.
  *
  * MSAA + auto-pong load semantics: the multisample shape pass resolves into
  * slot 0, then pong layers read/write and swap. On the next paint, if
  * `clearColor` is `null` (load), the shape pass loads the *previous frame's
  * post-layer* slot 0 — the same "load from last frame's result" semantic as a
  * no-pong load. Set a `clearColor` to start each frame fresh.
  */
class Panel private[painter] (val painter: Painter):
  private[painter] var specWidth: Int = 0
  private[painter] var specHeight: Int = 0
  private[painter] var clearColor: Opt[ClearColor] = null
  private[painter] var depthTest: Boolean = false
  private[painter] var multisample: Boolean = false
  private[painter] var mipLevels: Int = 1
  private[painter] var formats: Arr[TextureFormat] = Arr()
  private[painter] var shapes: Arr[AnyShape] = Arr()
  private[painter] var layers: Arr[AnyLayer] = Arr()
  // Public (not private[painter]) because the inline `bind` expands into
  // sketch code and references this field directly.
  var runtimeBindings: Dict[PanelBindingValue] = Dict[PanelBindingValue]()

  // Process-unique id, part of the layer bind-group cache key.
  private[painter] val panelId: Int =
    _panelIdSeq += 1
    _panelIdSeq
  // Bumped whenever slot-0's view changes under a cached layer bind group:
  // `swapPair` (rotates slot 0) and `ensureSize` realloc (new textures). The
  // layer cache treats an epoch mismatch as "invalidate + rebuild".
  private[painter] var bindEpoch: Int = 0

  // Slot-indexed render textures + their view bundles, lockstep. Length is
  // `formats.length` for a plain/MRT panel; a panel with auto-pong layers gets
  // one extra slot (index 1, the pong scratch — MRT + pong is forbidden, so pong
  // panels are always single-format, slot 0 = live result, slot 1 = scratch).
  private var _textures: Arr[GPUTexture] = Arr()
  private[painter] var views: Arr[TextureViewBundle] = Arr()
  private var _depthTexture: Opt[GPUTexture] = null
  private var _depthView: Opt[GPUTextureView] = null
  private var _depthSamplable: Boolean = false
  // MSAA depth can't be sampled as `texture_depth_2d`; when a multisample panel's
  // depth is sampled, an internal pass resolves it into this single-sample
  // texture (see `Painter.resolvePanelDepth`).
  private var _resolvedDepthTexture: Opt[GPUTexture] = null
  private var _resolvedDepthView: Opt[GPUTextureView] = null
  private var _needsDepthResolve: Boolean = false
  private var _msaaTextures: Arr[GPUTexture] = Arr()
  private var _msaaViews: Arr[GPUTextureView] = Arr()
  private var _width: Int = 0
  private var _height: Int = 0

  private[painter] def panelWidth: Int = _width
  private[painter] def panelHeight: Int = _height

  /** Effective number of mip levels: an explicit `mipLevels`, or — when
    * `mipLevels == 0` (set via `mips = true`) — the full chain computed from
    * the panel size.
    */
  private[painter] def mipLevelCount: Int =
    if mipLevels == 0 then
      val maxDim = Math.max(_width, _height)
      if maxDim <= 0 then 1
      else (Math.log(maxDim.toDouble) / Math.log(2.0)).toInt + 1
    else mipLevels

  private[painter] def effectiveFormats: Arr[TextureFormat] =
    if formats.length == 0 then Arr(painter.preferredFormat) else formats

  private[painter] def targetCount: Int = effectiveFormats.length

  // The live result view (slot 0). External samplers, `show()`, and mip
  // generation all read slot 0, so a per-pong-layer `swapPair` keeping slot 0
  // authoritative is all that's needed — no output-routing override.
  private[painter] def textureView: GPUTextureView = views(0).attach
  // The pong scratch render target (slot 1). Valid only on pong-configured
  // panels; read exclusively inside the ping-pong branch, which runs only when
  // a layer auto-pongs (⇒ `needsPong` ⇒ slot 1 allocated).
  private[painter] def pongTargetView: GPUTextureView = views(1).attach
  private[painter] def depthView: GPUTextureView = _depthView.get
  // True when this panel's (multisample) depth must be resolved to a sampleable
  // single-sample texture after the shape pass. The render-target depth view to
  // read is [[depthView]]; the resolve target is [[resolvedDepthTarget]].
  private[painter] def needsDepthResolve: Boolean = _needsDepthResolve
  private[painter] def resolvedDepthTarget: GPUTextureView =
    _resolvedDepthView.get
  private[painter] def msaaView: GPUTextureView = _msaaViews(0)
  private[painter] def msaaViewAt(index: Int): GPUTextureView = _msaaViews(
    index,
  )

  /** Rotate slot 0 ↔ slot 1 (texture + view bundle together) after a ping-pong
    * layer pass, so the just-written pong scratch (slot 1) becomes the live
    * result (slot 0). The view bundles travel with their textures, so nothing
    * else needs invalidating. Called per pong layer, keeping slot 0
    * authoritative at every point — the next layer, external samplers,
    * `show()`, and mip generation all read slot 0.
    */
  private[painter] def swapPair(): Unit =
    val t = _textures(0)
    _textures(0) = _textures(1)
    _textures(1) = t
    val sv = views(0)
    views(0) = views(1)
    views(1) = sv
    bindEpoch += 1

  // (Re)allocate the depth render texture (and, for a sampled MSAA panel, the
  // single-sample resolve texture). Shared by `ensureSize` and the lazy upgrade
  // in `depthSamplingView`.
  private def allocDepth(): Unit =
    if _depthTexture.notNull then _depthTexture.get.destroy()
    if _resolvedDepthTexture.notNull then _resolvedDepthTexture.get.destroy()
    val depthUsage =
      if _depthSamplable then
        GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING
      else GPUTextureUsage.RENDER_ATTACHMENT
    val depthTex = painter.device.createTexture(
      Obj.literal(
        size = Obj.literal(width = _width, height = _height),
        format = "depth24plus",
        usage = depthUsage,
        sampleCount = if multisample then 4 else 1,
      ),
    )
    _depthTexture = depthTex
    _depthView = depthTex.createView()
    if _depthSamplable && multisample then
      val resTex = painter.device.createTexture(
        Obj.literal(
          size = Obj.literal(width = _width, height = _height),
          format = "depth24plus",
          usage =
            GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING,
          sampleCount = 1,
        ),
      )
      _resolvedDepthTexture = resTex
      _resolvedDepthView = resTex.createView()
      _needsDepthResolve = true
    else
      _resolvedDepthTexture = null
      _resolvedDepthView = null
      _needsDepthResolve = false

  // Return a depth view usable as a `texture_depth_2d` shader input. The first
  // time a panel's depth is sampled it's re-allocated with TEXTURE_BINDING; for
  // a multisample panel that also allocates the single-sample resolve texture
  // (filled by `Painter.resolvePanelDepth` each frame) and we hand that back.
  private[painter] def depthSamplingView: GPUTextureView =
    if !_depthSamplable && _depthTexture.notNull then
      _depthSamplable = true
      allocDepth()
    if _needsDepthResolve then _resolvedDepthView.get else _depthView.get

  /** Make a [[PanelBinding]] selecting one view of this panel to feed another
    * pass as a texture: a render-target `index`, a specific `mipLevel` (`-1` =
    * sampling view), or the `depth` texture. A bare panel binds `binding()`.
    */
  def binding(
      index: Int = 0,
      mipLevel: Int = -1,
      depth: Boolean = false,
  ): PanelBinding =
    new PanelBinding(this, index, mipLevel, depth)

  /** Reconfigure this panel (same options as [[Painter.panel]]); returns `this`
    * for chaining. Only provided args change. `mips = true` sets
    * `mipLevels = 0` (full auto chain); the singular `shape`/`layer`/`format`
    * are sugar and the plural forms take precedence.
    */
  def set[S <: AnyShape, L <: AnyLayer](
      width: Maybe[Int] = Maybe.Not,
      height: Maybe[Int] = Maybe.Not,
      clearColor: Maybe[Opt[ClearColor]] = Maybe.Not,
      depthTest: Maybe[Boolean] = Maybe.Not,
      multisample: Maybe[Boolean] = Maybe.Not,
      mipLevels: Maybe[Int] = Maybe.Not,
      mips: Maybe[Boolean] = Maybe.Not,
      format: Maybe[TextureFormat] = Maybe.Not,
      formats: Maybe[Arr[TextureFormat]] = Maybe.Not,
      shape: Maybe[S] = Maybe.Not,
      shapes: Maybe[Arr[S]] = Maybe.Not,
      layer: Maybe[L] = Maybe.Not,
      layers: Maybe[Arr[L]] = Maybe.Not,
  ): this.type =
    width.foreach(v => this.specWidth = v)
    height.foreach(v => this.specHeight = v)
    // Copied, not aliased: `Vec4` is mutable, so retaining the caller's
    // instance would let a later `someColor.x = …` silently change this
    // panel's clear color.
    clearColor.foreach: v =>
      this.clearColor =
        if v.isNull then null
        else Vec4(v.get.x, v.get.y, v.get.z, v.get.w)
    depthTest.foreach(v => this.depthTest = v)
    multisample.foreach(v => this.multisample = v)
    mips.foreach(v => if v then this.mipLevels = 0)
    mipLevels.foreach(v => if v > 0 then this.mipLevels = v)
    formats.orMaybe(format.map(f => Arr(f))).foreach(v => this.formats = v)
    shapes
      .orMaybe(shape.map(s => Arr(s.asInstanceOf[AnyShape])))
      .foreach(v => this.shapes = v.asInstanceOf[Arr[AnyShape]])
    layers
      .orMaybe(layer.map(l => Arr(l)))
      .foreach(v => this.layers = v.asInstanceOf[Arr[AnyLayer]])
    // MRT panels can't host auto-pong layers: ping-pong is single-target by
    // design (one color attachment), so it can't post-process multiple render
    // targets. Compose a chain of single-format panels instead. Checked here —
    // the choke point for both initial construction and later reconfiguration.
    if this.formats.length > 1 && needsPong then
      throw jsError(
        "Panel: MRT (multiple formats) cannot host auto-pong layers. " +
          "Chain a single-format panel for post-processing instead.",
      )
    this

  private inline def processPanelEntry[N <: String & Singleton, V](
      pair: BindPair[N, V],
  ): Unit =
    inline pair.value match
      case sampler: GPUSampler =>
        runtimeBindings.set(pair.name, sampler)
      case bb: BufferBinding[?, ?] =>
        runtimeBindings.set(pair.name, bb)
      case pb: PanelBinding =>
        runtimeBindings.set(pair.name, pb)
      case p: Panel =>
        runtimeBindings.set(pair.name, p)
      // A uniform array bound as bare values. Unlike shape/layer bindings there
      // is no schema here to read a capacity from, so the buffer is sized from
      // the values: the array must be the full length the consuming shades
      // declare. Bind `values.asUniform[N]` instead to fill fewer than N.
      case values: Arr[t] =>
        summonFrom:
          case elem: UniformValue[`t`, f] =>
            summonFrom:
              case e: UniformArrayElem[`t`] =>
                val uv = UniformArray.valuesOnly[t, f](
                  elem,
                  UniformArray.rowsFor(values.length, e.lanes),
                )
                val existing = runtimeBindings.at(pair.name)
                // `:=`, not `set` — with T an Arr itself, the Arr overload of
                // `set` would win and then fail its UniformArray evidence.
                if runtimeBindings.has(pair.name)
                  && existing.isInstanceOf[BufferBinding[?, ?]]
                then existing.asInstanceOf[BufferBinding[Arr[t], ?]] := values
                else
                  val bb = BufferBinding[Arr[t], f](painter.device)(using uv)
                  bb := values
                  runtimeBindings.set(pair.name, bb)
      case rawValue =>
        if runtimeBindings.has(pair.name)
          && runtimeBindings.at(pair.name).isInstanceOf[BufferBinding[?, ?]]
        then
          runtimeBindings
            .at(pair.name)
            .asInstanceOf[BufferBinding[V, ?]]
            .set(rawValue)
        else
          summonFrom:
            case uv: UniformValue[V, f] =>
              val bb =
                BufferBinding[V, f](painter.device, rawValue)(using uv)
              runtimeBindings.set(pair.name, bb)

  /** Bind values shared by all of this panel's shapes/layers, by uniform/panel
    * field name: `panel.bind("name" := value, …)`. Values may be a
    * `BufferBinding`, a raw uniform value (auto-boxed), a `GPUSampler`, a
    * `Panel`, or a [[PanelBinding]]. Overloads take 1–8 pairs; chain `.bind`
    * for more. (Per-shape bindings still go on the shape; these are defaults.)
    */
  inline def bind[N1 <: String & Singleton, V1](
      e1: BindPair[N1, V1],
  ): this.type =
    processPanelEntry(e1)
    this

  inline def bind[N1 <: String & Singleton, V1, N2 <: String & Singleton, V2](
      e1: BindPair[N1, V1],
      e2: BindPair[N2, V2],
  ): this.type =
    processPanelEntry(e1)
    processPanelEntry(e2)
    this

  inline def bind[
      N1 <: String & Singleton,
      V1,
      N2 <: String & Singleton,
      V2,
      N3 <: String & Singleton,
      V3,
  ](
      e1: BindPair[N1, V1],
      e2: BindPair[N2, V2],
      e3: BindPair[N3, V3],
  ): this.type =
    processPanelEntry(e1)
    processPanelEntry(e2)
    processPanelEntry(e3)
    this

  inline def bind[
      N1 <: String & Singleton,
      V1,
      N2 <: String & Singleton,
      V2,
      N3 <: String & Singleton,
      V3,
      N4 <: String & Singleton,
      V4,
  ](
      e1: BindPair[N1, V1],
      e2: BindPair[N2, V2],
      e3: BindPair[N3, V3],
      e4: BindPair[N4, V4],
  ): this.type =
    processPanelEntry(e1)
    processPanelEntry(e2)
    processPanelEntry(e3)
    processPanelEntry(e4)
    this

  inline def bind[
      N1 <: String & Singleton,
      V1,
      N2 <: String & Singleton,
      V2,
      N3 <: String & Singleton,
      V3,
      N4 <: String & Singleton,
      V4,
      N5 <: String & Singleton,
      V5,
  ](
      e1: BindPair[N1, V1],
      e2: BindPair[N2, V2],
      e3: BindPair[N3, V3],
      e4: BindPair[N4, V4],
      e5: BindPair[N5, V5],
  ): this.type =
    processPanelEntry(e1)
    processPanelEntry(e2)
    processPanelEntry(e3)
    processPanelEntry(e4)
    processPanelEntry(e5)
    this

  inline def bind[
      N1 <: String & Singleton,
      V1,
      N2 <: String & Singleton,
      V2,
      N3 <: String & Singleton,
      V3,
      N4 <: String & Singleton,
      V4,
      N5 <: String & Singleton,
      V5,
      N6 <: String & Singleton,
      V6,
  ](
      e1: BindPair[N1, V1],
      e2: BindPair[N2, V2],
      e3: BindPair[N3, V3],
      e4: BindPair[N4, V4],
      e5: BindPair[N5, V5],
      e6: BindPair[N6, V6],
  ): this.type =
    processPanelEntry(e1)
    processPanelEntry(e2)
    processPanelEntry(e3)
    processPanelEntry(e4)
    processPanelEntry(e5)
    processPanelEntry(e6)
    this

  inline def bind[
      N1 <: String & Singleton,
      V1,
      N2 <: String & Singleton,
      V2,
      N3 <: String & Singleton,
      V3,
      N4 <: String & Singleton,
      V4,
      N5 <: String & Singleton,
      V5,
      N6 <: String & Singleton,
      V6,
      N7 <: String & Singleton,
      V7,
  ](
      e1: BindPair[N1, V1],
      e2: BindPair[N2, V2],
      e3: BindPair[N3, V3],
      e4: BindPair[N4, V4],
      e5: BindPair[N5, V5],
      e6: BindPair[N6, V6],
      e7: BindPair[N7, V7],
  ): this.type =
    processPanelEntry(e1)
    processPanelEntry(e2)
    processPanelEntry(e3)
    processPanelEntry(e4)
    processPanelEntry(e5)
    processPanelEntry(e6)
    processPanelEntry(e7)
    this

  inline def bind[
      N1 <: String & Singleton,
      V1,
      N2 <: String & Singleton,
      V2,
      N3 <: String & Singleton,
      V3,
      N4 <: String & Singleton,
      V4,
      N5 <: String & Singleton,
      V5,
      N6 <: String & Singleton,
      V6,
      N7 <: String & Singleton,
      V7,
      N8 <: String & Singleton,
      V8,
  ](
      e1: BindPair[N1, V1],
      e2: BindPair[N2, V2],
      e3: BindPair[N3, V3],
      e4: BindPair[N4, V4],
      e5: BindPair[N5, V5],
      e6: BindPair[N6, V6],
      e7: BindPair[N7, V7],
      e8: BindPair[N8, V8],
  ): this.type =
    processPanelEntry(e1)
    processPanelEntry(e2)
    processPanelEntry(e3)
    processPanelEntry(e4)
    processPanelEntry(e5)
    processPanelEntry(e6)
    processPanelEntry(e7)
    processPanelEntry(e8)
    this

  // Allocate a pong scratch iff some layer auto-pongs. Uses the shared
  // `Layer.autoPongsSlot0` predicate — the stricter form, so a layer that
  // manually binds slot 0 no longer over-allocates an unused pong texture.
  private def needsPong: Boolean =
    var i = 0
    while i < layers.length do
      if layers(i).autoPongsSlot0 then return true
      i += 1
    false

  // Build the eager per-slot view bundle for a freshly-allocated texture: one
  // single-level view per mip level plus one full-chain sampling view.
  private def buildViews(tex: GPUTexture, mipCount: Int): TextureViewBundle =
    val perMip = Arr[GPUTextureView]()
    var m = 0
    while m < mipCount do
      perMip.push(
        tex.createView(Obj.literal(baseMipLevel = m, mipLevelCount = 1)),
      )
      m += 1
    new TextureViewBundle(perMip, tex.createView())

  private[painter] def ensureSize(canvasW: Int, canvasH: Int): Unit =
    val targetW = if specWidth == 0 then canvasW else specWidth
    val targetH = if specHeight == 0 then canvasH else specHeight
    if targetW != _width || targetH != _height then
      // Destroy old textures (pong scratch, if any, lives in `_textures`).
      var d = 0
      while d < _textures.length do
        _textures(d).destroy()
        d += 1
      d = 0
      while d < _msaaTextures.length do
        _msaaTextures(d).destroy()
        d += 1
      // Depth textures are (re)allocated by `allocDepth()` below.

      _width = targetW
      _height = targetH

      val mipCount = mipLevelCount
      val fmts = effectiveFormats
      val hasPong = needsPong
      val colorUsage =
        GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING

      _textures = Arr()
      views = Arr()
      _msaaTextures = Arr()
      _msaaViews = Arr()

      var i = 0
      while i < fmts.length do
        val fmt = fmts(i)
        val tex = painter.device.createTexture(
          Obj.literal(
            size = Obj.literal(width = targetW, height = targetH),
            format = fmt.toJs,
            usage = colorUsage,
            mipLevelCount = mipCount,
          ),
        )
        _textures.push(tex)
        views.push(buildViews(tex, mipCount))

        if multisample then
          val msaaTex = painter.device.createTexture(
            Obj.literal(
              size = Obj.literal(width = targetW, height = targetH),
              format = fmt.toJs,
              sampleCount = 4,
              usage = GPUTextureUsage.RENDER_ATTACHMENT,
            ),
          )
          _msaaTextures.push(msaaTex)
          _msaaViews.push(msaaTex.createView())

        i += 1

      // Pong scratch (slot 1). MRT + pong is forbidden, so a pong panel is
      // single-format ⇒ this appends index 1 alongside the single main slot 0.
      if hasPong then
        val pongTex = painter.device.createTexture(
          Obj.literal(
            size = Obj.literal(width = targetW, height = targetH),
            format = fmts(0).toJs,
            usage = colorUsage,
            mipLevelCount = mipCount,
          ),
        )
        _textures.push(pongTex)
        views.push(buildViews(pongTex, mipCount))

      if depthTest then allocDepth()

      // Textures (and their views) are all fresh — any cached layer bind group
      // referencing the old ones is now stale.
      bindEpoch += 1
