package trivalibs.graphics.painter

import org.scalajs.dom.HTMLCanvasElement
import trivalibs.bufferdata.StructArray
import trivalibs.graphics.buffers.*
import trivalibs.graphics.geometry.BufferedGeometry
import trivalibs.graphics.math.*
import trivalibs.graphics.math.cpu.*
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.painter.*
import trivalibs.graphics.shader.*
import trivalibs.graphics.shader.dsl.LayerProgram
import trivalibs.graphics.shader.dsl.Program
import trivalibs.utils.events.CanvasInput
import trivalibs.utils.events.interactiveCanvas
import trivalibs.utils.js.*

import scala.compiletime.erasedValue
import scala.scalajs.js

private val LAYER_VERT_BODY =
  """  let x = f32((in.vertex_index << 1u) & 2u) * 2.0 - 1.0;
  let y = f32(in.vertex_index & 2u) * 2.0 - 1.0;
  out.uv = vec2f(x * 0.5 + 0.5, 0.5 - y * 0.5);
  out.position = vec4f(x, y, 0.0, 1.0);"""

private val BLIT_WGSL = """
struct VsOut {
  @builtin(position) pos: vec4f,
  @location(0) uv: vec2f,
}

@vertex
fn vs_main(@builtin(vertex_index) vi: u32) -> VsOut {
  let x = f32((vi << 1u) & 2u) * 2.0 - 1.0;
  let y = f32(vi & 2u) * 2.0 - 1.0;
  var out: VsOut;
  out.pos = vec4f(x, y, 0.0, 1.0);
  out.uv = vec2f(x * 0.5 + 0.5, 0.5 - y * 0.5);
  return out;
}

@group(0) @binding(0) var blit_texture: texture_2d<f32>;
@group(0) @binding(1) var blit_sampler: sampler;

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4f {
  return textureSample(blit_texture, blit_sampler, in.uv);
}
"""

// Resolves a multisample depth attachment into a single-sample depth texture so
// it can be sampled as `texture_depth_2d`. Reads subsample 0 (cheap; adequate
// for depth-driven effects) and writes it via `frag_depth`.
private val DEPTH_RESOLVE_WGSL = """
@group(0) @binding(0) var ms_depth: texture_depth_multisampled_2d;

@vertex
fn vs_main(@builtin(vertex_index) vi: u32) -> @builtin(position) vec4f {
  let x = f32((vi << 1u) & 2u) * 2.0 - 1.0;
  let y = f32(vi & 2u) * 2.0 - 1.0;
  return vec4f(x, y, 0.0, 1.0);
}

@fragment
fn fs_main(@builtin(position) pos: vec4f) -> @builtin(frag_depth) f32 {
  return textureLoad(ms_depth, vec2i(pos.xy), 0);
}
"""

/** Central registry and frame driver for WebGPU rendering.
  *
  * A `Painter` owns the GPU device and a [[Surface]] (a browser canvas, or a
  * [[PixelFrames]] / [[PixelImage]] container offline), and is the factory for
  * every rendering resource: [[shade]]/[[layerShade]] (shaders), [[form]]
  * (geometry), [[shape]] (drawable), [[layer]] (full-screen post-processing
  * pass), [[panel]] (render target), [[binding]] (uniform buffers) and
  * samplers. Per frame you call [[paint]] to render panels off-screen and
  * [[show]] to present one to the surface.
  *
  * Don't construct directly — use [[Painter.init]] in the browser, which sets
  * up the device and runs your code inside a ready painter:
  * {{{
  * Painter.init(canvas): painter =>
  *   val shade = painter.layerShade[(time: Float)]: program => ...
  *   val panel = painter.panel(layer = painter.layer(shade).bind("time" := t))
  *   animate: tpf =>
  *     painter.paint(panel)
  *     painter.show(panel)
  * }}}
  * Offline, or with a caller-supplied GPU, use [[Painter.create]].
  */
class Painter private[painter] (
    val device: GPUDevice,
    val surface: Surface,
    /** The adapter this painter's device came from; null when the painter was
      * handed a device.
      */
    val adapterInfo: Opt[GPUAdapterInfo],
):
  val queue: GPUQueue = device.queue

  /** The surface's format: the default panel format and the `show` target. */
  val preferredFormat: TextureFormat = surface.format

  private val pipelineCache = Dict[GPURenderPipeline]()
  private var nextShadeId = 0
  private val resizeCallbacks = Arr[(Double, Double) => Unit]()
  private var readback: Opt[Readback] = null

  surface.onResize((w, h) => fireResize(w, h))

  /** The browser canvas this painter presents to. Throws on a painter without a
    * [[CanvasSurface]] (offline containers).
    */
  def canvas: HTMLCanvasElement = surface match
    case s: CanvasSurface => s.canvas
    case _ => throw jsError("Painter.canvas: this painter has no canvas")

  private[painter] def ensureReadback(): Readback =
    if readback.isNull then readback = Readback(device)
    readback.get

  /** Register a callback invoked with `(width, height)` **immediately** (with
    * the current surface size) and again on every surface resize. Use it to
    * update aspect-dependent uniforms (camera aspect, resolution bindings).
    */
  def onResize(cb: (Double, Double) => Unit): Unit =
    resizeCallbacks.push(cb)
    cb(surface.width, surface.height)

  private[painter] def fireResize(w: Double, h: Double): Unit =
    var k = 0
    while k < resizeCallbacks.length do
      resizeCallbacks(k)(w, h)
      k += 1

  /** Current surface width in physical pixels. `Double`, to match [[onResize]]
    * — sizes are almost always used in ratios and float math.
    */
  def width: Double = surface.width

  /** Current surface height in physical pixels. `Double`, to match [[onResize]]
    * — sizes are almost always used in ratios and float math.
    */
  def height: Double = surface.height

  /** Resolves once every pending readback has landed in its container; only
    * then is a [[PixelFrames]] / [[PixelImage]] target complete. Not a step of
    * normal rendering: with nothing to read back it resolves immediately.
    */
  def awaitReadback(): js.Promise[Unit] =
    if readback.isNull then js.Promise.resolve[Unit](())
    else readback.get.awaitAll()

  /** Convenience: set up a `CanvasInput` for this painter's canvas with the
    * interactive-canvas defaults (focusable, cleared outline, focus on
    * pointer-down, initial focus) and the default drag/hold gesture preset. See
    * `interactiveCanvas`.
    */
  def input(
      initialFocus: Boolean = true,
      holdDelay: Double = 400.0,
      holdRadius: Double = 5.0,
      suppressContextMenu: Boolean = true,
      onActivity: Maybe[js.Function0[Unit]] = Maybe.Not,
      dragGlideHalfLife: Double = 90.0,
      dragGlideMinSpeed: Double = 50.0,
  ): CanvasInput =
    interactiveCanvas(
      canvas,
      initialFocus,
      holdDelay,
      holdRadius,
      suppressContextMenu,
      onActivity,
      dragGlideHalfLife,
      dragGlideMinSpeed,
    )

  // =========================================================================
  // Shared samplers
  // =========================================================================

  /** Create a custom sampler. Use a `mipmapFilter` of `Linear` when sampling a
    * mip-mapped panel texture with trilinear filtering. Set `addressMode` to
    * `AddressMode.Repeat` to seamlessly tile a repeat-sampled texture; it
    * applies to both U and V unless overridden per axis by `addressModeU` /
    * `addressModeV` (when given, those take precedence over `addressMode`).
    */
  def sampler(
      magFilter: FilterMode = FilterMode.Nearest,
      minFilter: FilterMode = FilterMode.Nearest,
      mipmapFilter: FilterMode = FilterMode.Nearest,
      addressMode: AddressMode = AddressMode.ClampToEdge,
      addressModeU: Maybe[AddressMode] = Maybe.Not,
      addressModeV: Maybe[AddressMode] = Maybe.Not,
  ): GPUSampler =
    device.createSampler(
      Obj.literal(
        magFilter = magFilter.toJs,
        minFilter = minFilter.toJs,
        mipmapFilter = mipmapFilter.toJs,
        addressModeU = addressModeU.orElse(addressMode).toJs,
        addressModeV = addressModeV.orElse(addressMode).toJs,
      ),
    )

  /** Shared nearest-filter sampler. Pass directly as a binding value (`"samp"
    * := painter.samplerNearest`).
    */
  lazy val samplerNearest: GPUSampler = sampler()

  /** Shared linear-filter sampler. Pass directly as a binding value (`"samp" :=
    * painter.samplerLinear`).
    */
  lazy val samplerLinear: GPUSampler = sampler(
    magFilter = FilterMode.Linear,
    minFilter = FilterMode.Linear,
    mipmapFilter = FilterMode.Linear,
  )

  // =========================================================================
  // Shade factory
  // =========================================================================

  /** Build a vertex+fragment shader from the DSL.
    *
    * Type params are named-tuple schemas (field order = layout index, field
    * name = WGSL variable name):
    *   - `A` — vertex attributes, e.g. `(position: Vec3, uv: Vec2)`
    *   - `V` — varyings passed vertex→fragment
    *   - `U` — uniforms; wrap fields in `VertexUniform[T]`/`FragmentUniform[T]`
    *     to restrict stage visibility (bare `T` = both stages)
    *
    * Provide the body via the `program` builder
    * (`program.vert`/`program.frag`). Overloads add `P` (panel-texture
    * bindings) and `FO` (custom fragment output / MRT); a separate overload
    * takes raw WGSL strings instead of the DSL.
    */
  inline def shade[A, V, U](
      build: Program[A, V, U, EmptyTuple, FragOut] => Unit,
  ): Shade[U, EmptyTuple] =
    val program = new Program[A, V, U, EmptyTuple, FragOut]
    build(program)
    shadeFromWgsl[A, V, U, EmptyTuple](
      program.vertBodyStr,
      program.fragBodyStr,
      program.helperFnsStr,
    )

  /** [[shade]] with panel-texture bindings `P` (a named tuple of
    * `FragmentPanel`/`VertexPanel` markers), read in the shader via
    * `ctx.textures.<name>`.
    */
  inline def shade[A, V, U, P](
      build: Program[A, V, U, P, FragOut] => Unit,
  ): Shade[U, P] =
    val program = new Program[A, V, U, P, FragOut]
    build(program)
    shadeFromWgsl[A, V, U, P](
      program.vertBodyStr,
      program.fragBodyStr,
      program.helperFnsStr,
    )

  /** [[shade]] with panels `P` and a custom fragment-output schema `FO` (a
    * named tuple of multiple `Vec4` targets) for multiple-render-target (MRT)
    * passes, e.g. a deferred G-buffer. Each `FO` field maps to a panel format.
    */
  inline def shade[A, V, U, P, FO](
      build: Program[A, V, U, P, FO] => Unit,
  ): Shade[U, P] =
    val program = new Program[A, V, U, P, FO]
    build(program)
    shadeFromWgslFO[A, V, U, P, FO](
      program.vertBodyStr,
      program.fragBodyStr,
      program.helperFnsStr,
    )

  /** [[shade]] from raw WGSL strings instead of the DSL. The
    * `@group`/`@binding`/ `@location` decorations and the `in`/`out`/uniform
    * struct declarations are still generated from the `A`/`V`/`U` schemas —
    * write only the body, using `in.<field>`, `out.<field>`, and each uniform
    * field by name.
    */
  inline def shade[A, V, U](
      vertWgsl: String,
      fragWgsl: String,
  ): Shade[U, EmptyTuple] =
    shadeFromWgsl[A, V, U, EmptyTuple](vertWgsl, fragWgsl, "")

  private inline def buildIndices[T]: Dict[Int] =
    val names = derive.fieldNames[T]
    val dict = Dict[Int]()
    var i = 0
    while i < names.length do
      dict.asInstanceOf[js.Dynamic].updateDynamic(names(i))(i)
      i += 1
    dict

  private inline def shadeFromWgsl[A, V, U, P](
      vertWgsl: String,
      fragWgsl: String,
      helperFns: String,
  ): Shade[U, P] =
    val id = nextShadeId
    nextShadeId += 1
    val uniformIndices = buildIndices[U]
    val panelIndices = buildIndices[P]

    val panelDecls = derive.generatePanelDeclarations[P]

    inline erasedValue[U] match
      case _: EmptyTuple =>
        val sd =
          Shader.full[A, V, EmptyTuple, VertIn, VertOut, FragIn, FragOut](
            vertWgsl,
            fragWgsl,
            helperFns,
          )
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val vbl = sd.vertexBufferLayout
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val bgls =
          if panelBgl.notNull then Arr[GPUBindGroupLayout](panelBgl)
          else Arr[GPUBindGroupLayout]()
        val pl = layouts.createPipelineLayout(device, bgls)
        Shade[U, P](
          id,
          module,
          vbl,
          null,
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )
      case _ =>
        type Wrapped = (values: U)
        val sd = Shader.full[A, V, Wrapped, VertIn, VertOut, FragIn, FragOut](
          vertWgsl,
          fragWgsl,
          helperFns,
        )
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val vbl = sd.vertexBufferLayout
        val (bgls, _) = sd.createPipelineLayout(device)
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val allBgls =
          if panelBgl.notNull then bgls ++ Arr[GPUBindGroupLayout](panelBgl)
          else bgls
        val pl = layouts.createPipelineLayout(device, allBgls)
        Shade[U, P](
          id,
          module,
          vbl,
          bgls(0),
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )

  private inline def shadeFromWgslFO[A, V, U, P, FO](
      vertWgsl: String,
      fragWgsl: String,
      helperFns: String,
  ): Shade[U, P] =
    val id = nextShadeId
    nextShadeId += 1
    val uniformIndices = buildIndices[U]
    val panelIndices = buildIndices[P]

    val panelDecls = derive.generatePanelDeclarations[P]

    inline erasedValue[U] match
      case _: EmptyTuple =>
        val sd = Shader.full[A, V, EmptyTuple, VertIn, VertOut, FragIn, FO](
          vertWgsl,
          fragWgsl,
          helperFns,
        )
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val vbl = sd.vertexBufferLayout
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val bgls =
          if panelBgl.notNull then Arr[GPUBindGroupLayout](panelBgl)
          else Arr[GPUBindGroupLayout]()
        val pl = layouts.createPipelineLayout(device, bgls)
        Shade[U, P](
          id,
          module,
          vbl,
          null,
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )
      case _ =>
        type Wrapped = (values: U)
        val sd = Shader.full[A, V, Wrapped, VertIn, VertOut, FragIn, FO](
          vertWgsl,
          fragWgsl,
          helperFns,
        )
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val vbl = sd.vertexBufferLayout
        val (bgls, _) = sd.createPipelineLayout(device)
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val allBgls =
          if panelBgl.notNull then bgls ++ Arr[GPUBindGroupLayout](panelBgl)
          else bgls
        val pl = layouts.createPipelineLayout(device, allBgls)
        Shade[U, P](
          id,
          module,
          vbl,
          bgls(0),
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )

  // =========================================================================
  // LayerShade factory — fullscreen triangle with user fragment shader
  // =========================================================================

  /** Build a fragment-only shader for a full-screen pass (used by [[layer]]).
    *
    * There is no user vertex stage — Painter supplies a built-in full-screen
    * triangle and the fragment context exposes `ctx.in.uv` (a `Vec2` in `[0,1]`
    * screen space). This is the simplest overload, with no uniforms or panel
    * inputs, no custom output.
    */
  inline def layerShade(
      build: LayerProgram[EmptyTuple, EmptyTuple, FragOut] => Unit,
  ): Shade[EmptyTuple, EmptyTuple] =
    val program = new LayerProgram[EmptyTuple, EmptyTuple, FragOut]
    build(program)
    layerShadeFromWgsl[EmptyTuple, EmptyTuple](
      program.fragBodyStr,
      program.helperFnsStr,
    )

  /** Build a fragment-only shader for a full-screen pass (used by [[layer]]).
    *
    * There is no user vertex stage — Painter supplies a built-in full-screen
    * triangle and the fragment context exposes `ctx.in.uv` (a `Vec2` in `[0,1]`
    * screen space). Because every uniform is fragment-stage by construction,
    * the `U` fields need no `FragmentUniform[_]` wrapper — bare types are
    * auto-wrapped. Overloads add `P` (panel-texture inputs) and `FO` (custom
    * output).
    */
  inline def layerShade[U](
      build: LayerProgram[U, EmptyTuple, FragOut] => Unit,
  ): Shade[U, EmptyTuple] =
    val program = new LayerProgram[U, EmptyTuple, FragOut]
    build(program)
    layerShadeFromWgsl[U, EmptyTuple](program.fragBodyStr, program.helperFnsStr)

  /** [[layerShade]] with panel-texture inputs `P` (named tuple of
    * `FragmentPanel`), sampled via `ctx.textures.<name>`. The common
    * post-processing shape: read one or more panels, write a color.
    */
  inline def layerShade[U, P](
      build: LayerProgram[U, P, FragOut] => Unit,
  ): Shade[U, P] =
    val program = new LayerProgram[U, P, FragOut]
    build(program)
    layerShadeFromWgsl[U, P](program.fragBodyStr, program.helperFnsStr)

  /** [[layerShade]] with panels `P` and a custom multi-target output schema
    * `FO`.
    */
  inline def layerShade[U, P, FO](
      build: LayerProgram[U, P, FO] => Unit,
  ): Shade[U, P] =
    val program = new LayerProgram[U, P, FO]
    build(program)
    layerShadeFromWgslFO[U, P, FO](
      program.fragBodyStr,
      program.helperFnsStr,
    )

  private inline def layerShadeFromWgsl[U, P](
      fragWgsl: String,
      helperFns: String,
  ): Shade[U, P] =
    val id = nextShadeId
    nextShadeId += 1
    val uniformIndices = buildIndices[U]
    val panelIndices = buildIndices[P]
    type VBI = (vertex_index: BuiltinVertexIndex)
    val panelDecls = derive.generatePanelDeclarations[P]
    inline erasedValue[U] match
      case _: EmptyTuple =>
        val sd = Shader.full[
          EmptyTuple,
          (uv: Vec2),
          EmptyTuple,
          VBI,
          VertOut,
          FragIn,
          FragOut,
        ](LAYER_VERT_BODY, fragWgsl, helperFns)
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val bgls =
          if panelBgl.notNull then Arr[GPUBindGroupLayout](panelBgl)
          else Arr[GPUBindGroupLayout]()
        val pl = layouts.createPipelineLayout(device, bgls)
        Shade[U, P](
          id,
          module,
          null,
          null,
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )
      case _ =>
        import scala.NamedTuple.AnyNamedTuple
        type FragU = NamedTuple.Map[U & AnyNamedTuple, derive.WrapFragment]
        type Wrapped = (values: FragU)
        val sd = Shader.full[
          EmptyTuple,
          (uv: Vec2),
          Wrapped,
          VBI,
          VertOut,
          FragIn,
          FragOut,
        ](LAYER_VERT_BODY, fragWgsl, helperFns)
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val (bgls, _) = sd.createPipelineLayout(device)
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val allBgls =
          if panelBgl.notNull then bgls ++ Arr[GPUBindGroupLayout](panelBgl)
          else bgls
        val pl = layouts.createPipelineLayout(device, allBgls)
        Shade[U, P](
          id,
          module,
          null,
          bgls(0),
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )

  private inline def layerShadeFromWgslFO[U, P, FO](
      fragWgsl: String,
      helperFns: String,
  ): Shade[U, P] =
    val id = nextShadeId
    nextShadeId += 1
    val uniformIndices = buildIndices[U]
    val panelIndices = buildIndices[P]
    type VBI = (vertex_index: BuiltinVertexIndex)
    val panelDecls = derive.generatePanelDeclarations[P]
    inline erasedValue[U] match
      case _: EmptyTuple =>
        val sd = Shader.full[
          EmptyTuple,
          (uv: Vec2),
          EmptyTuple,
          VBI,
          VertOut,
          FragIn,
          FO,
        ](LAYER_VERT_BODY, fragWgsl, helperFns)
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val bgls =
          if panelBgl.notNull then Arr[GPUBindGroupLayout](panelBgl)
          else Arr[GPUBindGroupLayout]()
        val pl = layouts.createPipelineLayout(device, bgls)
        Shade[U, P](
          id,
          module,
          null,
          null,
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )
      case _ =>
        import scala.NamedTuple.AnyNamedTuple
        type FragU = NamedTuple.Map[U & AnyNamedTuple, derive.WrapFragment]
        type Wrapped = (values: FragU)
        val sd = Shader.full[
          EmptyTuple,
          (uv: Vec2),
          Wrapped,
          VBI,
          VertOut,
          FragIn,
          FO,
        ](LAYER_VERT_BODY, fragWgsl, helperFns)
        val baseWgsl = sd.generateWGSL
        val wgsl =
          if panelDecls.isEmpty then baseWgsl else s"$baseWgsl\n\n$panelDecls"
        log(wgsl)
        val module = device.createShaderModule(Obj.literal(code = wgsl))
        val (bgls, _) = sd.createPipelineLayout(device)
        val panelBgl = layouts.createPanelBindGroupLayout[P](device)
        val allBgls =
          if panelBgl.notNull then bgls ++ Arr[GPUBindGroupLayout](panelBgl)
          else bgls
        val pl = layouts.createPipelineLayout(device, allBgls)
        Shade[U, P](
          id,
          module,
          null,
          bgls(0),
          panelBgl,
          pl,
          false,
          uniformIndices,
          panelIndices,
        )

  // =========================================================================
  // Form factory
  // =========================================================================

  /** Create a [[Form]] (vertex buffers + topology). Provide geometry as either
    * a `BufferedGeometry` (from the geometry/mesh helpers, supports indices) or
    * a raw `StructArray` of vertices (from `allocateAttribs`). `geometries` /
    * `verticesAll` take an `Arr` of those instead: several geometry buffers
    * drawn in sequence as one shape (see [[Form]]). `topology` defaults to
    * triangle-list and `frontFace` to CCW. Reassign later with `form.set(...)`,
    * which reuses the GPU buffers while the new data fits.
    */
  def form[F <: Tuple](
      geometry: Maybe[BufferedGeometry[F]] = Maybe.Not,
      vertices: Maybe[StructArray[F]] = Maybe.Not,
      geometries: Maybe[Arr[BufferedGeometry[F]]] = Maybe.Not,
      verticesAll: Maybe[Arr[StructArray[F]]] = Maybe.Not,
      topology: Maybe[PrimitiveTopology] = Maybe.Not,
      frontFace: Maybe[FrontFace] = Maybe.Not,
  ): Form = Form(this).set(
    geometry,
    vertices,
    geometries,
    verticesAll,
    topology,
    frontFace,
  )

  // =========================================================================
  // Binding factory
  // =========================================================================

  /** Create a uniform `BufferBinding` pre-set to `value` (e.g.
    * `painter.binding(Vec3(1, 0, 0))`, `painter.binding(0.0f)`). Update later
    * with `b.set(v)` / `b := v` / `b.update(ref => …)`; "const-ness" is by
    * convention. Supports any `T` with a `UniformLayout` (Float, Double,
    * Vec2-4, Mat2-4).
    */
  inline def binding[T: UniformLayout as ul](
      value: T,
  ): BufferBinding[T, ul.Fields] =
    BufferBinding[T](device, value)

  /** Create an uninitialised uniform `BufferBinding` of type `T`, e.g.
    * `painter.binding[Mat4]`. Set its value before first render.
    */
  inline def binding[T: UniformLayout as ul]: BufferBinding[T, ul.Fields] =
    BufferBinding[T](device)

  // =========================================================================
  // Shape factory
  // =========================================================================

  /** Create a drawable [[Shape]] from a [[Form]] and a [[Shade]]. Bind its
    * uniforms/panels with `.bind("name" := value, …)` and add per-draw
    * overrides via `shape.instances.add(...)`. `cullMode`/`blendState` are
    * optional — see [[Shape.set]] for their defaults.
    */
  def shape[U, P](
      form: Form,
      shade: Shade[U, P],
      cullMode: Maybe[CullMode] = Maybe.Not,
      blendState: Maybe[Opt[BlendState]] = Maybe.Not,
  ): Shape[U, P] =
    Shape[U, P](this, form, shade).set(cullMode, blendState)

  // =========================================================================
  // Layer factory
  // =========================================================================

  /** Create a [[Layer]] — a full-screen post-processing pass built from a
    * [[layerShade]]. Attach layers to a [[panel]]; they run in order after the
    * panel's shapes, each reading the previous pass's output.
    *
    *   - `blendState` — e.g. `BlendState.Additive` to accumulate (bloom
    *     upsample, deferred lights).
    *   - `mipSource`/`mipTarget` — render this pass into a specific mip level
    *     of the panel's texture (`-1` = disabled). When any layer on a panel
    *     sets `mipTarget`, the painter skips automatic mipmap generation for
    *     that panel, so the layers build the mip chain by hand
    *     (downsample/upsample).
    */
  def layer[U, P](
      shade: Shade[U, P],
      blendState: Maybe[Opt[BlendState]] = Maybe.Not,
      mipSource: Maybe[Int] = Maybe.Not,
      mipTarget: Maybe[Int] = Maybe.Not,
  ): Layer[U, P] =
    Layer[U, P](this, shade).set(blendState, mipSource, mipTarget)

  // =========================================================================
  // Panel factory
  // =========================================================================

  /** Create a [[Panel]] — a render target holding ordered shapes then ordered
    * layers, rendered by [[paint]]. Defaults to the canvas size and the
    * preferred format; all options are also settable later via `panel.set`.
    *
    *   - `clearColor` — a [[ClearColor]] (`Vec4`); an `(r, g, b, a)` tuple
    *     converts implicitly. Omit to load (not clear) previous contents.
    *   - `shape`/`shapes`, `layer`/`layers` — singular sugar for one item; the
    *     plural form takes precedence.
    *   - `width`/`height` — fixed size; omit to track the canvas.
    *   - `format`/`formats` — a [[TextureFormat]] (e.g.
    *     `TextureFormat.Rgba16Float` for HDR); `formats` (multiple) declares an
    *     MRT target matching a shader's `FO` output.
    *   - `mips = true` (or `mipLevels = N`) — allocate a mip chain (see
    *     [[layer]] for hand-built chains vs auto-generation).
    *   - `depthTest`, `multisample` — enable depth buffering / MSAA.
    *
    * Bind panel textures as inputs to other passes via `otherPanel`/`layer`
    * bindings and `panel.binding(index/mipLevel/depth)`.
    */
  def panel[S <: AnyShape, L <: AnyLayer](
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
  ): Panel = Panel(this).set(
    width = width,
    height = height,
    clearColor = clearColor,
    depthTest = depthTest,
    multisample = multisample,
    mipLevels = mipLevels,
    mips = mips,
    format = format,
    formats = formats,
    shape = shape,
    shapes = shapes,
    layer = layer,
    layers = layers,
  )

  // =========================================================================
  // draw() — direct-to-canvas rendering
  // =========================================================================

  /** Render a single [[Shape]] straight to the canvas, bypassing panels — a
    * shortcut for simple demos. Pass `clearColor` to clear first; omit it on
    * subsequent calls to blend on top of what's already there:
    * {{{
    * painter.draw(shape1, clearColor = (0.1, 0.1, 0.1, 1.0))
    * painter.draw(shape2) // blends over shape1
    * }}}
    * For multi-pass / off-screen rendering use [[panel]] + [[paint]] +
    * [[show]]. On a container painter `draw` output is not read back; draw into
    * a panel and `show` it instead.
    */
  def draw(
      shape: AnyShape,
      clearColor: Opt[ClearColor] = null,
  ): Unit =
    val encoder = device.createCommandEncoder()
    val textureView = surface.currentTexture().createView()

    val colorAttachment =
      if clearColor.notNull then
        val c = clearColor.get
        Obj.literal(
          view = textureView,
          loadOp = "clear",
          storeOp = "store",
          clearValue = Obj.literal(r = c.r, g = c.g, b = c.b, a = c.a),
        )
      else
        Obj.literal(
          view = textureView,
          loadOp = "load",
          storeOp = "store",
        )

    val pass = encoder.beginRenderPass(
      Obj.literal(colorAttachments = Arr(colorAttachment)),
    )

    renderShapeOnPass(pass, shape)
    pass.end()

    queue.submit(Arr(encoder.finish()))

  // =========================================================================
  // paint() / show() — Panel-based rendering
  // =========================================================================

  private def paintPanel(panel: Panel): Unit =
    panel.ensureSize(surface.width, surface.height)
    val msaa = panel.multisample

    // Step 1: render shapes into main texture(s)
    val encoder = device.createCommandEncoder()
    val panelFormats = panel.effectiveFormats

    val colorAttachments = Arr[js.Dynamic]()
    var t = 0
    while t < panel.targetCount do
      val attachment =
        if panel.clearColor.notNull then
          val c = panel.clearColor.get
          if msaa then
            Obj.literal(
              view = panel.msaaViewAt(t),
              resolveTarget = panel.views(t).attach,
              loadOp = "clear",
              storeOp = "discard",
              clearValue = Obj.literal(r = c.r, g = c.g, b = c.b, a = c.a),
            )
          else
            Obj.literal(
              view = panel.views(t).attach,
              loadOp = "clear",
              storeOp = "store",
              clearValue = Obj.literal(r = c.r, g = c.g, b = c.b, a = c.a),
            )
        else if msaa then
          Obj.literal(
            view = panel.msaaViewAt(t),
            resolveTarget = panel.views(t).attach,
            loadOp = "load",
            storeOp = "store",
          )
        else
          Obj.literal(
            view = panel.views(t).attach,
            loadOp = "load",
            storeOp = "store",
          )
      colorAttachments.push(attachment)
      t += 1

    val passDesc: js.Dynamic =
      Obj.literal(colorAttachments = colorAttachments)
    if panel.depthTest then
      passDesc.depthStencilAttachment = Obj.literal(
        view = panel.depthView,
        depthLoadOp = "clear",
        depthStoreOp = "store",
        depthClearValue = 1.0,
      )

    // Step 1: Render shapes (with depth/msaa from panel config)
    val shapePass = encoder.beginRenderPass(passDesc)

    var i = 0
    while i < panel.shapes.length do
      renderShapeOnPass(
        shapePass,
        panel.shapes(i),
        panel.depthTest,
        msaa,
        panelFormats,
        panel,
      )
      i += 1

    shapePass.end()
    queue.submit(Arr(encoder.finish()))

    // Step 1b: resolve multisample depth → single-sample so it can be sampled.
    if panel.needsDepthResolve then resolvePanelDepth(panel)

    // Step 2: Render layers in order — no depth, no msaa.
    // Layers are fullscreen quads that always render on top.
    // Consecutive non-ping-pong layers share a pass; ping-pong forces a new one.

    // Slot 0 is always the live result. Pong layers read it, write the scratch
    // (slot 1), then `swapPair` so slot 0 holds their output again; non-pong
    // layers load-compose into slot 0 in a shared pass. So every read of
    // `panel.textureView` below is fresh — it reflects all prior layers.

    var curEncoder: Opt[GPUCommandEncoder] = null
    var curPass: Opt[GPURenderPassEncoder] = null

    var j = 0
    while j < panel.layers.length do
      val layer = panel.layers(j)
      val needsPingPong = layer.autoPongsSlot0
      val hasMipTarget = layer.mipTarget >= 0

      if hasMipTarget then
        // Mip-targeted layer: render to a specific mip level, no ping-pong.
        // Flush any open shared pass first so its slot-0 writes are visible.
        if curPass.notNull then
          curPass.end()
          queue.submit(Arr(curEncoder.finish()))
          curPass = null

        val mipDstView = panel.views(0).perMip(layer.mipTarget)
        val mipSrcView =
          if layer.mipSource >= 0 then panel.views(0).perMip(layer.mipSource)
          else panel.textureView

        val enc = device.createCommandEncoder()
        val mipPass = enc.beginRenderPass(
          Obj.literal(
            colorAttachments = Arr(
              Obj.literal(
                view = mipDstView,
                loadOp = "load",
                storeOp = "store",
              ),
            ),
          ),
        )
        renderLayerOnPass(
          mipPass,
          layer,
          formats = panelFormats,
          srcView = mipSrcView,
          panel = panel,
        )
        mipPass.end()
        queue.submit(Arr(enc.finish()))
      else if needsPingPong then
        // Flush any open shared pass so a preceding non-pong blend layer's write
        // to slot 0 is submitted before we sample it here.
        if curPass.notNull then
          curPass.end()
          queue.submit(Arr(curEncoder.finish()))
          curPass = null

        // Ping-pong pass: read slot 0 (auto-injected), write the scratch (slot
        // 1), then swap so slot 0 becomes this layer's output.
        val enc = device.createCommandEncoder()
        val ppPass = enc.beginRenderPass(
          Obj.literal(
            colorAttachments = Arr(
              Obj.literal(
                view = panel.pongTargetView,
                loadOp = "load",
                storeOp = "store",
              ),
            ),
          ),
        )
        renderLayerOnPass(
          ppPass,
          layer,
          formats = panelFormats,
          srcView = panel.textureView,
          panel = panel,
        )
        ppPass.end()
        queue.submit(Arr(enc.finish()))

        panel.swapPair()
      else
        // Non-pong layer: lazily open a shared pass on slot 0 and load-compose
        // (blend layers stack on top of the live result, which persists there).
        if curPass.isNull then
          curEncoder = device.createCommandEncoder()
          curPass = curEncoder.beginRenderPass(
            Obj.literal(
              colorAttachments = Arr(
                Obj.literal(
                  view = panel.textureView,
                  loadOp = "load",
                  storeOp = "store",
                ),
              ),
            ),
          )
        renderLayerOnPass(curPass, layer, formats = panelFormats, panel = panel)

      j += 1

    if curPass.notNull then
      curPass.end()
      queue.submit(Arr(curEncoder.finish()))

    // Generate mipmaps if configured — but skip when any layer renders into a
    // mip target: those layers build the mip chain by hand, so auto-generation
    // would clobber it.
    var hasMipTargetLayers = false
    var mi = 0
    while mi < panel.layers.length do
      if panel.layers(mi).mipTarget >= 0 then hasMipTargetLayers = true
      mi += 1
    if panel.mipLevelCount > 1 && !hasMipTargetLayers then
      generateMipmaps(panel)

  /** Render one or more [[Panel]]s off-screen, in argument order. Each panel
    * draws its shapes then runs its layers into its own texture(s); nothing is
    * presented to the canvas — call [[show]] for that. Pass panels in
    * dependency order when later panels sample earlier ones (e.g.
    * `paint(scene, bloom, canvas)`). This is the varargs form; concrete-arity
    * overloads (1–12) exist for the hot path to avoid varargs allocation.
    */
  def paint(panels: Panel*): Unit =
    var i = 0
    while i < panels.length do
      paintPanel(panels(i))
      i += 1

  // Concrete-arity overloads for the hot path — avoid the Scala varargs
  // pipeline (`ScalaRunTime.wrapRefArray` → `Seq` → `toJSVarArgsImpl`) so a
  // typical per-frame `paint(a, b, …)` call stays allocation-free.
  inline def paint(a: Panel): Unit =
    paintPanel(a)
  inline def paint(a: Panel, b: Panel): Unit =
    paintPanel(a)
    paintPanel(b)
  inline def paint(a: Panel, b: Panel, c: Panel): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
  inline def paint(a: Panel, b: Panel, c: Panel, d: Panel): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
  inline def paint(a: Panel, b: Panel, c: Panel, d: Panel, e: Panel): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
  inline def paint(
      a: Panel,
      b: Panel,
      c: Panel,
      d: Panel,
      e: Panel,
      f: Panel,
  ): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
    paintPanel(f)
  inline def paint(
      a: Panel,
      b: Panel,
      c: Panel,
      d: Panel,
      e: Panel,
      f: Panel,
      g: Panel,
  ): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
    paintPanel(f)
    paintPanel(g)
  inline def paint(
      a: Panel,
      b: Panel,
      c: Panel,
      d: Panel,
      e: Panel,
      f: Panel,
      g: Panel,
      h: Panel,
  ): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
    paintPanel(f)
    paintPanel(g)
    paintPanel(h)
  inline def paint(
      a: Panel,
      b: Panel,
      c: Panel,
      d: Panel,
      e: Panel,
      f: Panel,
      g: Panel,
      h: Panel,
      i: Panel,
  ): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
    paintPanel(f)
    paintPanel(g)
    paintPanel(h)
    paintPanel(i)
  inline def paint(
      a: Panel,
      b: Panel,
      c: Panel,
      d: Panel,
      e: Panel,
      f: Panel,
      g: Panel,
      h: Panel,
      i: Panel,
      j: Panel,
  ): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
    paintPanel(f)
    paintPanel(g)
    paintPanel(h)
    paintPanel(i)
    paintPanel(j)
  inline def paint(
      a: Panel,
      b: Panel,
      c: Panel,
      d: Panel,
      e: Panel,
      f: Panel,
      g: Panel,
      h: Panel,
      i: Panel,
      j: Panel,
      k: Panel,
  ): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
    paintPanel(f)
    paintPanel(g)
    paintPanel(h)
    paintPanel(i)
    paintPanel(j)
    paintPanel(k)
  inline def paint(
      a: Panel,
      b: Panel,
      c: Panel,
      d: Panel,
      e: Panel,
      f: Panel,
      g: Panel,
      h: Panel,
      i: Panel,
      j: Panel,
      k: Panel,
      l: Panel,
  ): Unit =
    paintPanel(a)
    paintPanel(b)
    paintPanel(c)
    paintPanel(d)
    paintPanel(e)
    paintPanel(f)
    paintPanel(g)
    paintPanel(h)
    paintPanel(i)
    paintPanel(j)
    paintPanel(k)
    paintPanel(l)

  /** Present an already-[[paint]]ed panel's output to the surface. Typically
    * the last call each frame: `paint(...)` then `show(finalPanel)`.
    *
    * On a canvas painter this blits to the canvas. On a container painter it
    * reads back right away: one frame appended to a [[PixelFrames]], or a
    * [[PixelImage]]'s `data` overwritten — complete once [[awaitReadback]]
    * resolves. The blit samples the panel, so a panel of another size is scaled
    * and one of another format converted; at equal size it is exact.
    */
  def show(panel: Panel): Unit =
    val encoder = device.createCommandEncoder()
    blitInto(encoder, panel, surface.currentTexture(), preferredFormat)
    surface.present(encoder)

  /** Convenience: [[paint]] then [[show]] a single panel. Use the separate
    * calls when you paint several panels before presenting one.
    */
  def paintAndShow(p: Panel): Unit =
    paint(p)
    show(p)

  // =========================================================================
  // Blit — the one present algorithm (show) and the mip-chain downsample
  // =========================================================================

  /** Blit slot 0 of `panel` into `dst`: a full-screen triangle sampling the
    * panel with a nearest sampler. Shared by every `show` target. The fragment
    * stage returns `vec4<f32>` and the GPU stores it in `dstFormat`, so scaling
    * and format conversion need no extra code.
    */
  private[painter] def blitInto(
      encoder: GPUCommandEncoder,
      panel: Panel,
      dst: GPUTexture,
      dstFormat: TextureFormat,
  ): Unit =
    val pass = encoder.beginRenderPass(
      Obj.literal(
        colorAttachments = Arr(
          Obj.literal(
            view = dst.createView(),
            loadOp = "clear",
            storeOp = "store",
            clearValue = Obj.literal(r = 0, g = 0, b = 0, a = 0),
          ),
        ),
      ),
    )
    val bundle = panel.views(0)
    var group = bundle.blitGroup
    if group.isNull then
      group = device.createBindGroup(
        Obj.literal(
          layout = blitBindGroupLayout,
          entries = Arr(
            Obj.literal(binding = 0, resource = bundle.attach),
            Obj.literal(binding = 1, resource = blitSampler),
          ),
        ),
      )
      bundle.blitGroup = group
    pass.setPipeline(blitPipelineFor(dstFormat))
    pass.setBindGroup(0, group.get)
    pass.draw(3)
    pass.end()

  private lazy val blitSampler: GPUSampler =
    device.createSampler(
      Obj.literal(magFilter = "nearest", minFilter = "nearest"),
    )

  private lazy val blitBindGroupLayout: GPUBindGroupLayout =
    device.createBindGroupLayout(
      Obj.literal(
        entries = Arr(
          Obj.literal(
            binding = 0,
            visibility = GPUShaderStage.FRAGMENT,
            texture = Obj.literal(),
          ),
          Obj.literal(
            binding = 1,
            visibility = GPUShaderStage.FRAGMENT,
            sampler = Obj.literal(),
          ),
        ),
      ),
    )

  private lazy val blitModule: GPUShaderModule =
    device.createShaderModule(Obj.literal(code = BLIT_WGSL))

  private lazy val blitPipelineLayout: GPUPipelineLayout =
    device.createPipelineLayout(
      Obj.literal(bindGroupLayouts = Arr(blitBindGroupLayout)),
    )

  private val blitPipelines: Dict[GPURenderPipeline] =
    Dict[GPURenderPipeline]()

  /** The blit pipeline for one destination format, created on first use. Shared
    * by `show` (nearest sampler) and mip generation (linear sampler): same
    * shader and layout, only the bind group's sampler differs.
    */
  private def blitPipelineFor(format: TextureFormat): GPURenderPipeline =
    val key = format.asString
    // One lookup, not a `has` + `at` pair — this runs per show.
    val cached = blitPipelines.at(key)
    if !js.isUndefined(cached.asInstanceOf[js.Any]) then cached
    else
      val p = device.createRenderPipeline(
        Obj.literal(
          layout = blitPipelineLayout,
          vertex = Obj.literal(module = blitModule, entryPoint = "vs_main"),
          fragment = Obj.literal(
            module = blitModule,
            entryPoint = "fs_main",
            targets = Arr(Obj.literal(format = format.toJs)),
          ),
          primitive = Obj.literal(topology = "triangle-list"),
        ),
      )
      blitPipelines.set(key, p)
      p

  // =========================================================================
  // MSAA depth resolve — single-sample depth from a multisample attachment
  // =========================================================================

  private lazy val depthResolveBindGroupLayout: GPUBindGroupLayout =
    device.createBindGroupLayout(
      Obj.literal(
        entries = Arr(
          Obj.literal(
            binding = 0,
            visibility = GPUShaderStage.FRAGMENT,
            texture = Obj.literal(sampleType = "depth", multisampled = true),
          ),
        ),
      ),
    )

  private lazy val depthResolvePipeline: GPURenderPipeline =
    val module =
      device.createShaderModule(Obj.literal(code = DEPTH_RESOLVE_WGSL))
    val pl = device.createPipelineLayout(
      Obj.literal(bindGroupLayouts = Arr(depthResolveBindGroupLayout)),
    )
    device.createRenderPipeline(
      Obj.literal(
        layout = pl,
        vertex = Obj.literal(module = module, entryPoint = "vs_main"),
        // Depth-only: a fragment stage (writes frag_depth) with no color targets.
        fragment = Obj.literal(
          module = module,
          entryPoint = "fs_main",
          targets = Arr[js.Dynamic](),
        ),
        primitive = Obj.literal(topology = "triangle-list"),
        depthStencil = Obj.literal(
          format = "depth24plus",
          depthWriteEnabled = true,
          depthCompare = "always",
        ),
      ),
    )

  /** Resolve `panel`'s multisample depth into its single-sample sampleable
    * depth texture. Run after the shape pass, before anything samples the
    * depth.
    */
  private def resolvePanelDepth(panel: Panel): Unit =
    val encoder = device.createCommandEncoder()
    val pass = encoder.beginRenderPass(
      Obj.literal(
        colorAttachments = Arr[js.Dynamic](),
        depthStencilAttachment = Obj.literal(
          view = panel.resolvedDepthTarget,
          depthLoadOp = "clear",
          depthStoreOp = "store",
          depthClearValue = 1.0,
        ),
      ),
    )
    val bindGroup = device.createBindGroup(
      Obj.literal(
        layout = depthResolveBindGroupLayout,
        entries = Arr(
          Obj.literal(binding = 0, resource = panel.depthView),
        ),
      ),
    )
    pass.setPipeline(depthResolvePipeline)
    pass.setBindGroup(0, bindGroup)
    pass.draw(3)
    pass.end()
    queue.submit(Arr(encoder.finish()))

  // =========================================================================
  // Mipmap generation
  // =========================================================================

  private lazy val mipBlitSampler: GPUSampler =
    device.createSampler(
      Obj.literal(magFilter = "linear", minFilter = "linear"),
    )

  private def generateMipmaps(panel: Panel): Unit =
    val mipCount = panel.mipLevelCount
    if mipCount <= 1 then return
    val fmt =
      if panel.formats.length > 0 then panel.formats(0) else preferredFormat
    val pipeline = blitPipelineFor(fmt)

    var i = 1
    while i < mipCount do
      val srcView = panel.views(0).perMip(i - 1)
      val dstView = panel.views(0).perMip(i)

      val encoder = device.createCommandEncoder()
      val pass = encoder.beginRenderPass(
        Obj.literal(
          colorAttachments = Arr(
            Obj.literal(
              view = dstView,
              loadOp = "clear",
              storeOp = "store",
              clearValue = Obj.literal(r = 0, g = 0, b = 0, a = 0),
            ),
          ),
        ),
      )

      val bindGroup = device.createBindGroup(
        Obj.literal(
          layout = blitBindGroupLayout,
          entries = Arr(
            Obj.literal(binding = 0, resource = srcView),
            Obj.literal(binding = 1, resource = mipBlitSampler),
          ),
        ),
      )

      pass.setPipeline(pipeline)
      pass.setBindGroup(0, bindGroup)
      pass.draw(3)
      pass.end()
      queue.submit(Arr(encoder.finish()))
      i += 1

  // =========================================================================
  // Working buffers for binding merge (reusable, single-threaded JS)
  // =========================================================================

  private val _workBindings: BindingSlots = Arr()
  private val _workPanelBindings: Arr[Opt[PanelBinding]] = Arr()

  private def copyToWork(
      bindings: BindingSlots,
      panelBindings: Arr[Opt[PanelBinding]],
  ): Unit =
    _workBindings.length = bindings.length
    var i = 0
    while i < bindings.length do
      _workBindings(i) = bindings(i)
      i += 1
    _workPanelBindings.length = panelBindings.length
    var j = 0
    while j < panelBindings.length do
      _workPanelBindings(j) = panelBindings(j)
      j += 1

  private def applyPanelRuntimeBindings(
      panel: Panel,
      shade: Shade[?, ?],
      workBindings: BindingSlots,
      workPanelBindings: Arr[Opt[PanelBinding]],
  ): Unit =
    val dict = panel.runtimeBindings
    val keys =
      js.Object.keys(dict.asInstanceOf[js.Object]).asInstanceOf[Arr[String]]
    var i = 0
    while i < keys.length do
      val name = keys(i)
      val value = dict.at(name)
      if shade.uniformIndices.has(name) then
        val idx = shade.uniformIndices.at(name)
        if idx >= workBindings.length || workBindings(idx) == null then
          while workBindings.length <= idx do workBindings.push(null)
          workBindings(idx) =
            value.asInstanceOf[BufferBinding[?, ?] | GPUSampler]
      else if shade.panelIndices.has(name) then
        val idx = shade.panelIndices.at(name)
        if idx >= workPanelBindings.length || workPanelBindings(idx).isNull then
          while workPanelBindings.length <= idx do workPanelBindings.push(null)
          val pb =
            if value.isInstanceOf[PanelBinding] then
              value.asInstanceOf[PanelBinding]
            else PanelBinding(value.asInstanceOf[Panel])
          workPanelBindings(idx) = pb
      i += 1

  private def applyInstanceBindings(
      inst: Instance[?, ?],
      workBindings: BindingSlots,
      workPanelBindings: Arr[Opt[PanelBinding]],
  ): Unit =
    var i = 0
    while i < inst.bindings.length do
      if inst.bindings(i) != null then
        while workBindings.length <= i do workBindings.push(null)
        workBindings(i) = inst.bindings(i)
      i += 1
    var j = 0
    while j < inst.panelBindings.length do
      if inst.panelBindings(j).notNull then
        while workPanelBindings.length <= j do workPanelBindings.push(null)
        workPanelBindings(j) = inst.panelBindings(j)
      j += 1

  private def hasPanelRuntimeBindings(panel: Opt[Panel]): Boolean =
    panel.notNull && js.Object
      .keys(panel.runtimeBindings.asInstanceOf[js.Object])
      .asInstanceOf[Arr[String]]
      .length > 0

  // =========================================================================
  // Bind group helpers
  // =========================================================================

  // Build the group-0 (uniform) bind group, or null when the layer has none.
  private def buildValueBindGroup(
      shade: Shade[?, ?],
      bindings: BindingSlots,
  ): Opt[GPUBindGroup] =
    if bindings.length > 0 && shade.valueBindGroupLayout.notNull then
      val entries = Arr[js.Dynamic]()
      var i = 0
      while i < bindings.length do
        val b = bindings(i)
        if b != null then entries.push(bindingEntry(i, b))
        i += 1
      device.createBindGroup(
        Obj.literal(layout = shade.valueBindGroupLayout, entries = entries),
      )
    else null

  private def setValueBindGroup(
      pass: GPURenderPassEncoder,
      shade: Shade[?, ?],
      bindings: BindingSlots,
  ): Unit =
    val bg = buildValueBindGroup(shade, bindings)
    if bg.notNull then pass.setBindGroup(0, bg)

  // Build the group-1 (panel-texture) bind group, or null when the layer has no
  // panel layout or no entries.
  private def buildPanelBindGroup(
      shade: Shade[?, ?],
      panelBindings: Arr[Opt[PanelBinding]],
      srcView: Opt[GPUTextureView] = null,
  ): Opt[GPUBindGroup] =
    if shade.panelBindGroupLayout.notNull then
      val entries = Arr[js.Dynamic]()
      if srcView.notNull then
        entries.push(Obj.literal(binding = 0, resource = srcView))
      val startIdx = if srcView.notNull then 1 else 0
      var k = startIdx
      while k < panelBindings.length do
        val pb = panelBindings(k)
        if pb.notNull then
          val view =
            if pb.depth then pb.panel.depthSamplingView
            else if pb.mipLevel < 0 then pb.panel.views(pb.index).sampling
            else pb.panel.views(pb.index).perMip(pb.mipLevel)
          entries.push(Obj.literal(binding = k, resource = view))
        k += 1
      if entries.length > 0 then
        device.createBindGroup(
          Obj.literal(
            layout = shade.panelBindGroupLayout,
            entries = entries,
          ),
        )
      else null
    else null

  private def setPanelBindGroup(
      pass: GPURenderPassEncoder,
      shade: Shade[?, ?],
      panelBindings: Arr[Opt[PanelBinding]],
      srcView: Opt[GPUTextureView] = null,
  ): Unit =
    val pg = buildPanelBindGroup(shade, panelBindings, srcView)
    if pg.notNull then pass.setBindGroup(1, pg)

  // =========================================================================
  // Per-shape render pass helper (shared by draw() and paint())
  // =========================================================================

  private def renderShapeOnPass(
      pass: GPURenderPassEncoder,
      shape: AnyShape,
      depthTest: Boolean = false,
      multisample: Boolean = false,
      formats: Arr[TextureFormat] = null,
      panel: Opt[Panel] = null,
  ): Unit =
    val fmts = if formats != null then formats else Arr(preferredFormat)
    val pipeline = getPipeline(
      shape.shade,
      shape.blendState,
      fmts,
      depthTest,
      multisample,
      shape.form.topology,
      shape.cullMode,
      shape.form.frontFace,
      shape.form.indexFormat,
    )

    pass.setPipeline(pipeline)

    val form = shape.form
    val bufferCount = form.activeBuffers
    val instanceCount = shape.instances.length
    val hasPanelBinds = hasPanelRuntimeBindings(panel)

    // A form can hold several geometry buffers (e.g. the fragments of a split
    // polyline); they are drawn in sequence sharing pipeline and bind groups.
    inline def drawCall(): Unit =
      var b = 0
      while b < bufferCount do
        val buf = form.buffers(b)
        if buf.vertexCount > 0 then
          pass.setVertexBuffer(
            0,
            buf.vertexBuffer.get,
            0.0,
            buf.vertexBufferCurrentSize,
          )
          if buf.indexCount > 0 then
            pass.setIndexBuffer(
              buf.indexBuffer.get,
              buf.indexFormat,
              0.0,
              buf.indexBufferCurrentSize,
            )
            pass.drawIndexed(buf.indexCount)
          else pass.draw(buf.vertexCount)
        b += 1

    if instanceCount == 0 then
      if hasPanelBinds then
        copyToWork(shape.bindings, shape.panelBindings)
        applyPanelRuntimeBindings(
          panel,
          shape.shade,
          _workBindings,
          _workPanelBindings,
        )
        setValueBindGroup(pass, shape.shade, _workBindings)
        setPanelBindGroup(pass, shape.shade, _workPanelBindings)
      else
        setValueBindGroup(pass, shape.shade, shape.bindings)
        setPanelBindGroup(pass, shape.shade, shape.panelBindings)
      drawCall()
    else
      var i = 0
      while i < instanceCount do
        val inst = shape.instances.items(i)
        copyToWork(shape.bindings, shape.panelBindings)
        if hasPanelBinds then
          applyPanelRuntimeBindings(
            panel,
            shape.shade,
            _workBindings,
            _workPanelBindings,
          )
        applyInstanceBindings(inst, _workBindings, _workPanelBindings)
        setValueBindGroup(pass, shape.shade, _workBindings)
        setPanelBindGroup(pass, shape.shade, _workPanelBindings)
        drawCall()
        i += 1

  // =========================================================================
  // Per-layer render pass helper
  // =========================================================================

  private def renderLayerOnPass(
      pass: GPURenderPassEncoder,
      layer: AnyLayer,
      depthTest: Boolean = false,
      multisample: Boolean = false,
      formats: Arr[TextureFormat] = null,
      srcView: Opt[GPUTextureView] = null,
      panel: Opt[Panel] = null,
  ): Unit =
    val fmts = if formats != null then formats else Arr(preferredFormat)
    val pipeline = getPipeline(
      layer.shade,
      layer.blendState,
      fmts,
      depthTest,
      multisample,
    )

    pass.setPipeline(pipeline)

    val instanceCount = layer.instances.length
    val hasPanelBinds = hasPanelRuntimeBindings(panel)

    if instanceCount == 0 then
      if hasPanelBinds then
        // Dynamic path: panel runtime overrides merged per draw — not cached.
        copyToWork(layer.bindings, layer.panelBindings)
        applyPanelRuntimeBindings(
          panel,
          layer.shade,
          _workBindings,
          _workPanelBindings,
        )
        setValueBindGroup(pass, layer.shade, _workBindings)
        val effectiveSrcView =
          if _workPanelBindings.length > 0 && _workPanelBindings(0).notNull
          then null
          else srcView
        setPanelBindGroup(
          pass,
          layer.shade,
          _workPanelBindings,
          effectiveSrcView,
        )
      else
        // Static path: cacheable by (panelId, epoch). A hit skips the
        // work-buffer copy + GPU bind-group build; a mismatch (or first draw)
        // rebuilds and re-stores, dropping any stale entry.
        val c = layer.cache
        if c.notNull && panel.notNull
          && c.panelId == panel.panelId && c.epoch == panel.bindEpoch
        then
          if c.valueGroup.notNull then pass.setBindGroup(0, c.valueGroup)
          if c.panelGroup.notNull then pass.setBindGroup(1, c.panelGroup)
        else
          val vg = buildValueBindGroup(layer.shade, layer.bindings)
          val pg =
            buildPanelBindGroup(layer.shade, layer.panelBindings, srcView)
          if vg.notNull then pass.setBindGroup(0, vg)
          if pg.notNull then pass.setBindGroup(1, pg)
          layer.cache =
            if panel.notNull then
              LayerBindCache(panel.panelId, panel.bindEpoch, vg, pg)
            else null
      pass.draw(3)
    else
      var i = 0
      while i < instanceCount do
        val inst = layer.instances.items(i)
        copyToWork(layer.bindings, layer.panelBindings)
        if hasPanelBinds then
          applyPanelRuntimeBindings(
            panel,
            layer.shade,
            _workBindings,
            _workPanelBindings,
          )
        applyInstanceBindings(inst, _workBindings, _workPanelBindings)
        setValueBindGroup(pass, layer.shade, _workBindings)
        val effectiveSrcView =
          if _workPanelBindings.length > 0 && _workPanelBindings(0).notNull
          then null
          else srcView
        setPanelBindGroup(
          pass,
          layer.shade,
          _workPanelBindings,
          effectiveSrcView,
        )
        pass.draw(3)
        i += 1

  // =========================================================================
  // Pipeline creation + caching
  // =========================================================================

  private def blendKeyStr(bs: Opt[BlendState]): String =
    if bs.isNull then "n"
    else
      val c = bs.color
      val a = bs.alpha
      s"${c.srcFactor}.${c.dstFactor}.${c.operation}|${a.srcFactor}.${a.dstFactor}.${a.operation}"

  private def getPipeline(
      shade: Shade[?, ?],
      blendState: Opt[BlendState],
      formats: Arr[TextureFormat],
      depthTest: Boolean,
      multisample: Boolean,
      topology: PrimitiveTopology = PrimitiveTopology.TriangleList,
      cullMode: CullMode = CullMode.None,
      frontFace: FrontFace = FrontFace.CCW,
      indexFormat: Opt[String] = null,
  ): GPURenderPipeline =
    // Only strip topologies take a stripIndexFormat, and they *must* have one
    // to be drawn indexed — the primitive restart index depends on it.
    val stripIndexFormat: Opt[String] =
      if indexFormat.notNull &&
        (topology == PrimitiveTopology.TriangleStrip ||
          topology == PrimitiveTopology.LineStrip)
      then indexFormat
      else null
    // Only ever "", "2" or "4" — no separator needed, and it keeps the key
    // this builds on every draw call from growing.
    val stripKey =
      if stripIndexFormat.isNull then ""
      else if stripIndexFormat.get == "uint32" then "4"
      else "2"
    val key =
      s"${shade.id}|${blendKeyStr(blendState)}|${formats.asInstanceOf[Arr[String]].join(",")}|$depthTest|$multisample|${topology}|${cullMode}|${frontFace}$stripKey"
    // One lookup, not a `has` + `at` pair — this runs per draw call.
    val cached = pipelineCache.at(key)
    if !js.isUndefined(cached.asInstanceOf[js.Any]) then cached
    else
      val targets = Arr[js.Dynamic]()
      var ti = 0
      while ti < formats.length do
        val target =
          if blendState.isNull then Obj.literal(format = formats(ti).toJs)
          else Obj.literal(format = formats(ti).toJs, blend = blendState)
        targets.push(target)
        ti += 1

      val vertexDescriptor =
        if shade.vertexBufferLayout.notNull then
          Obj.literal(
            module = shade.shaderModule,
            entryPoint = "vs_main",
            buffers = Arr(shade.vertexBufferLayout),
          )
        else
          Obj.literal(
            module = shade.shaderModule,
            entryPoint = "vs_main",
          )

      val desc = Obj.literal(
        layout = shade.pipelineLayout,
        vertex = vertexDescriptor,
        fragment = Obj.literal(
          module = shade.shaderModule,
          entryPoint = "fs_main",
          targets = targets,
        ),
        primitive = Obj.literal(
          topology = topology.toJs,
          cullMode = cullMode.toJs,
          frontFace = frontFace.toJs,
        ),
      )
      if stripIndexFormat.notNull then
        desc.primitive.stripIndexFormat = stripIndexFormat.get
      if depthTest then
        desc.depthStencil = Obj.literal(
          format = "depth24plus",
          depthWriteEnabled = true,
          depthCompare = "less",
        )
      if multisample then desc.multisample = Obj.literal(count = 4)
      val p = device.createRenderPipeline(desc)
      pipelineCache.set(key, p)
      p

  // =========================================================================
  // Bind group creation
  // =========================================================================

  private def bindingEntry(
      i: Int,
      b: BufferBinding[?, ?] | GPUSampler,
  ): js.Dynamic =
    if b.isInstanceOf[BufferBinding[?, ?]] then
      val buffer = b.asInstanceOf[BufferBinding[?, ?]]
      Obj.literal(
        binding = i,
        resource = Obj.literal(buffer = buffer.gpuBuffer),
      )
    else Obj.literal(binding = i, resource = b.asInstanceOf[GPUSampler])

object Painter:
  /** Initialise WebGPU for `canvas` and resolve a ready [[Painter]]. Requests
    * an adapter + device, configures the context with the preferred format,
    * sizes the canvas to its client rect, and installs a `ResizeObserver` that
    * drives [[Painter.onResize]]. Rejects if WebGPU is unavailable. Prefer the
    * `init(canvas)(setup)` overload, which runs your setup inside the ready
    * painter.
    */
  def init(canvas: HTMLCanvasElement): js.Promise[Painter] =
    val maybeGpu = WebGPU.getGPU()
    if maybeGpu.isEmpty then
      js.Promise.reject(
        js.Error("WebGPU is not supported"),
      )
    else
      val gpu = maybeGpu.safe
      val surface = CanvasSurface(
        canvas,
        format = TextureFormat.of(gpu.getPreferredCanvasFormat()),
      )
      setup(surface, gpu)

  /** Create a painter on `target` with a caller-supplied `gpu` — offline
    * (node-webgpu's `create([...])`) or in the browser with adapter options.
    * The library never looks the GPU up.
    *
    *   - `target` — a [[PixelFrames]] / [[PixelImage]] container (offline:
    *     `show` reads back into it), or a [[Surface]] such as
    *     `CanvasSurface(canvas)`. Panels default to its format.
    *   - `powerPreference`, `forceFallbackAdapter`, `requireAdapter`,
    *     `logAdapter` — see [[WebGPU.requestAdapter]].
    *   - `requiredFeatures`, `requiredLimits` — see [[WebGPU.requestDevice]].
    *     An `rgba32float` container needs `Arr("float32-filterable")`.
    */
  def create(
      target: PixelTarget | Surface,
      gpu: GPU,
      powerPreference: Maybe[PowerPreference] = Maybe.Not,
      forceFallbackAdapter: Boolean = false,
      requireAdapter: Maybe[js.Function1[GPUAdapterInfo, Boolean]] = Maybe.Not,
      requiredFeatures: Maybe[Arr[String]] = Maybe.Not,
      requiredLimits: Maybe[js.Object] = Maybe.Not,
      logAdapter: Boolean = false,
  ): js.Promise[Painter] =
    WebGPU
      .requestAdapter(
        gpu,
        powerPreference,
        forceFallbackAdapter,
        requireAdapter,
        logAdapter,
      )
      .flatMap: adapter =>
        WebGPU
          .requestDevice(adapter, requiredFeatures, requiredLimits)
          .map: device =>
            createOn(target, device, adapter.info)

  /** Create a painter on `target` with a caller-supplied `device`; synchronous,
    * no lookups at all. For adapter-level control in user code
    * (`WebGPU.requestAdapter` → `WebGPU.requestDevice` → here).
    */
  def create(target: PixelTarget | Surface, device: GPUDevice): Painter =
    createOn(target, device, null)

  private def createOn(
      target: PixelTarget | Surface,
      device: GPUDevice,
      adapterInfo: Opt[GPUAdapterInfo],
  ): Painter =
    if target.isInstanceOf[Surface] then
      build(target.asInstanceOf[Surface], device, adapterInfo)
    else
      val readback = Readback(device)
      val painter = build(
        CaptureSurface(target.asInstanceOf[PixelTarget], readback),
        device,
        adapterInfo,
      )
      painter.readback = readback
      painter

  // `init` goes through here, not through the union-typed `create`, so a
  // browser bundle never links `CaptureSurface`.
  private def setup(surface: Surface, gpu: GPU): js.Promise[Painter] =
    WebGPU
      .requestAdapter(gpu)
      .flatMap: adapter =>
        WebGPU
          .requestDevice(adapter)
          .map: device =>
            build(surface, device, adapter.info)

  private def build(
      surface: Surface,
      device: GPUDevice,
      adapterInfo: Opt[GPUAdapterInfo],
  ): Painter =
    surface.configure(device)
    new Painter(device, surface, adapterInfo)

  /** Initialise WebGPU and run `setup` with the ready [[Painter]] — the
    * standard sketch entry point. All resource creation, `onResize`, and the
    * `animate` loop go inside the `setup` closure:
    * {{{
    * Painter.init(canvas): p =>
    *   // create shades, forms, shapes, panels, bindings here
    *   animate: tpf => p.paint(panel); p.show(panel)
    * }}}
    */
  def init(canvas: HTMLCanvasElement)(
      setup: Painter => Unit,
  ): js.Promise[Unit] =
    init(canvas).map(setup)
