# FloatArray — Optimized Flat F32 Buffer Abstraction

## Motivation

The current `StructArray[Fields <: Tuple]` covers the general case well:
arbitrary nesting, mixed primitive types, compile-time offsets via match types.
But real vertex and uniform buffer data has a much more constrained shape:

1. **F32-only**: 99% of GPU uploads are 32-bit floats — positions, normals, UVs,
   matrices.
2. **Depth-1 named tuple rows**: typical vertex layouts are flat named tuples
   whose fields are compound float groups (`Vec3` = 3 floats, `Mat4` = 16
   floats), never nested structs.

The key performance issue: `StructArray` uses `ArrayBuffer + DataView`, and
every single float write becomes a
`DataView.setFloat32(byteOffset, value, true)` call in JavaScript. That is a
method call with bounds-checking, endianness dispatch, and offset arithmetic.

**Float32Array** provides direct indexed access: `arr[floatIndex] = value`
compiles to a single typed-array store in V8 — no method overhead, no endianness
flag, CPU-optimal memory access pattern. For a 16-float Mat4, this is 16 raw
stores vs 16 method calls.

Beyond performance, this new abstraction is purpose-built for graphics: the leaf
types map directly to GPU concepts, and deep integration with the math library
means buffer writes use the same types as CPU-side computation.

---

## Core Design: FN Leaf Types

Instead of a generic `F32Field[T]` type class, we define a fixed set of
**float-group leaf types** that cover all GPU data shapes:

| Type  | Floats | GPU Usage                             |
| ----- | ------ | ------------------------------------- |
| `F1`  | 1      | Float scalar (time, opacity, etc.)    |
| `F2`  | 2      | Vec2 (UV, 2D position)                |
| `F3`  | 3      | Vec3 packed (position, normal, color) |
| `F4`  | 4      | Vec4, Vec3 padded (std140), Mat2      |
| `F9`  | 9      | Mat3 packed                           |
| `F12` | 12     | Mat3 padded (std140: 3 × Vec4)        |
| `F16` | 16     | Mat4                                  |

These types serve **dual purpose**:

1. **Schema markers** in named tuple type definitions
2. **Runtime references** into a `Float32Array` (zero-cost via opaque types)

```scala
// Schema definition — the struct layout is explicit in float groups
type VertexRow   = (pos: F3, normal: F3, uv: F2)
type ParticleRow = (pos: F3, vel: F3, time: F1, life: F1)
type UniformRow  = (viewMat: F16, projMat: F16, camPos: F4, time: F1)
//                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                  F4 for camPos: Vec3 padded to 4 floats for std140

// Runtime — FN values are references into the Float32Array
val row = arr(i)
row.pos.x = 1.0     // writes directly to Float32Array
row.normal := myVec3 // via Vec3Mutable given
```

**Padding is explicit**: the user chooses `F3` for packed Vec3 (vertex
attribute) or `F4` for padded Vec3 (std140 uniform). No phantom tags or separate
given sets needed.

---

## FN Opaque Types

All FN types share the same runtime representation — a `(Float32Array, Int)`
pair — but are distinct at the type level for match-type dispatch:

```scala
import scala.scalajs.js.typedarray.Float32Array

// Shared base — js.Object for zero-cost field access
class FloatRef(val arr: Float32Array, val idx: Int) extends js.Object

opaque type F1  = FloatRef
opaque type F2  = FloatRef
opaque type F3  = FloatRef
opaque type F4  = FloatRef
opaque type F9  = FloatRef
opaque type F12 = FloatRef
opaque type F16 = FloatRef

type FloatValue = F1 | F2 | F3 | F4 | F9 | F12 | F16
```

Each FN companion provides constructors and indexed float access.

**All values are `Double`** — matching the math library. In JavaScript, all
numbers are IEEE 754 doubles at runtime. When writing to `Float32Array`, the JS
engine handles double→float truncation natively. No `.toFloat` conversion on the
Scala side.

```scala
object F1:
  inline def apply(arr: Float32Array, idx: Int): F1 = FloatRef(arr, idx)
  extension (f: F1)
    inline def get: Double          = f.arr(f.idx)
    inline def set(v: Double): Unit = f.arr(f.idx) = v
    // F1 is optimized: direct get/set, no index parameter needed

object F3:
  inline def apply(arr: Float32Array, idx: Int): F3 = FloatRef(arr, idx)
  extension (f: F3)
    inline def get(n: Int): Double          = f.arr(f.idx + n)
    inline def set(n: Int, v: Double): Unit = f.arr(f.idx + n) = v
    // convenience: set all 3 at once
    inline def setAll(x: Double, y: Double, z: Double): Unit =
      f.arr(f.idx) = x; f.arr(f.idx + 1) = y; f.arr(f.idx + 2) = z

// F2, F4, F9, F12, F16 analogously
```

**Note on Scala.js `Float32Array` facade**: The Scala.js typed array facade
declares `Float32Array.update(index: Int, value: Float)`. We may need to use
`.asInstanceOf` or `js.Dynamic` to pass `Double` directly without the Scala
compiler inserting a `$fround` call. Alternatively, we can define our own thin
facade that takes `Double`:

```scala
@js.native @JSGlobal
class F32Array(len: Int) extends js.typedarray.TypedArray[Double, F32Array]:
  @JSBracketAccess def apply(index: Int): Double = js.native
  @JSBracketAccess def update(index: Int, value: Double): Unit = js.native
```

This avoids any Scala-level float conversion — the JS engine receives a double
and truncates it on typed-array store, which is the fastest path.

---

## Compile-Time Layout via Match Types

Since FN types are distinct opaque types, match types work directly — no
`summonInline`:

```scala
/** Float count for a single field type */
type FloatCount[T] <: Int = T match
  case F1  => 1
  case F2  => 2
  case F3  => 3
  case F4  => 4
  case F9  => 9
  case F12 => 12
  case F16 => 16

/** Total float count (stride) for a row tuple */
type RowStride[Fields <: Tuple] <: Int = Fields match
  case EmptyTuple => 0
  case h *: t     => FloatCount[h] + RowStride[t]

/** Float offset of field at index N within a row */
type FieldOffset[Fields <: Tuple, N <: Int] <: Int = N match
  case 0 => 0
  case scala.compiletime.ops.int.S[n1] =>
    Fields match
      case h *: t => FloatCount[h] + FieldOffset[t, n1]
```

Example: for `(pos: F3, uv: F2, time: F1)`:

- `RowStride` = 3 + 2 + 1 = **6** (compile-time literal)
- `FieldOffset[_, 0]` = 0 (pos)
- `FieldOffset[_, 1]` = 3 (uv)
- `FieldOffset[_, 2]` = 5 (time)

All computed at compile time via `constValue`, identical to how `StructArray`
works.

---

## `FloatArray[Row]` — The Array

```scala
opaque type FloatArray[Row <: Tuple] = Float32Array
// Float32Array.length = count * stride

object FloatArray:
  transparent inline def allocate[Row <: Tuple](count: Int): FloatArray[Row] =
    new Float32Array(constValue[RowStride[Row]] * count)

  extension [Row <: Tuple](arr: FloatArray[Row])
    inline def stride: Int = constValue[RowStride[Row]]
    inline def length: Int = arr.length / constValue[RowStride[Row]]
    inline def jsArray: Float32Array = arr

    transparent inline def apply(index: Int): FloatStruct[Row] =
      FloatStruct(arr, index * constValue[RowStride[Row]])

    // Low-level building blocks
    inline def setFloat(offset: Int, value: Float): Unit =
      arr(offset) = value
    inline def getFloat(offset: Int): Float =
      arr(offset)
```

**Schema flexibility**: `Row` can be a named tuple, unnamed tuple, or
`NamedTuple.From[CaseClass]`. The library internally uses `DropNames` to get the
plain value tuple for match-type dispatch (`RowStride`, `FieldOffset`). Users
never see this — they write `FloatArray.allocate[MyRow](n)` with whatever form
is natural for their use case.

---

## `FloatStruct[Row]` — Row Reference

```scala
opaque type FloatStruct[Row <: Tuple] = FloatRef
// FloatRef(arr, idx) where idx = row base index in float units

object FloatStruct:
  inline def apply[Row <: Tuple](arr: Float32Array, idx: Int): FloatStruct[Row] =
    FloatRef(arr, idx)

  extension [Row <: Tuple](ref: FloatStruct[Row])
    inline def arr: Float32Array = ref.arr
    inline def baseIdx: Int      = ref.idx

    /** Access field at compile-time index N, returning the appropriate FN type */
    transparent inline def field(n: Int): Tuple.Elem[Row, n.type] =
      val off = ref.idx + constValue[FieldOffset[Row, n.type]]
      inline erasedValue[Tuple.Elem[Row, n.type]] match
        case _: F1  => F1(ref.arr, off)
        case _: F2  => F2(ref.arr, off)
        case _: F3  => F3(ref.arr, off)
        case _: F4  => F4(ref.arr, off)
        case _: F9  => F9(ref.arr, off)
        case _: F12 => F12(ref.arr, off)
        case _: F16 => F16(ref.arr, off)
```

Usage:

```scala
type VertexRow = (pos: F3, uv: F2, time: F1)
val arr = FloatArray.allocate[VertexRow](1000)
val row: FloatStruct[VertexRow] = arr(0)
val pos: F3 = row.field(0)   // compile-time: F3 at offset 0
val uv:  F2 = row.field(1)   // compile-time: F2 at offset 3
val t:   F1 = row.field(2)   // compile-time: F1 at offset 5
```

### Named field access (future)

For `row.pos` syntax instead of `row.field(0)`, we would need either:

- `Selectable` trait with `Fields` type member — but `FloatStruct` is an opaque
  type
- Macro-generated extension methods
- A code-gen approach at the usage site

Deferred to a later phase. Positional `field(n)` is sufficient initially and
matches the existing `StructRef.apply(n)` pattern.

---

## Math Library Integration

The FN types implement the appropriate math traits from `graphics.math`:

```scala
// In graphics.math.cpu or a dedicated bridge file

// F3 as a mutable Vec3 — all Double, no toFloat conversion
given Vec3Mutable[F3]:
  extension (v: F3)
    inline def x: Double      = v.get(0)
    inline def y: Double      = v.get(1)
    inline def z: Double      = v.get(2)
    inline def x_=(d: Double) = v.set(0, d)
    inline def y_=(d: Double) = v.set(1, d)
    inline def z_=(d: Double) = v.set(2, d)

given Vec3MutableOps[F3] = new Vec3MutableOps[F3] {}

// F2 as a mutable Vec2
given Vec2Mutable[F2]:
  extension (v: F2)
    inline def x: Double      = v.get(0)
    inline def y: Double      = v.get(1)
    inline def x_=(d: Double) = v.set(0, d)
    inline def y_=(d: Double) = v.set(1, d)

given Vec2MutableOps[F2] = new Vec2MutableOps[F2] {}

// F4 as a mutable Vec4 (also used for padded Vec3)
given Vec4Mutable[F4]:
  extension (v: F4) ...

// F16 as a mutable Mat4
given Mat4Mutable[F16]:
  extension (m: F16) ...

// F4 as a mutable Mat2 (Mat2 = 4 floats)
// This creates ambiguity with Vec4 — resolve via explicit naming or separate type?
// Option: Mat2 uses F4 but user writes to it via Mat2MutableOps explicitly
```

This gives us **direct interop** with all math operations:

```scala
val row = arr(i)
val pos: F3 = row.field(0)

// Read: all Vec3Base operations work
val len = pos.length
val d   = pos.dot(someVec3)

// Write: Vec3Mutable + Vec3MutableOps
pos := someVec3         // set from any Vec3-like type
pos.normalize           // in-place normalize
pos += velocity * dt    // in-place add

// Write from immutable ops result:
val reflected = someVec3 - normal * (2.0 * someVec3.dot(normal))
pos := reflected        // reflected is a CPU Vec3, assigned into buffer
```

**No Double→Float conversion in Scala**: All FN getter/setter methods use
`Double`. In JavaScript, all numbers are doubles. Writing a double to
`Float32Array` is handled natively by the JS engine (truncation on store,
promotion on load). We avoid any Scala-level `.toFloat` calls — the JS runtime
does this more efficiently than Scala's `$fround` helper.

### F1 special case

`F1` does not correspond to any vector/matrix trait. It has its own optimized
API:

```scala
object F1:
  extension (f: F1)
    inline def get: Double          = f.arr(f.idx)
    inline def set(v: Double): Unit = f.arr(f.idx) = v
```

### Accepting any VecNBase / MatNBase

The `set` method (via `:=` from `Vec3MutableOps`) already accepts any type that
implements `Vec3Base` — this is how the existing math library works. So:

```scala
pos := cpuVec3        // Vec3 (mutable Double class)
pos := immutableVec3  // Vec3Tuple or any Vec3Base implementation
pos := otherF3        // another F3 from a different buffer
```

No additional conversion code needed — the trait hierarchy handles it.

---

## Flexible Row Write API

The `set` method on `FloatArray` should accept **named tuples, unnamed tuples,
and case classes** — any `Product` whose fields match the FN schema's math
types. All the type-level machinery lives inside the library; user code stays
clean.

### The `InputRow` type

Each FN type maps to the math types it accepts:

```scala
/** Map FN schema → accepted input types for set() */
type InputRow[Row <: Tuple] <: Tuple = Row match
  case EmptyTuple => EmptyTuple
  case F1  *: t => Double *: InputRow[t]
  case F2  *: t => Vec2   *: InputRow[t]
  case F3  *: t => Vec3   *: InputRow[t]
  case F4  *: t => Vec4   *: InputRow[t]
  case F9  *: t => Mat3   *: InputRow[t]
  case F12 *: t => Mat3   *: InputRow[t]
  case F16 *: t => Mat4   *: InputRow[t]
```

This is essentially `MapFromFN` applied to the row tuple. For a named tuple
schema, the named variant preserves field names:

```scala
type ParticleRow = (pos: F3, vel: F3, life: F1)
// InputRow = (Vec3, Vec3, Double) — unnamed
// Named variant = (pos: Vec3, vel: Vec3, life: Double)
```

### Usage — all three forms

```scala
val arr = FloatArray.allocate[ParticleRow](1000)

// 1. Named tuple — field names match the schema
arr.set(0, (pos = myVec, vel = velVec, life = 2.0))

// 2. Unnamed tuple — positional match
arr.set(1, (myVec, velVec, 2.0))

// 3. Case class
case class Particle(pos: Vec3, vel: Vec3, life: Double)
arr.set(2, Particle(myVec, velVec, 3.0))
```

All three work because:

- Named tuples, unnamed tuples, and case classes are all `Product` in Scala 3
- `productElement(i)` gives uniform field access at runtime
- Unnamed tuples conform to named tuples (SIP)
- Type safety comes from the compile-time parameter constraint

### Implementation

```scala
extension [Row <: Tuple](arr: FloatArray[Row])
  /** Set row at index from any matching Product (tuple or case class) */
  transparent inline def set(index: Int, values: InputRow[Row]): Unit =
    val ref = arr(index)
    writeRowFields[Row](ref, values.asInstanceOf[Product], 0)

/** Inline recursive write — expands at compile time to direct Float32Array stores */
private inline def writeRowFields[Row <: Tuple](
  ref: FloatStruct[Row], src: Product, fieldIdx: Int
): Unit =
  inline erasedValue[Row] match
    case _: EmptyTuple => ()
    case _: (F1 *: t) =>
      val f = ref.field(fieldIdx).asInstanceOf[F1]
      f.set(src.productElement(fieldIdx).asInstanceOf[Double])
      writeRowFields[t](ref, src, fieldIdx + 1)
    case _: (F3 *: t) =>
      val v = src.productElement(fieldIdx)   // Vec3 or any Vec3Base
      val f = ref.field(fieldIdx).asInstanceOf[F3]
      f.set(0, v.asInstanceOf[Vec3].x)
      f.set(1, v.asInstanceOf[Vec3].y)
      f.set(2, v.asInstanceOf[Vec3].z)
      writeRowFields[t](ref, src, fieldIdx + 1)
    // F2, F4, F9, F12, F16 analogously
```

Each `inline erasedValue` branch compiles away — at the call site this becomes a
flat sequence of `Float32Array` index writes with no loops, no allocation, no
boxing.

### Accepting wider types via VecNBase

The inline write currently casts to concrete `Vec3`. To accept **any**
`Vec3Base` implementation (tuples, other buffer refs, etc.), use the given
directly:

```scala
    case _: (F3 *: t) =>
      val v = src.productElement(fieldIdx)
      val f = ref.field(fieldIdx).asInstanceOf[F3]
      // Use Vec3Base extension to read x/y/z from any Vec3-like type
      inline summonInline[Vec3Base[v.type]] match
        case base =>
          f.set(0, base.x(v)); f.set(1, base.y(v)); f.set(2, base.z(v))
```

This may need refinement during implementation — `productElement` returns `Any`,
so we'd need either a runtime type class lookup or constrain `InputRow` to
concrete types. The simplest first pass: accept the concrete math types (`Vec3`,
`Vec2`, `Vec4`, `Mat4`, `Double`) and later generalize.

### Field-by-field access (still available)

The `set` API writes a full row. For partial updates (e.g. update only `life` in
a particle system), use `field(n)` with the math givens:

```scala
arr(i).field(0) := newPos     // update just pos via Vec3MutableOps
arr(i).field(2).set(newLife)  // update just life via F1.set
```

---

## GPU Upload

`FloatArray` gives the `Float32Array` directly:

```scala
device.queue.writeBuffer(gpuBuffer, 0, arr.jsArray)
```

The `Float32Array.buffer` property provides the `ArrayBuffer` if needed for
mapped ranges.

---

## Relation to Current `StructArray` and Geometry Library

### Current geometry buffer pattern

```scala
type VertexWriter[T, F <: Tuple]  = (T, StructRef[F]) => Unit
// F is e.g. (F32, F32, F32) — flat primitive markers

transparent inline def buildFaceVertices[T, F <: Tuple](
  mesh: Mesh[T], writer: VertexWriter[T, F]
)(using pos: Position[T]): BufferedGeometry[F]
```

### New pattern with FloatArray

```scala
type FloatWriter[T, Row <: Tuple] = (T, FloatStruct[Row]) => Unit

transparent inline def buildFaceVertices[T, Row <: Tuple](
  mesh: Mesh[T], writer: FloatWriter[T, Row]
)(using pos: Position[T]): FloatBufferedGeometry[Row]

class FloatBufferedGeometry[Row <: Tuple](
  val vertices: FloatArray[Row],
  val indices: Opt[Uint16Array | Uint32Array],
)
```

### Migration strategy

1. Build `FloatArray` as a standalone module alongside `StructArray`
2. Add math library givens for FN types
3. Validate with unit tests
4. Add `FloatBufferedGeometry` as alternative to `BufferedGeometry`
5. Optionally migrate geometry builders; existing `StructArray`-based code keeps
   working

---

## File Organisation

```
src/graphics/floatarray/floatarray.scala  — FN types, FloatArray, FloatStruct, match types
src/graphics/floatarray/mathrefs.scala   — Vec/Mat givens for F1..F16
src/graphics/geometry/fbuffers.scala     — FloatBufferedGeometry + builders (Phase 4)

test/floatarray/                         — unit tests for FN access, FloatArray, FloatStruct
test/geometry/FBuffers.test.scala        — geometry integration tests
```

---

## Implementation Phases

### Phase 1 — FN leaf types and match types (`src/graphics/floatarray/floatarray.scala`)

1. `FloatRef` base class
2. `F1`, `F2`, `F3`, `F4`, `F9`, `F12`, `F16` opaque types with companions
3. `FloatCount`, `RowStride`, `FieldOffset` match types
4. Tests: verify `constValue[RowStride[...]]` for various row shapes

### Phase 2 — FloatArray and FloatStruct

1. `FloatArray[Row]` opaque type: `allocate`, `length`, `stride`,
   `apply(index)`, `jsArray`
2. `FloatStruct[Row]` opaque type: `field(n)` with `transparent inline` dispatch
3. Tests: allocate, write via FN, verify raw Float32Array contents

### Phase 3 — Math library integration (`src/graphics/floatarray/mathrefs.scala`)

1. `Vec2Mutable[F2]` + `Vec2MutableOps[F2]` givens
2. `Vec3Mutable[F3]` + `Vec3MutableOps[F3]` givens
3. `Vec4Mutable[F4]` + `Vec4MutableOps[F4]` givens
4. `Mat4Mutable[F16]` given
5. `Mat3Mutable[F9]` given (if Mat3 exists)
6. Tests: write via `:=`, read via `.x/.y/.z`, verify interop with CPU Vec3

### Phase 4 — Schema derivation and mesh integration

1. `ToFN` match type (Vec3 → F3, etc.)
2. `FromFN` reverse match type (F3 → Vec3, etc.) — for shader attribute
   contracts
3. `MapToFN` / `MapFromFN` tuple-level mapping
4. `F32Write[T, FN]` type class or inline copy via mutable givens
5. `FloatBufferedGeometry[Row]` + automatic buffer builders
6. Normal-appending strategies (`AppendNormal`)
7. Tests: mesh → FloatArray round-trip, verify buffer contents

### Phase 5 — Painter integration (deferred)

1. Painter `Form` accepts `FloatArray` for vertex upload
2. Shader attribute contracts use `MapFromFN` to derive idiomatic types

---

## Flexible Schema Input

The library accepts **named tuples, unnamed tuples, and case classes** for both
schema definitions and row writes. The type-level noise lives inside the
library; user code stays clean.

```scala
// Schema definition — all forms work:
type VertexRow = (pos: F3, uv: F2, time: F1)            // named tuple
val arr1 = FloatArray.allocate[VertexRow](1000)
val arr2 = FloatArray.allocate[(F3, F2, F1)](1000)       // unnamed tuple
case class Particle(pos: F3, vel: F3, life: F1)
val arr3 = FloatArray.allocate[NamedTuple.From[Particle]](1000) // case class

// Row writes — all forms work (see "Flexible Row Write API" section):
arr1.set(0, (pos = myVec, uv = myUv, time = 1.0))      // named tuple
arr1.set(1, (myVec, myUv, 2.0))                         // unnamed tuple
arr1.set(2, MyVertexClass(myVec, myUv, 3.0))            // case class
```

Internally, the library uses `NamedTuple.DropNames[Row]` to get the plain value
tuple for match-type dispatch (`RowStride`, `FieldOffset`). This is accepted
library-internal noise that users never see.

---

## FN Trait Ambiguity: Default + Import Pattern

`F4` can represent Vec4, Vec3-padded, or Mat2. `F9` can be Mat3. This is the
same situation as the existing `StructRef` math givens in `binding.scala`.

**Resolution**: Same pattern — provide the most common given by default, import
specific ones where needed:

```scala
// Default givens (always in scope)
given Vec4Mutable[F4]     // F4 is Vec4 by default
given Vec3Mutable[F3]
given Vec2Mutable[F2]
given Mat4Mutable[F16]

// Specialized givens — import at use site
object F4AsMat2:
  given Mat2Mutable[F4]   // import F4AsMat2.given where Mat2 is needed

object F4AsVec3Padded:
  given Vec3Mutable[F4]   // for std140 padded Vec3
  // writes x,y,z; ignores w padding float

object F9AsMat3:
  given Mat3Mutable[F9]
```

Usage mirrors existing pattern from `binding.scala`:

```scala
// Default: F4 as Vec4
ref.field(0) := someVec4

// Specific: F4 as Mat2
import F4AsMat2.given
ref.field(2) := someMat2
```

---

## Automatic Schema Derivation from Math Types

Mesh vertex types like `(pos: Vec3, uv: Vec2)` should be **automatically
mapped** to buffer schemas like `(pos: F3, uv: F2)` during buffer generation.
This is a compile-time type-level mapping:

```scala
/** Map a single math type to its FN buffer representation */
type ToFN[T] = T match
  case Double => F1
  case Float  => F1
  case Vec2   => F2
  case Vec3   => F3
  case Vec4   => F4
  case Mat2   => F4
  case Mat3   => F9
  case Mat4   => F16

/** Map an entire row tuple: Vec3 *: Vec2 *: EmptyTuple → F3 *: F2 *: EmptyTuple */
type MapToFN[Fields <: Tuple] <: Tuple = Fields match
  case EmptyTuple => EmptyTuple
  case h *: t     => ToFN[h] *: MapToFN[t]
```

**Reverse mapping** — FN types back to idiomatic math types. This is needed for
shader attribute contracts, which use `Vec3`/`Mat4` etc. in their named tuple
definitions:

```scala
/** Map an FN type back to its canonical math type */
type FromFN[T] = T match
  case F1  => Double
  case F2  => Vec2
  case F3  => Vec3
  case F4  => Vec4
  case F9  => Mat3
  case F12 => Mat3  // padded variant maps back to same Mat3
  case F16 => Mat4

/** Reverse-map an entire row tuple */
type MapFromFN[Fields <: Tuple] <: Tuple = Fields match
  case EmptyTuple => EmptyTuple
  case h *: t     => FromFN[h] *: MapFromFN[t]
```

For named tuples, both directions preserve field names via `NamedTuple.Map`:

```scala
// User defines vertex data:
type MyVertex = (pos: Vec3, uv: Vec2)

// Library derives buffer schema:
type BufferRow = NamedTuple.Map[MyVertex, ToFN]
// = (pos: F3, uv: F2)

// Library derives shader attribute contract from buffer schema:
type AttribContract = NamedTuple.Map[BufferRow, FromFN]
// = (pos: Vec3, uv: Vec2)

// Or from FN schema directly — e.g. when buffer schema was defined manually:
type ManualRow = (pos: F3, normal: F3, uv: F2, time: F1)
type AttribFromManual = NamedTuple.Map[ManualRow, FromFN]
// = (pos: Vec3, normal: Vec3, uv: Vec2, time: Double)
```

This bidirectional mapping means the FN schema is the **single source of
truth**: define the buffer layout once in FN types, and derive both the GPU
buffer structure and the shader attribute contract from it.

### Writing math values into FN refs

A type class handles the write from any math-compatible type into its FN ref.
The instances use `VecNBase` / `MatNBase` traits so they accept **any**
implementation (CPU Vec3, immutable tuples, other buffer refs, etc.):

```scala
trait F32Write[T, FN]:
  extension (fn: FN) def writeFrom(value: T): Unit

// F3 accepts any Vec3Base implementation
given [V: Vec3Base] => F32Write[V, F3]:
  extension (fn: F3) def writeFrom(value: V): Unit =
    fn.set(0, value.x); fn.set(1, value.y); fn.set(2, value.z)

// F2 accepts any Vec2Base implementation
given [V: Vec2Base] => F32Write[V, F2]:
  extension (fn: F2) def writeFrom(value: V): Unit =
    fn.set(0, value.x); fn.set(1, value.y)

// F4 accepts any Vec4Base implementation
given [V: Vec4Base] => F32Write[V, F4]:
  extension (fn: F4) def writeFrom(value: V): Unit =
    fn.set(0, value.x); fn.set(1, value.y); fn.set(2, value.z); fn.set(3, value.w)

// F16 accepts any Mat4Base implementation
given [M: Mat4Base] => F32Write[M, F16]:
  extension (fn: F16) def writeFrom(value: M): Unit =
    fn.set(0, value.m00); fn.set(1, value.m01); /* ... all 16 elements */

// F1 accepts Double directly
given F32Write[Double, F1]:
  extension (fn: F1) def writeFrom(value: Double): Unit =
    fn.set(value)
```

This means the `set` API and mesh integration automatically accept any type that
implements the right math trait — not just the concrete CPU classes.

---

## Mesh Integration: Automatic Buffer Generation

The geometry library currently uses manual `VertexWriter` functions:

```scala
// Current: user writes the conversion manually
def writePos(v: Vec3, ref: StructRef[(F32, F32, F32)]): Unit =
  ref.setAt(0)(v.x.toFloat); ref.setAt(1)(v.y.toFloat); ref.setAt(2)(v.z.toFloat)
```

With `FloatArray` + schema derivation, the conversion is **automatic**:

```scala
// Mesh vertex type — user defines their domain data
type MyVertex = (pos: Vec3, uv: Vec2)

// Buffer schema is derived automatically
type BufferRow = NamedTuple.Map[MyVertex, ToFN]  // = (pos: F3, uv: F2)

// Buffer generation — no manual writer needed!
val bg = mesh.toFloatBuffer[MyVertex]()
// Internally: allocates FloatArray[BufferRow], iterates faces,
// copies each field via the mutable givens
```

### Normal-appending strategies

For `FaceVerticesWithFaceNormal` and vertex-normal strategies, the normal is
**appended automatically** to the buffer schema:

```scala
// User's vertex type
type MyVertex = (pos: Vec3, uv: Vec2)

// FaceVerticesWithFaceNormal strategy derives:
type BufferRowN = (pos: F3, uv: F2, normal: F3)
//                                   ^^^^^^^^^^^ appended by the library

// At the type level:
type AppendNormal[Row <: Tuple] = Tuple.Concat[Row, F3 *: EmptyTuple]
// For named tuples: concat with (normal: F3)
```

The buffer builder:

```scala
transparent inline def buildFaceVerticesWithNormal[V, Row <: Tuple](
  mesh: Mesh[V],
)(using pos: Position[V]): FloatBufferedGeometry[AppendNormal[MapToFN[DropNames[Row]]]] =
  // 1. Derive buffer stride from MapToFN[Row] + 3 (for normal)
  // 2. Allocate FloatArray
  // 3. For each face:
  //    a. Write each vertex field via inline field-by-field copy
  //    b. Write face normal into the appended F3 slot
```

### The write loop — inline field-by-field copy

Given a source vertex `v: MyVertex = (pos: Vec3, uv: Vec2)` and a target
`ref: FloatStruct[BufferRow]`, the library generates an inline copy loop:

```scala
private inline def copyFields[Src <: Tuple, Dst <: Tuple](
  src: Tuple, ref: FloatStruct[Dst], fieldIdx: Int
): Unit =
  inline erasedValue[Src] match
    case _: EmptyTuple => ()
    case _: (h *: t) =>
      val fn = ref.field(fieldIdx)
      // fn is the FN type; src field is the math type
      // The mutable given for FN accepts the math type via :=
      fn := src.productElement(fieldIdx).asInstanceOf[h]
      copyFields[t, Dst](src, ref, fieldIdx + 1)
```

This inlines to direct `Float32Array` writes at each call site — zero overhead.

---

## Open Questions

- **Named field access**: `row.pos` instead of `row.field(0)` requires
  `Selectable`, macros, or code-gen. Important for API ergonomics — investigate
  `Selectable` with opaque types. Deferred to a later phase but high priority.

- **Iteration**: `FloatArray` should support `for i <- arr.indices do arr(i)...`
  loop pattern. Consider adding `Iterable` conversion like `StructArray`.
  Additionally, investigate zero-allocation iteration via a **shared mutable
  `FloatRef`** whose `idx` is updated each step — avoids creating a new
  `FloatRef` per row. API examples:

  ```scala
  // Zero-alloc: reuses one FloatRef, mutates idx each step
  arr.foreachMutable: ref =>
    ref.field(0) := newPos   // ref is only valid inside the callback

  // Zero-alloc update by index
  arr.updateMutable(i): ref =>
    ref.field(2).set(newLife)
  ```

  Names like `foreachMutable` / `updateMutable` signal that the ref must not be
  stored or reused outside the callback. Deferred to a later phase.

- **Match type on concrete classes**: `ToFN[Vec3]` matching on the concrete
  `Vec3` class should work in Scala 3 match types (unlike named tuple aliases
  which don't reduce). Needs verification during implementation — if it fails,
  fall back to inline given chains with `summonInline`.

- **`NamedTuple.Map` with match type**: Verify that `NamedTuple.Map[Row, ToFN]`
  works when `ToFN` is a match type. The `Map` signature requires
  `F[_ <: Tuple.Union[...]]` which may or may not accept match types. If not,
  use `MapToFN` on the `DropNames` tuple directly and reconstruct the named
  tuple.
