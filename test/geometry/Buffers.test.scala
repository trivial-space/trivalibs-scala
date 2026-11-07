package trivalibs.graphics.geometry

import munit.FunSuite
import trivalibs.bufferdata.F32
import trivalibs.bufferdata.StructRef
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.cpu.given
import trivalibs.utils.js.*

class BuffersTest extends FunSuite:

  // Vertex layout: (x: F32, y: F32, z: F32)
  type XYZ = (F32, F32, F32)
  type XYZXYZ = (F32, F32, F32, F32, F32, F32)

  def writePos(v: Vec3, ref: StructRef[XYZ]): Unit =
    ref.setAt(0)(v.x)
    ref.setAt(1)(v.y)
    ref.setAt(2)(v.z)

  def writePosNorm(v: Vec3, n: Vec3, ref: StructRef[XYZXYZ]): Unit =
    ref.setAt(0)(v.x)
    ref.setAt(1)(v.y)
    ref.setAt(2)(v.z)
    ref.setAt(3)(n.x)
    ref.setAt(4)(n.y)
    ref.setAt(5)(n.z)

  // A simple unit-square mesh: 2 triangles sharing 4 vertices
  //   (0,1)----(1,1)
  //     |  \    |
  //     |   \   |
  //   (0,0)----(1,0)
  def squareMesh(): Mesh[Vec3] =
    val m = new Mesh[Vec3]()
    m.addFace(Triangle(Vec3(0, 0, 0), Vec3(1, 0, 0), Vec3(1, 1, 0)))
    m.addFace(Triangle(Vec3(0, 0, 0), Vec3(1, 1, 0), Vec3(0, 1, 0)))
    m

  def quadMesh(): Mesh[Vec3] =
    val m = new Mesh[Vec3]()
    m.addFace(Quad(Vec3(0, 1, 0), Vec3(0, 0, 0), Vec3(1, 0, 0), Vec3(1, 1, 0)))
    m

  // ---------------------------------------------------------------------------
  // Plain faces, no Mesh / Position (2D)
  // ---------------------------------------------------------------------------

  test("Quad[Vec2] corners need no Position"):
    val q = Quad(Vec2(0, 1), Vec2(0, 0), Vec2(1, 0), Vec2(1, 1))
    assertEquals((q.tl.y, q.bl.y, q.br.x, q.tr.x), (1.0, 0.0, 1.0, 1.0))
    val (t1, t2) = q.toTriangles
    assertEquals((t1.a.y, t1.c.x, t2.b.x, t2.c.y), (1.0, 1.0, 1.0, 1.0))

  test("Quad[Vec2].toBufferedGeometry: 4 vertices, indices 0-1-2 0-2-3"):
    val q = Quad(Vec2(0, 1), Vec2(0, 0), Vec2(1, 0), Vec2(1, 1))
    val bg = q.toBufferedGeometry
    assertEquals(bg.vertices.length, 4)
    val br = bg.vertices(2)(0)
    assertEquals((br.getAt(0).toDouble, br.getAt(1).toDouble), (1.0, 0.0))
    val indices = bg.indices
    assert(indices != null)
    val ui = indices.asInstanceOf[scala.scalajs.js.typedarray.Uint16Array]
    assertEquals(ui.toArray.toList, List(0, 1, 2, 0, 2, 3))

  test("Triangle[Vec2].toBufferedGeometry: 3 vertices, no index buffer"):
    val bg = Triangle(Vec2(0, 0), Vec2(1, 0), Vec2(0, 1)).toBufferedGeometry
    assertEquals(bg.vertices.length, 3)
    assert(bg.indices == null)

  test("facesToBufferedGeometry: many quads in one geometry"):
    val quads = Arr(
      Quad(Vec2(0, 1), Vec2(0, 0), Vec2(1, 0), Vec2(1, 1)),
      Quad(Vec2(2, 1), Vec2(2, 0), Vec2(3, 0), Vec2(3, 1)),
    )
    val bg = facesToBufferedGeometry(quads)
    assertEquals(bg.vertices.length, 8)
    val ui = bg.indices.asInstanceOf[scala.scalajs.js.typedarray.Uint16Array]
    assertEquals(ui.toArray.toList.drop(6), List(4, 5, 6, 4, 6, 7))

  // ---------------------------------------------------------------------------
  // FaceVertices
  // ---------------------------------------------------------------------------

  test("FaceVertices: pure triangles — no index buffer"):
    val m = squareMesh()
    val bg = buildFaceVertices(m, writePos)
    assertEquals(bg.vertices.length, 6) // 2 triangles × 3 verts
    assert(bg.indices == null)

  test("FaceVertices: quad mesh — emits index buffer"):
    val m = quadMesh()
    val bg = buildFaceVertices(m, writePos)
    assertEquals(bg.vertices.length, 4) // 1 quad = 4 verts
    assert(bg.indices != null)

  test("FaceVertices: quad index buffer has 6 entries (2 triangles)"):
    val m = quadMesh()
    val bg = buildFaceVertices(m, writePos)
    val indices = bg.indices
    assert(indices != null)
    val ui = indices.asInstanceOf[scala.scalajs.js.typedarray.Uint16Array]
    assertEquals(ui.length, 6)

  test("FaceVertices: mixed tri+quad index buffer"):
    val m = new Mesh[Vec3]()
    m.addFace(Triangle(Vec3(0, 0, 0), Vec3(1, 0, 0), Vec3(0, 1, 0)))
    m.addFace(Quad(Vec3(0, 1, 1), Vec3(0, 0, 1), Vec3(1, 0, 1), Vec3(1, 1, 1)))
    val bg = buildFaceVertices(m, writePos)
    assertEquals(bg.vertices.length, 7) // 3 + 4
    val indices = bg.indices
    assert(indices != null)
    val ui = indices.asInstanceOf[scala.scalajs.js.typedarray.Uint16Array]
    assertEquals(ui.length, 9) // 3 + 6

  // ---------------------------------------------------------------------------
  // FaceVerticesWithFaceNormal
  // ---------------------------------------------------------------------------

  test("FaceVerticesWithFaceNormal: vertex count"):
    val m = squareMesh()
    val bg = buildFaceVerticesWithFaceNormal(m, writePosNorm)
    assertEquals(bg.vertices.length, 6)

  test("FaceVerticesWithFaceNormal: normals point +Z"):
    val m = squareMesh()
    val bg = buildFaceVerticesWithFaceNormal(m, writePosNorm)
    // Normal z component is at slot index 5
    assertEqualsDouble(bg.vertices(0).getAt(5): Double, 1.0, 1e-5)

  // ---------------------------------------------------------------------------
  // CompactVertices
  // ---------------------------------------------------------------------------

  test("CompactVertices: deduplicates shared positions"):
    val m = squareMesh()
    val bg = buildCompactVertices(m, writePos)
    assertEquals(bg.vertices.length, 4) // 4 unique corners

  test("CompactVertices: always has index buffer"):
    val m = squareMesh()
    val bg = buildCompactVertices(m, writePos)
    assert(bg.indices != null)

  test("CompactVertices: 2 triangles → 6 indices"):
    val m = squareMesh()
    val bg = buildCompactVertices(m, writePos)
    val indices = bg.indices
    assert(indices != null)
    val ui = indices.asInstanceOf[scala.scalajs.js.typedarray.Uint16Array]
    assertEquals(ui.length, 6)

  test("CompactVertices: quad → 4 vertices, 6 indices"):
    val m = quadMesh()
    val bg = buildCompactVertices(m, writePos)
    assertEquals(bg.vertices.length, 4)
    val indices = bg.indices
    assert(indices != null)
    val ui = indices.asInstanceOf[scala.scalajs.js.typedarray.Uint16Array]
    assertEquals(ui.length, 6)

  // ---------------------------------------------------------------------------
  // toBufferedGeometry dispatch
  // ---------------------------------------------------------------------------

  test("toBufferedGeometry dispatches to FaceVertices"):
    val m = squareMesh()
    val bg = toBufferedGeometry(m, MeshBufferType.FaceVertices)
    assertEquals(bg.vertices.length, 6)

  test("toBufferedGeometry dispatches to CompactVertices"):
    val m = squareMesh()
    val bg = toBufferedGeometry(m, MeshBufferType.CompactVertices)
    assertEquals(bg.vertices.length, 4)
