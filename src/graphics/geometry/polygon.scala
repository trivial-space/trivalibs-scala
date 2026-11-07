package trivalibs.graphics.geometry

import trivalibs.graphics.math.*
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.Vec3
import trivalibs.graphics.math.cpu.given
import trivalibs.utils.js.*

// Winding convention for all polygons: CCW viewed from front.

/** A triangle of three vertices `T` (CCW from the front). A [[Mesh]] [[Face]].
  * Ops for any `T`: `.a`/`.b`/`.c`, `.toBufferedGeometry`; with a
  * [[Position]]: `.normal`, `.splitByPlane`. */
opaque type Triangle[T] <: Arr[T] = Arr[T]

object Triangle:
  /** A triangle from three vertices, in CCW (front-facing) order. */
  def apply[T](a: T, b: T, c: T): Triangle[T] = Arr(a, b, c)

  extension [T](tri: Triangle[T])
    def a: T = tri(0)
    def b: T = tri(1)
    def c: T = tri(2)

    /** This triangle alone as a [[BufferedGeometry]] (3 vertices, no index
      * buffer) — see [[facesToBufferedGeometry]]. Needs no [[Position]], so
      * a `Triangle[Vec2]` works for 2D drawing.
      */
    transparent inline def toBufferedGeometry: Any =
      facesToBufferedGeometry[T](Arr(tri))

  extension [T: Position](tri: Triangle[T])
    def normal: Vec3 =
      val pa = tri.a.pos
      val pb = tri.b.pos
      val pc = tri.c.pos
      val e1 = Vec3(pb.x - pa.x, pb.y - pa.y, pb.z - pa.z)
      val e2 = Vec3(pc.x - pa.x, pc.y - pa.y, pc.z - pa.z)
      e1.cross(e2).normalize

  extension [T: {Position, Lerp}](tri: Triangle[T])
    // Returns 0-2 triangles (clipped away, or split into 1 or 2 triangles).
    def splitByPlane(
        plane: Plane,
    ): Arr[Triangle[T]] =
      val out = clipPolygon(tri, 3, plane)
      fanTriangulate(out)

// Quad winding: tl(0), bl(1), br(2), tr(3) — CCW viewed from front.
/** A quad of four vertices `T`, ordered `tl, bl, br, tr` (CCW from the front).
  * A [[Mesh]] [[Face]]. Ops for any `T`: `.tl`/`.bl`/`.br`/`.tr`,
  * `.toTriangles`, `.toBufferedGeometry`; with a [[Position]]: `.normal`, and
  * (plus [[trivalibs.graphics.math.Lerp]]) `.subdivideH`/`.subdivideV`,
  * `.splitByPlane`. Build with [[Quad.apply]] or the `fromDimensions`/
  * `fromCorners` helpers. */
opaque type Quad[T] <: Arr[T] = Arr[T]

/** A corner index of a [[Quad]] (`TopLeft`/`BottomLeft`/`BottomRight`/`TopRight`). */
opaque type QuadCorner = Int

object QuadCorner:
  val TopLeft: QuadCorner = 0
  val BottomLeft: QuadCorner = 1
  val BottomRight: QuadCorner = 2
  val TopRight: QuadCorner = 3

object Quad:
  /** A quad from its four corners in CCW order: top-left, bottom-left,
    * bottom-right, top-right. */
  def apply[T](tl: T, bl: T, br: T, tr: T): Quad[T] = Arr(tl, bl, br, tr)

  /** Dimensioned construction with the in-plane orientation **inferred** from
    * world up (swapping the reference to `-Z` when the normal is near-vertical
    * and `Y` would be degenerate). Good for floor / ceiling / wall quads, where
    * nothing about the quad picks out a direction of its own.
    *
    * When it does — a ceiling beam's soffit, an atlas band that must run along
    * the beam — use the `tangent` overload instead, which this delegates to.
    *
    * `uvAtPivot`: `(0,0)` = top-left, `(0.5,0.5)` = center, `(1,1)` =
    * bottom-right.
    */
  def fromDimensions[T](
      width: Double,
      height: Double,
      normal: Vec3,
      pivot: Vec3,
      uvAtPivot: Vec2 = Vec2(0.5, 0.5),
  )(f: (Vec3, Vec2) => T): Quad[T] =
    val n = normal.normalize
    val up = if math.abs(n.y) > 0.999 then -Vec3.Z else Vec3.Y
    fromDimensions(width, height, n, up.cross(n), pivot, uvAtPivot)(f)

  /** Dimensioned construction with the in-plane orientation **given**:
    * `tangent` is the direction `u` runs, projected into the plane of `normal`.
    *
    * This is the primitive; the inferring overload is this one with
    * `tangent = up.cross(normal)`.
    *
    * Note the delegation also fixes a scaling bug the inferring form had while
    * it was written out separately: it used `up.cross(n)` unnormalized as its
    * `u` direction, and `|up × n|` is 1 only when the two are perpendicular. A
    * 45°-tilted normal therefore shrank the quad to 0.71 × its requested width
    * AND height. Every call site passed an axis-aligned normal, so it never
    * showed.
    */
  def fromDimensions[T](
      width: Double,
      height: Double,
      normal: Vec3,
      tangent: Vec3,
      pivot: Vec3,
      uvAtPivot: Vec2,
  )(f: (Vec3, Vec2) => T): Quad[T] =
    val n = normal.normalize
    // Gram-Schmidt: drop any component of `tangent` along `n`, so a caller may
    // pass a convenient direction without pre-projecting it.
    val uDir = (tangent - n * n.dot(tangent)).normalize
    val uVec = uDir * width
    val vVec = -(n.cross(uDir)) * height
    val tlPos = pivot - uVec * uvAtPivot.x - vVec * uvAtPivot.y
    val trPos = tlPos + uVec
    val blPos = tlPos + vVec
    val brPos = blPos + uVec
    Quad(
      f(tlPos, Vec2(0.0, 0.0)),
      f(blPos, Vec2(0.0, 1.0)),
      f(brPos, Vec2(1.0, 1.0)),
      f(trPos, Vec2(1.0, 0.0)),
    )

  /** [[fromDimensions]] with an explicit `tangent`, pivoted at the center. */
  inline def fromDimensionsCenter[T](
      w: Double,
      h: Double,
      n: Vec3,
      tangent: Vec3,
      center: Vec3,
  )(f: (Vec3, Vec2) => T): Quad[T] =
    fromDimensions(w, h, n, tangent, center, Vec2(0.5, 0.5))(f)

  inline def fromDimensionsCenter[T](
      w: Double,
      h: Double,
      n: Vec3,
      center: Vec3,
  )(f: (Vec3, Vec2) => T): Quad[T] =
    fromDimensions(w, h, n, center, Vec2(0.5, 0.5))(f)

  inline def fromDimensionsTopLeft[T](
      w: Double,
      h: Double,
      n: Vec3,
      tl: Vec3,
  )(f: (Vec3, Vec2) => T): Quad[T] =
    fromDimensions(w, h, n, tl, Vec2(0.0, 0.0))(f)

  // From 4 explicit corner positions.  Normal is readable via Quad.normal on the result.
  def fromCorners[T](
      tl: Vec3,
      bl: Vec3,
      br: Vec3,
      tr: Vec3,
  )(f: (Vec3, Vec2) => T): Quad[T] =
    Quad(
      f(tl, Vec2(0.0, 0.0)),
      f(bl, Vec2(0.0, 1.0)),
      f(br, Vec2(1.0, 1.0)),
      f(tr, Vec2(1.0, 0.0)),
    )

  // Infer missing 4th corner from 3 adjacent corners (parallelogram rule).
  // a, b, c are the three known corners in the order tl(0) bl(1) br(2) tr(3),
  // skipping the missing slot.
  def fromThreeCorners[T](
      a: Vec3,
      b: Vec3,
      c: Vec3,
      missing: QuadCorner,
  )(f: (Vec3, Vec2) => T): Quad[T] =
    import QuadCorner.*
    val (tl, bl, br, tr) = missing match
      case TopLeft     => (a + c - b, a, b, c) // a=bl b=br c=tr
      case BottomLeft  => (a, a + b - c, b, c) // a=tl b=br c=tr
      case BottomRight => (a, b, b + c - a, c) // a=tl b=bl c=tr
      case _           => (a, b, c, a + c - b) // TopRight: a=tl b=bl c=br
    fromCorners(tl, bl, br, tr)(f)

  extension [T](q: Quad[T])
    def tl: T = q(0)
    def bl: T = q(1)
    def br: T = q(2)
    def tr: T = q(3)

    def toTriangles: (Triangle[T], Triangle[T]) =
      (Triangle(q.tl, q.bl, q.br), Triangle(q.tl, q.br, q.tr))

    /** This quad alone as a [[BufferedGeometry]] (4 vertices, indexed as two
      * triangles) — see [[facesToBufferedGeometry]]. Needs no [[Position]],
      * so a `Quad[Vec2]` works for 2D drawing:
      * {{{
      * painter.form(geometry = quad.toBufferedGeometry)
      * }}}
      */
    transparent inline def toBufferedGeometry: Any =
      facesToBufferedGeometry[T](Arr(q))

  extension [T: Position](q: Quad[T])
    // Both-diagonals cross product handles non-planar quads.
    def normal: Vec3 =
      val a = q.tl.pos
      val b = q.bl.pos
      val c = q.br.pos
      val d = q.tr.pos
      val d1 = Vec3(c.x - a.x, c.y - a.y, c.z - a.z) // tl→br
      val d2 = Vec3(d.x - b.x, d.y - b.y, d.z - b.z) // bl→tr
      d1.cross(d2).normalize

  extension [T: {Position, Lerp}](q: Quad[T])
    // Horizontal split: bottom half and top half.
    def subdivideH: (Quad[T], Quad[T]) =
      val ml = q.tl.lerp(q.bl, 0.5)
      val mr = q.tr.lerp(q.br, 0.5)
      (Quad(ml, q.bl, q.br, mr), Quad(q.tl, ml, mr, q.tr))

    // Vertical split: left half and right half.
    def subdivideV: (Quad[T], Quad[T]) =
      val mb = q.bl.lerp(q.br, 0.5)
      val mt = q.tl.lerp(q.tr, 0.5)
      (Quad(q.tl, q.bl, mb, mt), Quad(mt, mb, q.br, q.tr))

    // Returns 0, 1 Triangle, 1 Quad, or 1 Quad + 1 Triangle (5-vert clip output).
    def splitByPlane(
        plane: Plane,
    ): Arr[Triangle[T] | Quad[T]] =
      val out = clipPolygon(q, 4, plane)
      val n = out.length
      if n == 0 then Arr()
      else if n == 3 then Arr(Triangle(out(0), out(1), out(2)))
      else if n == 4 then Arr(Quad(out(0), out(1), out(2), out(3)))
      else
        // n == 5: fan into Quad(0..3) + Triangle(0, 3, 4)
        Arr(
          Quad(out(0), out(1), out(2), out(3)),
          Triangle(out(0), out(3), out(4)),
        )

// ---------------------------------------------------------------------------
// Shared Sutherland-Hodgman clipping core
// ---------------------------------------------------------------------------

private def clipPolygon[T: {Position, Lerp}](
    verts: Arr[T],
    n: Int,
    plane: Plane,
): Arr[T] =
  val out = Arr[T]()
  var i = 0
  while i < n do
    val curr = verts(i)
    val next = verts((i + 1) % n)
    val currIn = !curr.pos.behind(plane)
    val nextIn = !next.pos.behind(plane)
    if currIn then out.push(curr)
    if currIn != nextIn then
      val dc = plane.signedDist(curr.pos)
      val dn = plane.signedDist(next.pos)
      val t = dc / (dc - dn)
      out.push(curr.lerp(next, t))
    i += 1
  out

// Fan-triangulate a convex polygon (3+ verts) — returns triangles.
private def fanTriangulate[T](out: Arr[T]): Arr[Triangle[T]] =
  val n = out.length
  if n < 3 then Arr()
  else
    val tris = Arr[Triangle[T]]()
    var i = 1
    while i < n - 1 do
      tris.push(Triangle(out(0), out(i), out(i + 1)))
      i += 1
    tris
