package trivalibs.graphics.geometry

import trivalibs.bufferdata.F32
import trivalibs.bufferdata.StructArray
import trivalibs.bufferdata.StructRef
import trivalibs.bufferdata.ValueTuple
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.Vec2Buffer
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.cpu.Vec3Buffer
import trivalibs.graphics.math.cpu.Vec4
import trivalibs.graphics.math.cpu.Vec4Buffer
import trivalibs.utils.js.*

import scala.NamedTuple.AnyNamedTuple
import scala.compiletime.erasedValue
import scala.compiletime.summonFrom
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint16Array
import scala.scalajs.js.typedarray.Uint32Array

// ===========================================================================
// FieldWriter — single CPU vec/mat → its buffer-tuple value
// B is the buffer schema tuple (e.g. Vec3Buffer); the runtime value tuple
// is weakly typed as Tuple and cast at the use site (matches the structural
// shape of ValueTuple[B]).
// ===========================================================================

trait FieldWriter[T, B <: Tuple]:
  def value(t: T): Tuple

object FieldWriter:
  given floatWriter: FieldWriter[Float, F32 *: EmptyTuple]:
    def value(f: Float): Tuple = f *: EmptyTuple

  given doubleWriter: FieldWriter[Double, F32 *: EmptyTuple]:
    def value(d: Double): Tuple = d *: EmptyTuple

  given vec2Writer: FieldWriter[Vec2, Vec2Buffer]:
    def value(v: Vec2): Tuple = (v.x, v.y)

  given vec3Writer: FieldWriter[Vec3, Vec3Buffer]:
    def value(v: Vec3): Tuple = (v.x, v.y, v.z)

  given vec4Writer: FieldWriter[Vec4, Vec4Buffer]:
    def value(v: Vec4): Tuple =
      (v.x, v.y, v.z, v.w)

// ===========================================================================
// VertexLayout — positional walk producing a flat buffer fields tuple F
// and a matching value tuple. Mirrors AttribLayoutHelper's structure but
// also carries the runtime value-conversion logic. Both T and F are
// regular type parameters so implicit search produces concrete F.
// ===========================================================================

trait VertexLayoutHelper[T, F <: Tuple]:
  def value(t: T): Tuple

object VertexLayoutHelper:
  given leaf: [T, B <: Tuple]
    => FieldWriter[T, B] => VertexLayoutHelper[T, B *: EmptyTuple]:
    def value(t: T): Tuple = summon[FieldWriter[T, B]].value(t) *: EmptyTuple

  given nil: VertexLayoutHelper[EmptyTuple, EmptyTuple]:
    def value(t: EmptyTuple): Tuple = EmptyTuple

  given cons: [H, HB <: Tuple, Tail <: Tuple, TF <: Tuple]
    => FieldWriter[H, HB]
    => VertexLayoutHelper[Tail, TF] => VertexLayoutHelper[H *: Tail, HB *: TF]:
    def value(t: H *: Tail): Tuple =
      summon[FieldWriter[H, HB]].value(t.head)
        *: summon[VertexLayoutHelper[Tail, TF]].value(t.tail)

trait VertexLayout[T, F <: Tuple]:
  def value(t: T): Tuple

object VertexLayout:
  // Direct: T is a leaf or positional tuple — defer to the helper.
  given direct: [T, F <: Tuple]
    => VertexLayoutHelper[T, F] => VertexLayout[T, F]:
    def value(t: T): Tuple = summon[VertexLayoutHelper[T, F]].value(t)

  // Named tuple / case class — strip names and route to the helper.
  given named: [T <: AnyNamedTuple, F <: Tuple]
    => VertexLayoutHelper[NamedTuple.DropNames[T], F] => VertexLayout[T, F]:
    def value(t: T): Tuple =
      summon[VertexLayoutHelper[NamedTuple.DropNames[T], F]]
        .value(t.asInstanceOf[NamedTuple.DropNames[T]])

// ===========================================================================
// WithNormal — append (normal: Vec3) to a named tuple shader-attribs schema.
// Buffer generation does NOT use this — it operates positionally. This is
// purely a convenience for the shader-side attribs declaration.
// ===========================================================================

/** Append `(normal: Vec3)` to a vertex-attribs named tuple — the shader-side
  * `Attribs` schema to pair with a `…WithNormal` [[MeshBufferType]]. E.g.
  * `type V = (position: Vec3, uv: Vec2)` → shade with `WithNormal[V]` =
  * `(position: Vec3, uv: Vec2, normal: Vec3)`.
  */
type WithNormal[T <: AnyNamedTuple] =
  NamedTuple.Concat[T, NamedTuple.NamedTuple[
    "normal" *: EmptyTuple,
    Vec3 *: EmptyTuple,
  ]]

// ===========================================================================
// BufferedGeometry + builders
// ===========================================================================

/** GPU-ready geometry produced by [[toBufferedGeometry]]: a vertex
  * `StructArray` plus optional index buffer. Pass straight to
  * `painter.form(geometry = …)`.
  */
class BufferedGeometry[F <: Tuple](
    val vertices: StructArray[F],
    val indices: Opt[Uint16Array | Uint32Array],
)

// ---------------------------------------------------------------------------
// MeshBufferType — phantom-typed strategy tag. The Extra type parameter
// carries the buffer fields appended on top of the user vertex layout.
// Both MeshBufferType and the Extra aliases are opaque, so user code cannot
// construct new values or new Extra shapes. The inline match below is closed
// in practice by the named vals.
// ---------------------------------------------------------------------------

/** How [[toBufferedGeometry]] turns a [[Mesh]] into vertex + index buffers.
  * The `Extra` type param tracks any extra buffer fields (e.g. a generated
  * normal) so the shader `Attribs` schema must match (use [[WithNormal]]).
  */
opaque type MeshBufferType[Extra <: Tuple] = Int

object MeshBufferType:
  opaque type NoExtra <: Tuple = EmptyTuple
  opaque type WithNormal <: Tuple = Vec3Buffer *: EmptyTuple

  /** One vertex per face-corner (faces don't share vertices); indexed by
    * triangulating each face. The common default. */
  val FaceVertices: MeshBufferType[NoExtra] = 0
  /** De-duplicated vertices shared between faces (smaller buffer, smooth
    * shading); indexed. */
  val CompactVertices: MeshBufferType[NoExtra] = 1
  /** [[FaceVertices]] plus a generated per-face (flat) normal appended to each
    * vertex — pair the shader attribs with [[WithNormal]]. */
  val FaceVerticesWithFaceNormal: MeshBufferType[WithNormal] = 2
  /** [[FaceVertices]] plus a per-vertex (smoothed) normal. */
  val FaceVerticesWithVertexNormal: MeshBufferType[WithNormal] = 3
  /** [[CompactVertices]] plus a per-vertex normal. */
  val CompactVerticesWithNormal: MeshBufferType[WithNormal] = 4

// ---------------------------------------------------------------------------
// Public entry point — transparent inline so F is concrete at every call
// site, allowing StructArray.allocate[F] to evaluate constValue[TupleSize[F]].
// Extra drives the field-tuple shape and writer choice at compile time;
// the runtime if then picks among the build* functions in that branch.
// ---------------------------------------------------------------------------

/** Convert a [[Mesh]] of `Quad`/`Triangle` faces into a [[BufferedGeometry]]
  * (typed vertex `StructArray` + index buffer) ready for
  * `painter.form(geometry = …)`. This is the idiomatic path for 3D geometry;
  * `bufferType` picks the vertex strategy and optional generated normals (see
  * [[MeshBufferType]]). The vertex type `T` must have a `Position` (any named
  * tuple with a `position: Vec3` field qualifies):
  * {{{
  * type V = (position: Vec3, uv: Vec2)
  * val mesh = Mesh(box.faces((c, uvw) => (position = c, uv = uvw.xy)))
  * val form = painter.form(geometry =
  *   toBufferedGeometry(mesh, MeshBufferType.FaceVertices))
  * }}}
  */
transparent inline def toBufferedGeometry[T: Position, Extra <: Tuple](
    mesh: Mesh[T],
    bufferType: MeshBufferType[Extra],
): Any =
  summonFrom:
    case vl: VertexLayout[T, f] =>
      inline erasedValue[Extra] match
        case _: MeshBufferType.NoExtra =>
          val write: WriteOne[T, f] =
            (v, ref) => ref.set(vl.value(v).asInstanceOf[ValueTuple[f]])
          if bufferType == MeshBufferType.FaceVertices then
            buildFaceVertices[T, f](mesh, write)
          else buildCompactVertices[T, f](mesh, write)

        case _: MeshBufferType.WithNormal =>
          type FN = Tuple.Concat[f, Vec3Buffer *: EmptyTuple]
          val writeN: WriteOneN[T, FN] = (v, n, ref) =>
            val nVal = (n.x, n.y, n.z)
            ref.set(
              (vl.value(v) ++ (nVal *: EmptyTuple))
                .asInstanceOf[ValueTuple[FN]],
            )
          if bufferType == MeshBufferType.FaceVerticesWithFaceNormal then
            buildFaceVerticesWithFaceNormal[T, FN](mesh, writeN)
          else if bufferType == MeshBufferType.FaceVerticesWithVertexNormal then
            buildFaceVerticesWithVertexNormal[T, FN](mesh, writeN)
          else buildCompactVerticesWithNormal[T, FN](mesh, writeN)

// ---------------------------------------------------------------------------
// Strategy implementations
// ---------------------------------------------------------------------------

private type WriteOne[T, F <: Tuple] = (T, StructRef[F]) => Unit
private type WriteOneN[T, F <: Tuple] = (T, Vec3, StructRef[F]) => Unit

transparent inline def buildFaceVertices[T: Position, F <: Tuple](
    mesh: Mesh[T],
    write: WriteOne[T, F],
): BufferedGeometry[F] =
  var vertexCount = 0
  var hasQuads = false
  var fi = 0
  while fi < mesh.faces.length do
    val n = mesh.faces(fi).asInstanceOf[Arr[T]].length
    vertexCount += n
    if n == 4 then hasQuads = true
    fi += 1

  val verts = StructArray.allocate[F](vertexCount)
  var vi = 0

  if !hasQuads then
    fi = 0
    while fi < mesh.faces.length do
      val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
      var si = 0
      while si < arr.length do
        write(arr(si), verts(vi))
        vi += 1
        si += 1
      fi += 1
    BufferedGeometry(verts, null)
  else
    val idxBuf = Arr[Int]()
    var base = 0
    fi = 0
    while fi < mesh.faces.length do
      val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
      val n = arr.length
      var si = 0
      while si < n do
        write(arr(si), verts(vi))
        vi += 1
        si += 1
      if n == 3 then idxBuf.push(base, base + 1, base + 2)
      else
        idxBuf.push(base, base + 1, base + 2)
        idxBuf.push(base, base + 2, base + 3)
      base += n
      fi += 1
    BufferedGeometry(verts, makeIndexArray(idxBuf, vertexCount))

transparent inline def buildFaceVerticesWithFaceNormal[T: Position, F <: Tuple](
    mesh: Mesh[T],
    write: WriteOneN[T, F],
): BufferedGeometry[F] =
  mesh.ensureFaceNormals()

  var vertexCount = 0
  var hasQuads = false
  var fi = 0
  while fi < mesh.faces.length do
    val n = mesh.faces(fi).asInstanceOf[Arr[T]].length
    vertexCount += n
    if n == 4 then hasQuads = true
    fi += 1

  val verts = StructArray.allocate[F](vertexCount)
  var vi = 0

  if !hasQuads then
    fi = 0
    while fi < mesh.faces.length do
      val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
      val normal = mesh.faceData(fi).normal.get
      var si = 0
      while si < arr.length do
        write(arr(si), normal, verts(vi))
        vi += 1
        si += 1
      fi += 1
    BufferedGeometry(verts, null)
  else
    val idxBuf = Arr[Int]()
    var base = 0
    fi = 0
    while fi < mesh.faces.length do
      val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
      val n = arr.length
      val normal = mesh.faceData(fi).normal.get
      var si = 0
      while si < n do
        write(arr(si), normal, verts(vi))
        vi += 1
        si += 1
      if n == 3 then idxBuf.push(base, base + 1, base + 2)
      else
        idxBuf.push(base, base + 1, base + 2)
        idxBuf.push(base, base + 2, base + 3)
      base += n
      fi += 1
    BufferedGeometry(verts, makeIndexArray(idxBuf, vertexCount))

transparent inline def buildFaceVerticesWithVertexNormal[
    T: Position,
    F <: Tuple,
](
    mesh: Mesh[T],
    write: WriteOneN[T, F],
): BufferedGeometry[F] =
  mesh.ensureFaceNormals()

  var vertexCount = 0
  var hasQuads = false
  var fi = 0
  while fi < mesh.faces.length do
    val n = mesh.faces(fi).asInstanceOf[Arr[T]].length
    vertexCount += n
    if n == 4 then hasQuads = true
    fi += 1

  val verts = StructArray.allocate[F](vertexCount)
  var vi = 0

  if !hasQuads then
    fi = 0
    while fi < mesh.faces.length do
      val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
      val section = mesh.faceData(fi).section
      var si = 0
      while si < arr.length do
        val v = arr(si)
        val normal = calcVertexNormal(mesh, v.pos, section)
        write(v, normal, verts(vi))
        vi += 1
        si += 1
      fi += 1
    BufferedGeometry(verts, null)
  else
    val idxBuf = Arr[Int]()
    var base = 0
    fi = 0
    while fi < mesh.faces.length do
      val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
      val n = arr.length
      val section = mesh.faceData(fi).section
      var si = 0
      while si < n do
        val v = arr(si)
        val normal = calcVertexNormal(mesh, v.pos, section)
        write(v, normal, verts(vi))
        vi += 1
        si += 1
      if n == 3 then idxBuf.push(base, base + 1, base + 2)
      else
        idxBuf.push(base, base + 1, base + 2)
        idxBuf.push(base, base + 2, base + 3)
      base += n
      fi += 1
    BufferedGeometry(verts, makeIndexArray(idxBuf, vertexCount))

transparent inline def buildCompactVertices[T: Position, F <: Tuple](
    mesh: Mesh[T],
    write: WriteOne[T, F],
): BufferedGeometry[F] =
  val vertexCount = mesh.positions.length
  val verts = StructArray.allocate[F](vertexCount)

  var pi = 0
  while pi < mesh.positions.length do
    val vp = mesh.positions(pi)
    val ref = vp.faces(0)
    val v = mesh.faces(ref.faceIndex).asInstanceOf[Arr[T]](ref.vertexSlot)
    write(v, verts(pi))
    pi += 1

  val idxBuf = Arr[Int]()
  var fi = 0
  while fi < mesh.faces.length do
    val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
    val n = arr.length
    if n == 3 then
      idxBuf.push(
        mesh.positionMap(posKey(arr(0).pos)),
        mesh.positionMap(posKey(arr(1).pos)),
        mesh.positionMap(posKey(arr(2).pos)),
      )
    else
      val i0 = mesh.positionMap(posKey(arr(0).pos))
      val i1 = mesh.positionMap(posKey(arr(1).pos))
      val i2 = mesh.positionMap(posKey(arr(2).pos))
      val i3 = mesh.positionMap(posKey(arr(3).pos))
      idxBuf.push(i0, i1, i2)
      idxBuf.push(i0, i2, i3)
    fi += 1

  BufferedGeometry(verts, makeIndexArray(idxBuf, vertexCount))

transparent inline def buildCompactVerticesWithNormal[T: Position, F <: Tuple](
    mesh: Mesh[T],
    write: WriteOneN[T, F],
): BufferedGeometry[F] =
  mesh.ensureFaceNormals()
  val vertexCount = mesh.positions.length
  val verts = StructArray.allocate[F](vertexCount)

  var pi = 0
  while pi < mesh.positions.length do
    val vp = mesh.positions(pi)
    val ref = vp.faces(0)
    val v = mesh.faces(ref.faceIndex).asInstanceOf[Arr[T]](ref.vertexSlot)
    val normal =
      calcVertexNormal(mesh, vp.position, mesh.faceData(ref.faceIndex).section)
    write(v, normal, verts(pi))
    pi += 1

  val idxBuf = Arr[Int]()
  var fi = 0
  while fi < mesh.faces.length do
    val arr = mesh.faces(fi).asInstanceOf[Arr[T]]
    val n = arr.length
    if n == 3 then
      idxBuf.push(
        mesh.positionMap(posKey(arr(0).pos)),
        mesh.positionMap(posKey(arr(1).pos)),
        mesh.positionMap(posKey(arr(2).pos)),
      )
    else
      val i0 = mesh.positionMap(posKey(arr(0).pos))
      val i1 = mesh.positionMap(posKey(arr(1).pos))
      val i2 = mesh.positionMap(posKey(arr(2).pos))
      val i3 = mesh.positionMap(posKey(arr(3).pos))
      idxBuf.push(i0, i1, i2)
      idxBuf.push(i0, i2, i3)
    fi += 1

  BufferedGeometry(verts, makeIndexArray(idxBuf, vertexCount))

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private def calcVertexNormal[T: Position](
    mesh: Mesh[T],
    p: Vec3,
    section: Int,
): Vec3 =
  import trivalibs.graphics.math.cpu.given
  var sx = 0.0
  var sy = 0.0
  var sz = 0.0
  val vp = mesh.getPosition(p)
  if vp != null then
    var i = 0
    while i < vp.faces.length do
      val ref = vp.faces(i)
      if mesh.faceData(ref.faceIndex).section == section then
        val n = mesh.faceData(ref.faceIndex).normal
        if n != null then
          sx += n.x
          sy += n.y
          sz += n.z
      i += 1
  val len = math.sqrt(sx * sx + sy * sy + sz * sz)
  if len < 1e-10 then Vec3(0, 0, 0)
  else Vec3(sx / len, sy / len, sz / len)

private def makeIndexArray(
    idxBuf: Arr[Int],
    vertexCount: Int,
): Opt[Uint16Array | Uint32Array] =
  if idxBuf.length == 0 then null
  else if vertexCount <= 65535 then
    val ua = new Uint16Array(idxBuf.length)
    var i = 0
    while i < idxBuf.length do
      ua(i) = idxBuf(i)
      i += 1
    ua
  else
    val ua = new Uint32Array(idxBuf.length)
    var i = 0
    while i < idxBuf.length do
      ua(i) = idxBuf(i)
      i += 1
    ua
