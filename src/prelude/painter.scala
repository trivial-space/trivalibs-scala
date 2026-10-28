package trivalibs.prelude

/** Everything a painter sketch needs to put pixels on a canvas: CPU math, GPU
  * expression math, the `Painter` abstraction, the shader types and the shader
  * DSL, typed buffer bindings and the `animate` loop.
  *
  * ```scala
  * import trivalibs.prelude.core.{*, given}
  * import trivalibs.prelude.painter.{*, given}
  * ```
  *
  * replaces the seven-line import block every sketch used to open with:
  * `graphics.math.cpu`, `graphics.math.gpu`, `graphics.painter`,
  * `graphics.shader`, `graphics.shader.dsl`, `graphics.buffers` and
  * `utils.animation.animate`. The individual packages keep working — this is a
  * bundle, not a replacement.
  *
  * Two names differ from the raw imports, on purpose:
  *   - `None` (the empty shader contract, `trivalibs.graphics.shader.None`) is
  *     exported as `GPUNone`, so sketches keep Scala's `None`. Sketches used to
  *     write that rename themselves, once per file.
  *   - `Vec2`…`Mat4` come from `graphics.math.cpu`. `graphics.math.gpu`
  *     re-exports the same six names for shader contracts; exporting both here
  *     would be a duplicate definition, and the cpu ones are the originals.
  *
  * See [[trivalibs.prelude.core]] for the `$package` export mechanics and what
  * maintaining this list involves.
  */
object painter:

  // ── trivalibs.graphics.math ────────────────────────────────────────────────
  // The CPU/GPU-shared interpolation layer: the `LerpBy` type class, its `Lerp`
  // alias, and the `t.lerpIn(lo, hi)` extension that reads any `Lerp` bounds.
  // Note the absence of `LerpBy.given` — exporting those would put them in
  // identifier scope, where they would hide the vector ops' own `lerp`. They
  // are found through implicit scope instead; see interpolation.scala.
  export trivalibs.graphics.math.`interpolation$package`.{*, given}
  export trivalibs.graphics.math.LerpBy

  // ── trivalibs.graphics.lib ─────────────────────────────────────────────────
  // The shared CPU/GPU extensions (`c.hsv2rgb`, `p.polarToCart`,
  // `p.simplexFbm`, `p.extendedNoise`, `p.worleyNoise`, …) and the parameter
  // unions they take. Only the extension-carrying `$package` objects are
  // exported: the `Color` / `Simplex` / … objects exist on both sides (in
  // `graphics.lib` and `graphics.shader.lib`) and stay explicit imports.
  export trivalibs.graphics.lib.color.`color$package`.{*, given}
  export trivalibs.graphics.lib.coords.`coords$package`.{*, given}
  export trivalibs.graphics.lib.noise.`extensions$package`.{*, given}
  export trivalibs.graphics.lib.`args$package`.{
    FloatArg,
    IntArg,
    Vec2Arg,
    Vec3Arg,
    Vec4Arg,
    VecArg,
  }
  // GPU-only hash extensions (`x.hash`, `u.hashU`, `u.hash1`); their
  // receivers are shader types only.
  export trivalibs.graphics.shader.lib.random.`hash$package`.{*, given}

  // ── trivalibs.graphics.math.cpu ────────────────────────────────────────────
  export trivalibs.graphics.math.cpu.`package$package`.{*, given}
  export trivalibs.graphics.math.cpu.`swizzles$package`.{*, given}
  export trivalibs.graphics.math.cpu.`tuple_interop$package`.{*, given}
  export trivalibs.graphics.math.cpu.`quat$package`.{*, given}
  export trivalibs.graphics.math.cpu.`vec2$package`.{
    Vec2Buffer as _,
    Vec2dBuffer as _,
    Vec2Tuple as _,
    *,
    given,
  }
  export trivalibs.graphics.math.cpu.`vec3$package`.{
    Vec3Buffer as _,
    Vec3dBuffer as _,
    Vec3Tuple as _,
    *,
    given,
  }
  export trivalibs.graphics.math.cpu.`vec4$package`.{
    Vec4Buffer as _,
    Vec4dBuffer as _,
    Vec4Tuple as _,
    *,
    given,
  }
  export trivalibs.graphics.math.cpu.`mat2$package`.{
    Mat2Buffer as _,
    Mat2Tuple as _,
    *,
    given,
  }
  export trivalibs.graphics.math.cpu.`mat3$package`.{
    Mat3Buffer as _,
    Mat3PaddedBuffer as _,
    Mat3Tuple as _,
    *,
    given,
  }
  export trivalibs.graphics.math.cpu.`mat4$package`.{
    Mat4Buffer as _,
    Mat4Tuple as _,
    *,
    given,
  }
  export trivalibs.graphics.math.cpu.{
    Vec2,
    Vec2Buffer,
    Vec2dBuffer,
    Vec2Tuple,
    Vec3,
    Vec3Buffer,
    Vec3dBuffer,
    Vec3Tuple,
    Vec4,
    Vec4Buffer,
    Vec4dBuffer,
    Vec4Tuple,
    Mat2,
    Mat2Buffer,
    Mat2Tuple,
    Mat3,
    Mat3Buffer,
    Mat3PaddedBuffer,
    Mat3Tuple,
    Mat4,
    Mat4Buffer,
    Mat4Tuple,
    Quat,
    QuatImmutableOps,
    QuatMutableOps,
  }

  // ── trivalibs.graphics.math.gpu ────────────────────────────────────────────
  export trivalibs.graphics.math.gpu.`cpu_interop$package`.{*, given}
  export trivalibs.graphics.math.gpu.`float_expr$package`.{*, given}
  export trivalibs.graphics.math.gpu.`int_expr$package`.{*, given}
  export trivalibs.graphics.math.gpu.`expr$package`.{
    Stmt as _,
    Block as _,
    *,
    given,
  }
  export trivalibs.graphics.math.gpu.{
    Expr,
    LetExpr,
    VarExpr,
    ConstExpr,
    Stmt,
    Block,
    LeftScalar,
    UInt,
    IVec2,
    IVec3,
    IVec4,
    UVec2,
    UVec3,
    UVec4,
    vec2,
    vec3,
    vec4,
    ivec2,
    ivec3,
    ivec4,
    uvec2,
    uvec3,
    uvec4,
  }

  // ── trivalibs.graphics.painter ─────────────────────────────────────────────
  export trivalibs.graphics.painter.`layer$package`.{*, given}
  export trivalibs.graphics.painter.`panel$package`.{*, given}
  export trivalibs.graphics.painter.`shape$package`.{*, given}
  export trivalibs.graphics.painter.`enums$package`.{
    TextureFormat as _,
    FilterMode as _,
    AddressMode as _,
    PrimitiveTopology as _,
    CullMode as _,
    FrontFace as _,
    BlendFactor as _,
    BlendOp as _,
    *,
    given,
  }
  export trivalibs.graphics.painter.{
    Painter,
    Panel,
    Layer,
    Shape,
    Shade,
    Form,
    Instance,
    InstanceList,
    Bindable,
    BindPair,
    TextureFormat,
    FilterMode,
    AddressMode,
    PrimitiveTopology,
    CullMode,
    FrontFace,
    BlendFactor,
    BlendOp,
  }

  // `BlendFn`, `BlendState` and `PanelBinding` extend `js.Object`, and an
  // `export` forwarder for a JS type is rejected by the Scala.js plugin
  // ("@JSType is for compiler internal use only"). A hand-written type alias
  // plus an inline accessor for the companion gives the same two names at the
  // same zero cost. Same treatment for any future `js.Object` class here.
  type BlendFn = trivalibs.graphics.painter.BlendFn
  inline def BlendFn: trivalibs.graphics.painter.BlendFn.type =
    trivalibs.graphics.painter.BlendFn
  type BlendState = trivalibs.graphics.painter.BlendState
  inline def BlendState: trivalibs.graphics.painter.BlendState.type =
    trivalibs.graphics.painter.BlendState
  type PanelBinding = trivalibs.graphics.painter.PanelBinding
  inline def PanelBinding: trivalibs.graphics.painter.PanelBinding.type =
    trivalibs.graphics.painter.PanelBinding

  // Painter targets: surfaces and the offscreen pixel targets. `PixelFrames`
  // and `PixelImage` are `js.Object` traits, so they get the alias treatment.
  export trivalibs.graphics.painter.`pixels$package`.{
    PixelTarget as _,
    *,
    given,
  }
  export trivalibs.graphics.painter.{Surface, CanvasSurface, PixelTarget}
  type PixelFrames = trivalibs.graphics.painter.PixelFrames
  inline def PixelFrames: trivalibs.graphics.painter.PixelFrames.type =
    trivalibs.graphics.painter.PixelFrames
  type PixelImage = trivalibs.graphics.painter.PixelImage
  inline def PixelImage: trivalibs.graphics.painter.PixelImage.type =
    trivalibs.graphics.painter.PixelImage

  // Panel sources: the `TextureSource` / `ExternalImage` types and browser
  // image loading. `VideoFrame` is a native facade, so it gets an alias.
  export trivalibs.graphics.painter.`sources$package`.*
  export trivalibs.graphics.painter.WebImages
  type VideoFrame = trivalibs.graphics.painter.VideoFrame

  // The raw WebGPU facades (`GPUDevice`, `GPUTexture`, …) are deliberately not
  // bundled: no sketch names them, they are the plumbing the Painter hides —
  // and `@js.native` types cannot be re-exported at all. Import them directly
  // if you ever need to drop to the raw API. The exception is what the
  // extended setup takes: `GPU` (the parameter of every offscreen entry, an
  // alias since it is native), `WebGPU` (`adapterNamed`, `requestAdapter`) and
  // `PowerPreference`.
  type GPU = trivalibs.graphics.painter.GPU
  export trivalibs.graphics.painter.WebGPU
  export trivalibs.graphics.painter.`webgpu$package`.PowerPreference

  // ── trivalibs.graphics.shader ──────────────────────────────────────────────
  // `None` is the empty shader contract; renamed so Scala's `None` survives.
  export trivalibs.graphics.shader.`types$package`.{None as GPUNone, *, given}
  export trivalibs.graphics.shader.`builtins$package`.{
    BuiltinVertexIndex as _,
    BuiltinInstanceIndex as _,
    BuiltinPosition as _,
    BuiltinFragCoord as _,
    BuiltinFrontFacing as _,
    BuiltinSampleIndex as _,
    *,
    given,
  }
  export trivalibs.graphics.shader.{
    Shader,
    ShaderDef,
    WGSLType,
    derive,
    layouts,
    VertexUniform,
    FragmentUniform,
    SharedUniform,
    VertexPanel,
    FragmentPanel,
    SharedPanel,
    VertexDepthPanel,
    FragmentDepthPanel,
    SharedDepthPanel,
    BuiltinType,
    BuiltinVertexIndex,
    BuiltinInstanceIndex,
    BuiltinPosition,
    BuiltinFragCoord,
    BuiltinFrontFacing,
    BuiltinSampleIndex,
  }

  // ── trivalibs.graphics.shader.dsl ──────────────────────────────────────────
  export trivalibs.graphics.shader.dsl.`context$package`.{*, given}
  export trivalibs.graphics.shader.dsl.`types$package`.{*, given}
  export trivalibs.graphics.shader.dsl.`fn$package`.{WgslFn as _, *, given}
  export trivalibs.graphics.shader.dsl.{
    Program,
    LayerProgram,
    VertexCtx,
    FragmentCtx,
    VertexOut,
    AssignTarget,
    TypedPanelAccessor,
    TypedExprAccessor,
    TypedAssignAccessor,
    FnRegistry,
    ReturnEmitter,
  }

  // `WgslFn` is aliased rather than exported. An export forwarder for a
  // parameterized *opaque* type does not carry its companion's `apply`
  // extensions: `myFn(args)` then fails with "Found: WgslFn[…], Required:
  // WgslFnData" at every call site, because those extensions see the opaque
  // type transparently and the receiver is never adapted. A plain alias
  // dealiases to the original and resolves them, so a sketch on this bundle
  // alone can define and call its own `WgslFn`.
  type WgslFn[P, R] = trivalibs.graphics.shader.dsl.WgslFn[P, R]
  inline def WgslFn: trivalibs.graphics.shader.dsl.WgslFn.type =
    trivalibs.graphics.shader.dsl.WgslFn

  // `js.Object` class — see the BlendFn note above.
  type WgslFnData = trivalibs.graphics.shader.dsl.WgslFnData
  inline def WgslFnData: trivalibs.graphics.shader.dsl.WgslFnData.type =
    trivalibs.graphics.shader.dsl.WgslFnData

  // ── trivalibs.graphics.buffers ─────────────────────────────────────────────
  export trivalibs.graphics.buffers.`attributes$package`.{*, given}
  export trivalibs.graphics.buffers.`binding$package`.{*, given}
  export trivalibs.graphics.buffers.`uniform_array$package`.{*, given}
  export trivalibs.graphics.buffers.{
    BufferBinding,
    UniformValue,
    UniformLayout,
    UniformArray,
    UniformArrayElem,
    AttribLayout,
    AttribLayoutHelper,
  }

  // ── the render loop ────────────────────────────────────────────────────────
  export trivalibs.utils.animation.`animate$package`.{*, given}
  export trivalibs.utils.animation.Animator
