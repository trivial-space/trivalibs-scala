# 2D Line Geometry ✅ done

Port of `trivalibs_core::rendering::line_2d` — a variable-width polyline builder
with mitre joins, smoothing, vertex cleanup, and a typed `BufferedGeometry`
exporter that feeds directly into the painter.

Companion documents:

- **`documents/strokes-painting-port-plan.md`** in the consuming sketch repo —
  the roadmap this plan is phase 2 of (multi-buffer `Form` → line2d → CPU
  helpers → the strokes painting sketch). Read it for how the pieces fit
  together; read this one for the `line_2d` port itself. Note the ordering: the
  multi-buffer `Form` lands **before** this plan, so the example in §3 can draw
  all its fragments from one form.
- [documents/done/mesh-geometry-port-plan.md](mesh-geometry-port-plan.md) —
  original combined plan (now an index); all prerequisites listed there are
  done.
- [documents/done/geometry3d-plan.md](geometry3d-plan.md) — sibling plan for
  Grid / Cuboid / Sphere (independent feature).
- [documents/rust-painter/repomix-trivalibs-core.xml](../rust-painter/repomix-trivalibs-core.xml)
  — Rust source bundle. Key section: lines 3136–3946 (`rendering/line_2d`).

**Changed since this was written:** `Lerp` no longer lives in
`src/graphics/geometry/package.scala` — it moved to
[src/graphics/math/interpolation.scala](../../src/graphics/math/interpolation.scala)
during phase 3 of the roadmap above, `Lerp[Unit]` and the companion-object
arrangement included. Everything below describes the geometry package as it
stood at the time; read those `Lerp` locations as historical.

---

## 1. Context

The Rust painter's brush / stroke work all routes through `line_2d`. A `Line` is
a sequence of `LineVertex` values, each carrying position, width, accumulated
length, direction, and optional user data. `toBufferedGeometry` expands the line
into a triangle-strip quad mesh (two verts per input vertex, joined at mitres)
and emits it as a typed `BufferedGeometry[LineAttribs]` that the existing
painter pipeline consumes unchanged via `Form` / `Shape`.

### Design decisions vs. Rust

| Rust pattern                          | Scala replacement                                                                                                              |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `LineData<EmptyData>` type alias      | `Line[Unit]` — `EmptyData` is `Unit`                                                                                           |
| `Lerp<f32>` bound on `T` everywhere   | `using Lerp[T]` only at operations that need it (smooth, cleanup, `toBufferedGeometry`)                                        |
| `NeighbourList<T>` doubly-linked list | Inlined `forEachWithNeighbours` helper (~15 lines) — the only consumer                                                         |
| Hard-coded `VertexData` struct        | `type LineAttribs = (position: Vec2, width: Float, length: Float, uv: Vec2, localUv: Vec2)` — `allocateAttribs` derives layout |

### Non-goals

- `data/neighbour_list/` — the container is not ported; only its iterator
  combinators are needed, inlined in `line2d.scala`.
- Round / square / butt cap styles — Rust does not have them either. Mitre join
  - ratio-cap only.

---

## 1a. As implemented

Landed as `src/graphics/geometry/line2d.scala` +
`test/geometry/Line2d.test.scala` + `examples/bevel_lines_2d/`. The algorithms
follow the Rust source verbatim; the API deviates from the sketch below in these
places.

**No `LineGeometryProps`.** Rust needs a props struct because it has no default
arguments — Scala does. `toBufferedGeometry` takes the seven options as default
parameters directly:

```scala
line.toBufferedGeometry(
    smoothDepth: Int = 0,
    smoothAngleThreshold: Double = 0.05,
    smoothMinLength: Double = 3.0,
    totalLength: Opt[Double] = null,
    prevDirection: Opt[Vec2] = null,
    nextDirection: Opt[Vec2] = null,
    swapTextureOrientation: Boolean = false,
)
```

`toBufferedGeometries` exposes only the three `smooth*` ones — it computes the
other four per fragment. Rust's `cap_width_length_ratio` is dropped: it is
declared in the Rust props struct but never read.

**`Line[T]` carries a `defaultData: T`** instead of Rust's `T: Default` bound
(Scala has no such typeclass). The companion supplies the data-less path:
`Line(20.0)` / `Line(20.0, lenOffset)` build a `Line[Unit]`; `Line(w, off, d)`
builds a `Line[T]`. `add(pos)` / `add(pos, width)` / `add(pos, width, data)` are
then plain overloads on the class.

**Naming**: `verts` (not `iter`) for the vertex array, `totalLength` for Rust's
`line_length()`, `lenOffset` for `len_offset`.

**`flatMapWithNeighbours` is the public combinator** (Rust's
`flat_map_with_prev_next`), argument order `(prev, curr, next)` — no separate
`forEachWithNeighbours`; nothing else needed one. Vertices are copied wherever
Rust copies (`LineVertex.copy`), so a transformation never aliases the source
line's vertices.

**Transforms preserve `defaultWidth` / `lenOffset` / `defaultData`.** Rust's
`FromIterator` impl resets them to `1.0` / `0.0`, which is a latent bug —
harmless there only because widths are always explicit on the vertices.

**The extension methods live in `object Line`**, not at top level: the package
already has a top-level `toBufferedGeometry` (the mesh one in `buffers.scala`),
and Scala 3 requires overloads to share a definition group. The companion is in
the implicit scope of both `Line[T]` and `Arr[Line[T]]`, so call sites are
unchanged.

**`Lerp` givens moved into an `object Lerp` companion** (along with the new
`Lerp[Unit]`), so implicit search finds them without a `given` import at every
call site.

**Painter fix pulled in by this port:** a strip-topology pipeline must declare
`primitive.stripIndexFormat` to be usable with `drawIndexed` — the
primitive-restart sentinel differs between `uint16` and `uint32`. `line2d` is
the first geometry in the codebase that is both indexed _and_ strip-topology, so
every draw was rejected with `IndexFormat::Undefined`. `Form` now records the
index format its buffers were uploaded with (`Form.indexFormat`), and
`getPipeline` sets `stripIndexFormat` from it for strip topologies only (it must
stay undefined for lists) and includes it in the pipeline cache key — two forms
on the same shade can differ in index width, since the width follows the vertex
count. Within one form, `set(geometries = …)` widens 16-bit index buffers to
32-bit if any sibling needs 32, because all buffers of a form share one
pipeline.

Gotcha found on the way: `Opt[A | B].get` misresolves in Scala 3 — on a union
element type the `.get` extension picks up something else and yields a lambda
instead of the value. Library code passes `BufferedGeometry.indices` through
without `.get` (flow typing off `notNull`), so nothing was affected, but avoid
`.get` on an `Opt` of a union type.

---

## 2. Implementation

**New file:** `src/graphics/geometry/line2d.scala`  
**New test:** `test/geometry/Line2d.test.scala`  
**Edit:** `src/graphics/geometry/package.scala` — add a `Lerp[Unit]` given next
to `doubleLerp` / `vec2Lerp` (it does **not** exist yet, and `Line[Unit]` needs
it).

### 2.1 Inline neighbour iterator

Private to `line2d.scala`. Replaces `map_with_prev_next` / `with_neighbours`
from `neighbour_list/traits.rs`:

```scala
inline def forEachWithNeighbours[T](arr: Arr[T])(
    inline f: (prev: Opt[T], curr: T, next: Opt[T]) => Unit,
): Unit =
  var i = 0
  val n = arr.length
  while i < n do
    val prev: Opt[T] = if i == 0     then null else arr(i - 1)
    val next: Opt[T] = if i == n - 1 then null else arr(i + 1)
    f(prev, arr(i), next)
    i += 1
```

A `flatMapWithNeighbours` variant that accumulates results into a fresh `Arr` is
also needed — both helpers are ~10 lines each.

### 2.2 `LineVertex[T]` and `Line[T]`

```scala
class LineVertex[T](
    val pos:   Vec2,
    var width: Double,
    var len:   Double,
    var dir:   Vec2,
    val data:  T,
):
  def pointTo(next: Vec2): Unit
  def smoothEdge(
      prev: LineVertex[T],
      next: LineVertex[T],
      ratio: Double,
      angleThreshold: Double,
  )(using Lerp[T]): Arr[LineVertex[T]]

object LineVertex:
  def apply[T](pos: Vec2, width: Double, data: T): LineVertex[T]
  def apply(pos: Vec2, width: Double): LineVertex[Unit]

class Line[T](val defaultWidth: Double, val offset: Double = 0.0):
  private val list: Arr[LineVertex[T]] = Arr()
  var totalLength: Double = 0.0

  def vertCount: Int
  def iter: Arr[LineVertex[T]]
  def first: LineVertex[T]
  def last:  LineVertex[T]
  def get(i: Int): LineVertex[T]

  // Building
  def add(pos: Vec2): Unit
  def add(pos: Vec2, width: Double): Unit
  def add(pos: Vec2, width: Double, data: T): Unit
  def addVert(v: LineVertex[T]): Unit       // auto-links prev.pointTo(v.pos)
  def addVertRaw(v: LineVertex[T]): Unit    // no linkage

  // Transformations — all return a new Line[T]
  def smoothEdges(ratio: Double, minDist: Double, angleThreshold: Double = 0.0)(using Lerp[T]): Line[T]
  def cleanup(minLenWidRatio: Double, widthThreshold: Double, angleThreshold: Double)(using Lerp[T]): Line[T]
  def splitAtAngle(angleThreshold: Double): Arr[Line[T]]

object Line:
  def apply[T](defaultWidth: Double): Line[T]
  def apply[T](defaultWidth: Double, offset: Double): Line[T]
  def fromPoints(defaultWidth: Double, points: Arr[Vec2]): Line[Unit]
```

### 2.3 Line → `BufferedGeometry[LineAttribs]`

Fixed vertex layout matching Rust `VertexData` at lines 3154–3162:

```scala
type LineAttribs = (position: Vec2, width: Float, length: Float, uv: Vec2, localUv: Vec2)

class LineGeometryProps(
    val smoothDepth:            Int     = 0,
    val smoothAngleThreshold:   Double  = 0.05,
    val smoothMinLength:        Double  = 3.0,
    val capWidthLengthRatio:    Double  = 1.0,
    val totalLength:            Opt[Double] = null,
    val prevDirection:          Opt[Vec2]   = null,
    val nextDirection:          Opt[Vec2]   = null,
    val swapTextureOrientation: Boolean = false,
)
object LineGeometryProps:
  val Default: LineGeometryProps = LineGeometryProps()

extension [T](line: Line[T])
  def toBufferedGeometry(
      props: LineGeometryProps = LineGeometryProps.Default,
  )(using Lerp[T]): BufferedGeometry[LineAttribs]

extension [T](lines: Arr[Line[T]])
  def toBufferedGeometries(
      props: LineGeometryProps = LineGeometryProps.Default,
  )(using Lerp[T]): Arr[BufferedGeometry[LineAttribs]]
```

Implementation follows Rust `buffered_geometry.rs` verbatim: mitre join math,
top/bottom vert generation, zig-zag index emission, conditional cap adjustment
via `prevDirection` / `nextDirection`. Uses `allocateAttribs[LineAttribs](n)`.

`toBufferedGeometries` threads `prevDirection` / `nextDirection` between
adjacent lines (Rust lines 3438–3453) and alternates `swapTextureOrientation`
per segment so stroke continuity is the default.

### 2.4 Test coverage

| Case                                       | Assertion                                                            |
| ------------------------------------------ | -------------------------------------------------------------------- |
| `LineVertex.pointTo`                       | mirrors Rust `vert_point_to` test (lines 3856–3872)                  |
| `Line.totalLength`                         | mirrors Rust `line_length` test (lines 3875–3887)                    |
| `Line.fromPoints`                          | mirrors Rust `from_vecs` test (lines 3889–3903)                      |
| `Line.cleanup` thresholds                  | mirrors Rust `cleanup_vertices` test (lines 3906–3945) — 5 sub-cases |
| `Line.splitAtAngle`                        | corner detection splits at expected segment boundaries               |
| `toBufferedGeometry` vert count            | `2N + cap adjustments` for N input verts                             |
| `toBufferedGeometry` index count           | correct zig-zag triangle-strip pattern                               |
| `toBufferedGeometry` UV                    | first / last vertex `v = 0.5` (cap center); midpoints alternate      |
| `toBufferedGeometries` direction threading | segment-N's `prevDirection` equals segment-(N-1)'s last `dir`        |

---

## 3. Example — `examples/bevel_lines_2d/`

A port of the original TypeScript/wasm test bed for the Rust line
implementation,
`/home/trival/code/personal/trivialspace/playground/src/public/tests/shapes/bevel-lines-2d-wasm`
(`crate/src/lib.rs`) — random points with wildly varying widths, put through
every transformation in §2.2. It is the visual acceptance test for this port,
and it is the second consumer of the **multi-buffer `Form`** (roadmap phase 1,
already in place by the time this lands), since `splitAtAngle` produces several
fragments drawn from one form.

### 3.1 Geometry (CPU, rebuilt on resize)

1. `Line(20.0)`, ~20 random points spread over 1.5× the canvas, random widths in
   `[20, 300]`.
2. `flatMapWithNeighbours` inserting two extra vertices at `lerp 0.333` /
   `lerp 0.666` with fresh random widths.
3. `cleanup(0.5, 0.1, 0.1)`.
4. `splitAtAngle(Pi * 3 / 4)` — the corner split.
5. `toBufferedGeometries(smoothDepth = 4, smoothAngleThreshold = 0.001, smoothMinLength = 5.0)`.

### 3.2 Rendering

One `Form` holding **all** fragment geometries
(`p.form(geometries = …, topology = PrimitiveTopology.TriangleStrip)`), one
`Shape`, one draw sequence.

`Attribs = LineAttribs`, `Varyings = (uv: Vec2, localUv: Vec2)`,
`Uniforms = (size: VertexUniform[Vec2])`. Vert: `pos = position / size`, output
`vec4(pos.x, -pos.y, 0, 1)` (no `fit0111` — the points are generated centered on
the origin, as in the original test). Frag: `vec4(uv, 1, 1)` — the original
test's uv debug color, which makes mitre and uv errors immediately visible.
White clear color; `onResize` regenerates the geometry and updates `size`.

**Gate:** clean mitre joins, no gaps at the `splitAtAngle` corners, uv gradient
continuous across fragments. Served at `/bevel_lines_2d/`.

---

## 4. Implementation order

1. **`line2d.scala`** — `LineVertex`, `Line`, `forEachWithNeighbours`, all
   transformation methods.
2. **`toBufferedGeometry`** — mitre join algorithm + index emission.
3. **`Line2d.test.scala`** — all §2.4 cases passing.
4. **Example** — `examples/bevel_lines_2d/`, verified visually. Uses the
   multi-buffer `Form` from roadmap phase 1, which is done before this plan
   starts.

---

## 5. Critical files

| File                                                                             | Action                  |
| -------------------------------------------------------------------------------- | ----------------------- |
| [src/graphics/geometry/line2d.scala](../../src/graphics/geometry/line2d.scala)   | **New**                 |
| [test/geometry/Line2d.test.scala](../../test/geometry/Line2d.test.scala)         | **New**                 |
| [src/graphics/geometry/package.scala](../../src/graphics/geometry/package.scala) | Edit — add `Lerp[Unit]` |
| [examples/bevel_lines_2d/](../../examples/bevel_lines_2d/)                       | **New**                 |

### Existing files to reuse

- [src/graphics/geometry/buffers.scala](../../src/graphics/geometry/buffers.scala)
  — `BufferedGeometry[F]`
- [src/graphics/geometry/package.scala](../../src/graphics/geometry/package.scala)
  — `Lerp[Vec2]` given (`Lerp[Unit]` is added by this plan)
- [src/graphics/buffers/attributes.scala](../../src/graphics/buffers/attributes.scala)
  — `allocateAttribs[LineAttribs]`
- [src/utils/js.scala](../../src/utils/js.scala) — `Arr`, `Opt`
- [src/utils/numbers.scala](../../src/utils/numbers.scala) — `NumExt` (`.sin`,
  `.cos`, `.sqrt`)

---

## 6. Verification

```bash
bun run check            # library type-checks in isolation
bun run test             # Line2d.test.scala passes
bun run examples:build   # then examples:dev → bevel_lines_2d
```
