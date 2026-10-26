# Sketch Authoring Guide

How to build a sketch with the trivalibs painter — the end-to-end flow, the core
types, and the per-frame render model. For the exhaustive per-symbol reference,
hover in the IDE / query Metals MCP (`get-docs`, `inspect`) or browse the
generated Scaladoc (`bun run docs` → `docs/api/html/`). For the shader DSL op
catalog see [shader-dsl-guide.md](shader-dsl-guide.md); for traps see
[gotchas.md](gotchas.md).

> Naming note (vs the Rust painter): Rust `Layer` → Scala **`Panel`**; Rust
> `Effect` → Scala **`Layer`**; Rust `shade_effect` → **`layerShade`**.

## 1. Entry point

Everything a sketch does happens inside `Painter.init(canvas) { p => … }` —
resource creation, resize handling and the animation loop all live in that
closure. The `Painter` is the entry point to the library; how the enclosing
function is named, packaged and reached is up to the project using it.

```scala
import org.scalajs.dom.HTMLCanvasElement
import trivalibs.prelude.core.{*, given}
import trivalibs.prelude.painter.{*, given}

import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("sketch")
def myShape(canvas: HTMLCanvasElement): Unit =
  Painter.init(canvas): p =>
    // 1. shade  2. form  3. shape/layer  4. panel  5. animate
    ...
```

Two pieces of advice, both easy to follow and awkward to retrofit:

- **Let the host call in, rather than running on import.** A `@JSExportTopLevel`
  function the page invokes gives the host control over _when_ the sketch
  starts; a `@main` def runs as a side effect of loading the module, which is
  harder to sequence and to reuse.
- **Take the canvas as a parameter.** Looking it up with
  `document.getElementById` ties the bundle to a browser DOM. Passing it in lets
  the same code run under a host that has none — e.g. NativeScript Canvas, where
  the canvas comes from the native view tree.

The export name, the module layout and the build command are **conventions of
the consuming project**, not of this library; a project that runs many sketches
will want to fix them so its host glue is identical everywhere. This repo's own
`examples/` use `@JSExportTopLevel("main", moduleID = "<example>")` and look the
canvas up from `document`, because they are bundled many-to-one and each owns
its page — a reasonable choice for that setup, and not a general recommendation.

### The preludes

Two imports cover essentially everything:

```scala
import trivalibs.prelude.core.{*, given}     // Arr / Dict / Maybe / Opt / Obj,
                                             // Pi, Tau, the NumExt + IntExt givens
import trivalibs.prelude.painter.{*, given}  // cpu + gpu math, Painter, shader
                                             // types + DSL, buffers, animate
```

`prelude.painter` replaces the seven-line block sketches used to open with
(`graphics.math.cpu`, `graphics.math.gpu`, `graphics.painter`,
`graphics.shader`, `graphics.shader.dsl`, `graphics.buffers`,
`utils.animation.animate`). Both are bundles, not replacements — the individual
packages keep working, and a file that wants only `Arr` still writes
`import trivalibs.utils.js.Arr`.

Two names differ from the raw imports, deliberately:

- `None` (the empty shader contract) is exported as **`GPUNone`**, so sketches
  keep Scala's `None`.
- `Vec2`…`Mat4` come from `graphics.math.cpu`; the `gpu` package re-exports the
  same six names for shader contracts, and exporting both would clash.

Angle constants are **`Pi`** and **`Tau`** (`Tau` = 2π, a full turn) —
capitalised, from `utils.numbers` via `prelude.core`.

## 2. The core pipeline

```
Shade   = compiled shader (vertex+fragment, or fragment-only for a Layer)
Form    = geometry (vertex buffer + topology)
Shape   = Form + Shade + bound uniforms   → drawn into a Panel
Layer   = full-screen fragment pass (post-processing) → attached to a Panel
Panel   = render target: clears, draws its shapes, then runs its layers
paint   = render panels off-screen;  show = present one to the canvas
```

### 2a. 3D geometry — Mesh + `toBufferedGeometry` (the default path)

For anything beyond a single primitive, build a **`Mesh`** of `Quad`/`Triangle`
faces and convert it with **`toBufferedGeometry`** — this is the idiomatic way
to make 3D geometry. It gives you indexed buffers and optional generated
normals, and works with the `geometry` builders (`Box`, `sphereMesh`, `Grid`).

```scala
import trivalibs.graphics.geometry.{*, given}

type Attribs  = (position: Vec3, normal: Vec3)   // normal is generated (see below)
type Varyings = (normal: Vec3)
type Uniforms = (mvp: Mat4)

// Box → Mesh: vertices are Vec3 positions.
val box  = Box(Vec3.zero, 1.0, 1.0, 1.0)
val mesh = new Mesh[Vec3]()
box.faces.foreach((face, normal) => mesh.addFace(face, normal))  // normal optional
// (mesh.addFace(face) is fine too — normals are computed below when requested.)

// FaceVerticesWithFaceNormal generates a per-face normal → matches Attribs:
val form = p.form(geometry =
  toBufferedGeometry(mesh, MeshBufferType.FaceVerticesWithFaceNormal))

val shade = p.shade[Attribs, Varyings, Uniforms]: program =>
  program.vert: ctx =>
    Block(
      ctx.out.position := ctx.bindings.mvp * vec4(ctx.in.position, 1.0),
      ctx.out.normal   := ctx.in.normal,
    )
  program.frag: ctx =>
    ctx.out.color := vec4(ctx.in.normal.normalize * 0.5 + 0.5, 1.0)

val mvp   = p.binding[Mat4]
val shape = p.shape(form, shade, cullMode = CullMode.Back).bind("mvp" := mvp)
val panel = p.panel(shape = shape, clearColor = (0.05, 0.06, 0.1, 1.0), depthTest = true)
```

- **`MeshBufferType`** picks the vertex strategy: `FaceVertices` (default, no
  normals), `CompactVertices` (shared/de-duplicated vertices), or the
  `…WithFaceNormal` / `…WithVertexNormal` variants that **append a generated
  normal** to each vertex — so a trailing `normal: Vec3` appears in your
  `Attribs` (if the mesh vertex is itself a named tuple like
  `(position: Vec3, uv: Vec2)`, use `WithNormal[V]` for the attribs schema).
- **Builders**: `Box` — `.faces` (each as `(quad, normal)`), or per-face
  `.frontFace`/`.topFace`/… with a `(corner, uvw) => vertex` fn for custom
  per-vertex attributes (UVs etc.); `sphereMesh(vSeg, hSeg)(f)`; `Grid` →
  `Mesh(grid.ccwQuads)`.
- Transform meshes with `mesh.map` / `flatMap`. All three builders are in the
  `geometry3d_scene` example (box + sphere + terrain grid).

### 2a′. Simple primitives — `allocateAttribs` (raw vertices)

For a one-off primitive (a single triangle / quad, a handful of vertices) where
a mesh is overkill, write raw vertices directly:

```scala
type Attribs = (position: Vec3, color: Vec3)
val verts = allocateAttribs[Attribs](3)
verts(0).set0(0.0, 0.5, 0.0)   // field 0 = position
verts(0).set1(1.0, 0.2, 0.2)   // field 1 = color
// … fill the other vertices …
val form = p.form(vertices = verts)
```

Reach for the `Mesh` path (2a) as soon as the geometry is non-trivial.

### 2a″. Several buffers in one form, and per-frame updates

A form can hold **several geometry buffers**, drawn in sequence by one shape
with the same pipeline, bind groups, topology and front face. Use it when one
logical thing is made of many chunks — e.g. a polyline that `line2d` split into
fragments at its sharp corners:

```scala
val form = p.form(
  geometries = fragments,                      // Arr[BufferedGeometry[F]]
  topology   = PrimitiveTopology.TriangleStrip,
)
// raw-vertex sibling: p.form(verticesAll = arrOfStructArrays)
```

Reassigning geometry (`form.set(...)`, same params) is cheap enough to do per
frame: buffers are reused while the new data still fits and only reallocated
when it grows, and each draw binds only the live slice — so a **smaller** upload
never leaves stale vertices from a larger one behind. Buffers left over from a
longer previous upload simply go inactive. See the `random_lines` example.

### 2b. Full-screen layer (post-processing / procedural)

A `Layer` needs no `Form` — it's a fragment shader over a full-screen triangle,
with `ctx.in.uv` in `[0,1]`:

```scala
type U = (time: Float, res: Vec2)
val shade = p.layerShade[U]: program =>
  program.frag: ctx =>
    ctx.out.color := vec4(ctx.in.uv, 0.0, 1.0)

val time = p.binding(0.0f)
val res  = p.binding[Vec2]
val panel = p.panel(layer = p.layer(shade).bind("time" := time, "res" := res))
```

**`ctx.in.uv`'s origin is the TOP-LEFT.** The built-in vertex stage emits
`out.uv = vec2f(x * 0.5 + 0.5, 0.5 - y * 0.5)`, so `uv.y == 0` is the top row
and `uv.y == 1` the bottom — matching texture-coordinate convention, not clip
space. Worth knowing before writing any vertical ramp: a gradient that should
start dark at the top is `f(uv.y)` with `f(0) == 0`, not `1 - uv.y`.

A layer needs no uniforms at all if it has none — the zero-schema overload
resolves without a type argument:

```scala
val shade = p.layerShade: program =>
  program.frag: ctx =>
    ctx.out.color := vec4(vec3(ctx.in.uv.y), 1.0)   // black at top → white
```

### 2c. A panel as a static texture

A panel is a render target, so one can be rendered **once** and then bound
wherever a texture is wanted, instead of being repainted every frame:

```scala
val panel = p.panel(width = w, height = h, layer = p.layer(shade))
p.paint(panel)            // ← without this the panel is empty
shape.bind("tex" := panel)
```

**Don't forget the `paint`.** A panel only holds pixels once it has been
painted; one that never appears in the per-frame `paint` list and isn't painted
at construction stays blank, with no error anywhere.

## 3. Bindings

`p.binding[T]` (or `p.binding(initialValue)`) makes a uniform buffer; update it
with `b.set(v)` / `b := v` / `b.update(ref => …)`. Attach values by **field
name** with `"name" := value`:

```scala
shape.bind("mvp" := mvp, "tint" := Vec3(1, 0.5, 0.2))   // raw value auto-boxes
```

Values may be a `BufferBinding`, a raw uniform value (auto-boxed), a
`GPUSampler` (`p.samplerLinear`), a `Panel`, or a `panel.binding(...)`.

`p.samplerLinear` clamps at the edges. For a texture deliberately sampled with
UV running past `[0,1]` — a tile repeated across a large surface — build one
explicitly:

```scala
val tileSampler = p.sampler(
  FilterMode.Linear, FilterMode.Linear, FilterMode.Linear, AddressMode.Repeat,
)
```

Without `AddressMode.Repeat` the out-of-range UV clamps into a smear at the edge
rather than tiling. `bind` matches only on the **field name** and a compatible
**value type** — it is stage-agnostic and never mentions visibility (which is
convenient: the same `bind` call works regardless of stage).

Shader-stage **visibility** is instead declared in the shade's uniform schema
(the `U` type parameter), by wrapping field types: `VertexUniform[T]` /
`FragmentUniform[T]` (bare `T` = both stages; in `layerShade` everything is
fragment, so no wrapper needed). See the
[shader DSL guide](shader-dsl-guide.md).

**Panel textures** are declared as a separate `P` schema of `FragmentPanel`
markers and read via `ctx.textures.<name>`:

```scala
type P = (tex: FragmentPanel)
val shade = p.layerShade[U, P]: program =>
  program.frag: ctx =>
    ctx.out.color := ctx.textures.tex.sample(ctx.in.uv, ctx.bindings.samp)
// bind a source panel:  layer.bind("tex" := sourcePanel, "samp" := p.samplerLinear)
```

**Instances** (one draw per entry, sharing the form/shade) via
`shape.instances.add("model" := m, …)`.

### Uniform arrays

`UniformArray[T, N]` is a fixed-capacity array uniform — `N` is part of the
type, so a shade's declaration and its binding cannot disagree. Element types:
`Float`/`Double`, `Vec2`, `Vec3`, `Vec4`, `Mat2`/`Mat3`/`Mat4`.

```scala
type MaxStops = 8
type U = (stops: FragmentUniform[UniformArray[Vec4, MaxStops]], count: FragmentUniform[Double])

val colors = Arr(Vec4(…), Vec4(…), Vec4(…))   // 3 values, capacity 8
shape.bind("stops" := colors, "count" := colors.length.toDouble)
```

The values travel as a plain `Arr` — the capacity comes from the schema field,
so it is never restated.

**A shorter `Arr` than `N` is explicitly supported**, and it is easy to miss:
normally you would expect the array to have exactly `N` elements, and binding 3
values into an `array<vec4, 8>` looks like a mistake. It is not. This is how one
shade serves a varying number of steps — the capacity is the maximum, a `count`
uniform says how many are live, and the shader loops to `count`:

```scala
loop(1, count.toI32)(i => col := col.mix(stops(i).rgb, …))
```

Elements past the ones written read as zero on a fresh binding. One caveat: **a
later, shorter `set` does not clear the tail** — bind 8 values, then 3, and
elements 3..7 still hold the old ones. That is exactly what `count` masks off,
so the pattern is safe as long as the shader honours it.

A held binding takes its capacity from its own type, so updates stay bare too:

```scala
val stops = p.binding[UniformArray[Vec4, MaxStops]]   // held, updated per frame
stops.set(colors)
```

Where there is no capacity anywhere — no schema field, no binding type — name it
with `asUniform`:

```scala
p.binding(colors.asUniform[MaxStops])              // created with initial values
panel.bind("stops" := colors.asUniform[MaxStops])  // to fill fewer than MaxStops
```

`panel.bind` also takes a bare `Arr`, but sizes the buffer from the values — so
there it must be the full length the shades declare. See
[gotchas](gotchas.md#a-panelbind-array-is-sized-by-the-values-you-pass).

**`N` counts elements, not GPU rows.** WGSL requires a uniform array's element
stride to be a multiple of 16 bytes, so scalars and `Vec2`s are packed four and
two to a `vec4` row — `UniformArray[Double, 8]` declares `array<vec4<f32>, 2>`.
That is invisible from both sides: you bind 8 doubles and index `0..7`. `N` need
not be a multiple of the lane count; `UniformArray[Double, 7]` just leaves the
last lane dead, and still rejects an eighth value.

## 4. The render model (important)

`p.paint(panel)` does, per panel:

1. **Shape pass** — clears to `clearColor` (or loads if none), draws all
   `shapes` into the panel's base texture (with depth/MSAA if enabled).
2. **Layers** — runs each layer in order. A layer reads the previous pass's
   output; the painter auto-injects it as the layer's first panel-texture slot
   **unless** you bind that slot to an external panel (then it reads that
   instead — how a threshold/composite pass reads a separate scene panel).

`p.show(panel)` blits a painted panel to the canvas. Typical frame:

```scala
animate: tpf =>            // tpf is milliseconds (Rust passes seconds — rescale)
  time += tpf
  mvp.set(cam.viewProjMat * model)
  p.paint(panel)
  p.show(panel)
```

Multi-pass: pass panels to `paint` in dependency order, then `show` the last:

```scala
p.paint(scenePanel, bloomPanel, canvasPanel)   // each may sample the earlier ones
p.show(canvasPanel)
```

`p.paintAndShow(panel)` is the one-panel shortcut; `p.draw(shape, clearColor)`
renders a single shape straight to the canvas with no panel (simple demos).

**Static renderings (no `animate` loop) paint in `onResize`** when the canvas
can be resized: a resize clears the canvas, and the callback also runs
immediately, so no separate paint in setup is needed:

```scala
p.onResize: (w, h) =>
  p.paintAndShow(panel)
```

With a fixed-size canvas, a plain `p.paintAndShow(panel)` in setup is enough.

### Mip chains

`p.panel(mips = true)` (or `mipLevels = N`) allocates a mip chain. By default
the painter auto-generates the chain from mip 0 after rendering. If you build
the chain by hand with mip-targeted layers
(`p.layer(shade, mipSource = i, mipTarget = i+1)`), the painter detects that and
**skips** auto-generation so your pyramid survives — this is how bloom
downsample/upsample works (see the `mipmaps` example).

## 5. Camera, transforms, resize

```scala
val cam = PerspectiveCamera(fov = 0.9, aspect = w/h, near = 0.1, far = 100.0, pos = Vec3(0, 1.7, 0))
p.onResize: (w, h) => cam(aspect = w.toDouble / h)   // runs now + on every resize
// per frame:  mvp.set(cam.viewProjMat * obj.modelMat)
```

`Transform` is a mutable TRS (`translation`/`rotation`/`scale`); `SceneObject`
gives `.modelMat` / `.modelViewProjMat(cam)`. For first-person controls, pair
`p.input()` with `BasicFirstPersonCameraController` from
`trivalibs.graphics.scene` — construct it with the camera and the input state,
then call `controller.update(tpf)` each frame before reading `cam.viewProjMat`.

## 6. Events

Only `onResize` is wired. For pointer/keyboard, either use `p.input()`
([InputState]) or attach DOM listeners to `p.canvas`. Keep event handling out of
the `animate` body.

## 7. The tuning loop — `trivalibs.dev`

Authoring a sketch means editing a constant, rebuilding, and looking. Vite
hot-reloads the new module, which by default drops you back at the camera's
start position — so every rebuild costs a walk back to whatever you were looking
at. `import trivalibs.dev.*` fixes that:

```scala
val cam = PerspectiveCamera(fov = 0.85, near = 0.1, far = 150.0, pos = …)
devPreserve(cam)          // pos + rotH/rotV survive the reload, restored in place
```

- **`devPreserve(cam, label = "camera")`** — round-trips a `PerspectiveCamera`'s
  `pos` / `rotH` / `rotV` through `sessionStorage`, keyed by module URL so
  sketches don't collide. `fov` / `near` / `far` stay from the sketch config and
  `aspect` is left to `onResize`. Returns a handle whose `.reset` wipes it.
- **`devPreserve(key, init)`** — the same for any value with a `DevCodec`,
  returning a `DevVar[T]`; read and write `.value` and the latest is saved on
  the next reload.
- **`devMode`** — `true` under the Vite dev server, `false` in a built sketch
  (it tests `import.meta.hot`, which Vite strips in production, so the branch is
  tree-shaken out). Gate any **development affordance that must not ship** on it
  — a free-flying camera, a debug overlay, a bypass of a constraint the finished
  piece depends on:

  ```scala
  cam.pos = bounds.clearOf(
    cam.pos,
    eyeY = if devMode then cam.pos.y else EyeHeight,   // fly in dev, walk in prod
  )
  ```

  Better than a hand-rolled `val Debug = true`, which can be committed in the
  wrong state.

Outside dev mode every one of these is inert, so they cost nothing to leave in.

## Where to look next

- Op catalog & shader patterns → [shader-dsl-guide.md](shader-dsl-guide.md)
- Pitfalls (Double-on-left, mip clobber, F32 vs Double) →
  [gotchas.md](gotchas.md)
- Offscreen rendering, readback to pixels, screenshots, GPU selection →
  [painter-setup-guide.md](painter-setup-guide.md)
- Per-symbol API → Metals `get-docs`/`inspect`, or the Scaladoc site
- Canonical code → `examples/*`, one per feature
