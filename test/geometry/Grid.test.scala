package trivalibs.graphics.geometry

import munit.FunSuite
import trivalibs.utils.js.*

class GridTest extends FunSuite:

  def make2x2(): Grid[Int] =
    // col0: [1,2], col1: [3,4]   → get(0,0)=1 get(0,1)=2 get(1,0)=3 get(1,1)=4
    Grid.fromCols(Arr(Arr(1, 2), Arr(3, 4)))

  // ---------------------------------------------------------------------------
  // CoordOps
  // ---------------------------------------------------------------------------

  test("ClampToEdge clamps negative x"):
    val g = make2x2()
    assertEquals(g.get(-1, 0), 1)

  test("ClampToEdge clamps oversized x"):
    val g = make2x2()
    assertEquals(g.get(5, 0), 3)

  test("ClampToEdge clamps negative y"):
    val g = make2x2()
    assertEquals(g.get(0, -3), 1)

  test("ClampToEdge clamps oversized y"):
    val g = make2x2()
    assertEquals(g.get(0, 99), 2)

  test("CircleAll wraps both axes"):
    val g = Grid.fromCols[Int](
      Arr(Arr(1, 2, 3), Arr(4, 5, 6), Arr(7, 8, 9)),
      CoordOps.CircleAll,
    )
    // get(4,4) on 3×3 → (4%3=1, 4%3=1) → get(1,1) = 5
    assertEquals(g.get(4, 4), 5)

  test("CircleAll wraps negative axes"):
    val g = Grid.fromCols[Int](
      Arr(Arr(1, 2, 3), Arr(4, 5, 6), Arr(7, 8, 9)),
      CoordOps.CircleAll,
    )
    // get(-1,-1) on 3×3 → (-1+3=2, -1+3=2) → get(2,2) = 9
    assertEquals(g.get(-1, -1), 9)

  test("CircleCols wraps x, clamps y"):
    val g = Grid.fromCols[Int](Arr(Arr(1, 2), Arr(3, 4)), CoordOps.CircleCols)
    assertEquals(g.get(2, 0), 1) // x wraps: 2%2=0
    assertEquals(g.get(0, 5), 2) // y clamps to 1

  test("CircleRows wraps y, clamps x"):
    val g = Grid.fromCols[Int](Arr(Arr(1, 2), Arr(3, 4)), CoordOps.CircleRows)
    assertEquals(g.get(0, 2), 1) // y wraps: 2%2=0
    assertEquals(g.get(5, 0), 3) // x clamps to 1

  // ---------------------------------------------------------------------------
  // addCol / addRow length checks
  // ---------------------------------------------------------------------------

  test("addCol length mismatch throws"):
    val g = make2x2()
    intercept[scala.scalajs.js.JavaScriptException]:
      g.addCol(Arr(10, 20, 30))

  test("addRow length mismatch throws"):
    val g = make2x2()
    intercept[scala.scalajs.js.JavaScriptException]:
      g.addRow(Arr(10))

  test("addRow appends one element to each column"):
    val g = make2x2()
    g.addRow(Arr(5, 6))
    assertEquals(g.height, 3)
    assertEquals(g.get(0, 2), 5)
    assertEquals(g.get(1, 2), 6)

  // ---------------------------------------------------------------------------
  // Vertex neighbours
  // ---------------------------------------------------------------------------

  test("Vertex.left at left edge returns null (ClampToEdge)"):
    val g = make2x2()
    val v = g.vertex(0, 0)
    assert(v.left == null)

  test("Vertex.right at right edge returns null (ClampToEdge)"):
    val g = make2x2()
    val v = g.vertex(1, 0)
    assert(v.right == null)

  test("Vertex.top at top edge returns null (ClampToEdge)"):
    val g = make2x2()
    val v = g.vertex(0, 0)
    assert(v.top == null)

  test("Vertex.bottom at bottom edge returns null (ClampToEdge)"):
    val g = make2x2()
    val v = g.vertex(0, 1)
    assert(v.bottom == null)

  test("Vertex.right in middle returns neighbour"):
    val g = make2x2()
    val v = g.vertex(0, 0)
    val r = v.right
    assert(r != null)
    assertEquals(r.get.x, 1)
    assertEquals(r.get.value, 3)

  test("Vertex.bottom in middle returns neighbour"):
    val g = make2x2()
    val v = g.vertex(0, 0)
    val b = v.bottom
    assert(b != null)
    assertEquals(b.get.y, 1)
    assertEquals(b.get.value, 2)

  test("Vertex.left wraps with CircleAll"):
    val g =
      Grid.fromCols[Int](Arr(Arr(10, 20), Arr(30, 40)), CoordOps.CircleAll)
    val v = g.vertex(0, 0)
    val l = v.left
    assert(l != null)
    assertEquals(l.get.x, 1)
    assertEquals(l.get.value, 30)

  // ---------------------------------------------------------------------------
  // map
  // ---------------------------------------------------------------------------

  test("map preserves shape"):
    val g = make2x2()
    val g2 = g.map(v => v.value * 10)
    assertEquals(g2.width, 2)
    assertEquals(g2.height, 2)

  test("map transforms values"):
    val g = make2x2()
    val g2 = g.map(v => v.value * 2)
    assertEquals(g2.get(0, 0), 2)
    assertEquals(g2.get(1, 1), 8)

  test("map neighbour access works in f"):
    val g = make2x2()
    // For each vertex, add value of right neighbour (or 0 at right edge)
    val g2 = g.map(v => v.value + v.right.getOr(Vertex(0, 0, 0, g)).value)
    assertEquals(g2.get(0, 0), 1 + 3) // right=3
    assertEquals(g2.get(1, 0), 3 + 0) // at right edge, right=null → 0

  // ---------------------------------------------------------------------------
  // flatMapCols / flatMapRows
  // ---------------------------------------------------------------------------

  test("flatMapCols doubles each column"):
    val g = make2x2()
    // For each column of 2 vertices, produce 2 new columns: original + doubled
    val g2 = g.flatMapCols: col =>
      val doubled = Arr[Int]()
      var i = 0
      while i < col.length do
        doubled.push(col(i).value * 2)
        i += 1
      Arr(
        Arr.from(col.map(v => v.value)),
        doubled,
      )
    assertEquals(g2.width, 4)
    assertEquals(g2.height, 2)

  test("flatMapRows doubles each row"):
    val g = make2x2()
    val g2 = g.flatMapRows: row =>
      val doubled = Arr[Int]()
      var i = 0
      while i < row.length do
        doubled.push(row(i).value * 2)
        i += 1
      Arr(
        Arr.from(row.map(v => v.value)),
        doubled,
      )
    assertEquals(g2.width, 2)
    assertEquals(g2.height, 4)

  // ---------------------------------------------------------------------------
  // subdivide
  // ---------------------------------------------------------------------------

  test("subdivide(1,1) on 2×2 gives 3×3"):
    val g = Grid.fromCols[Double](
      Arr(Arr(0.0, 0.0), Arr(1.0, 1.0)),
    )
    val g2 = g.subdivide(1, 1)
    assertEquals(g2.width, 3)
    assertEquals(g2.height, 3)

  test("subdivide(1,1) interpolates midpoints"):
    // col0=[0,4], col1=[2,0] — different in both axes to exercise both directions
    val g = Grid.fromCols[Double](
      Arr(Arr(0.0, 4.0), Arr(2.0, 0.0)),
    )
    val g2 = g.subdivide(1, 1)
    assertEquals(g2.width, 3)
    assertEquals(g2.height, 3)
    // original corners preserved
    assertEqualsDouble(g2.get(0, 0), 0.0, 1e-9)
    assertEqualsDouble(g2.get(2, 0), 2.0, 1e-9)
    assertEqualsDouble(g2.get(0, 2), 4.0, 1e-9)
    assertEqualsDouble(g2.get(2, 2), 0.0, 1e-9)
    // mid column (x=1): midpoint between col0 and col1
    assertEqualsDouble(g2.get(1, 0), 1.0, 1e-9) // (0+2)/2
    assertEqualsDouble(g2.get(1, 2), 2.0, 1e-9) // (4+0)/2
    // mid row (y=1): midpoint between row0 and row1 in the subdivided grid
    assertEqualsDouble(g2.get(0, 1), 2.0, 1e-9) // (0+4)/2
    assertEqualsDouble(g2.get(2, 1), 1.0, 1e-9) // (2+0)/2
    // center point
    assertEqualsDouble(g2.get(1, 1), 1.5, 1e-9) // (1+2)/2 = 1.5

  test("subdivide(3,0) rows unchanged, cols 4x-3"):
    val g = Grid.fromCols[Double](Arr(Arr(0.0), Arr(1.0), Arr(2.0)))
    val g2 = g.subdivide(3, 0)
    assertEquals(g2.height, 1)
    // width = 3 + (3-1)*3 = 3 + 6 = 9
    assertEquals(g2.width, 9)

  test("subdivide(0,0) is identity"):
    val g = make2x2()
    val g2 = g.map(v => v.value.toDouble).subdivide(0, 0)
    assertEquals(g2.width, 2)
    assertEquals(g2.height, 2)

  // ---------------------------------------------------------------------------
  // ccwQuads
  // ---------------------------------------------------------------------------

  test("ccwQuads on 2×2 gives 1 quad with correct corners"):
    val g = make2x2()
    val qs = g.ccwQuads
    assertEquals(qs.length, 1)
    val q = qs(0)
    // Quad(tl=get(0,0), bl=get(0,1), br=get(1,1), tr=get(1,0))
    assertEquals(q(0), 1) // tl
    assertEquals(q(1), 2) // bl
    assertEquals(q(2), 4) // br
    assertEquals(q(3), 3) // tr

  test("ccwQuads count on 3×3 grid is 4"):
    val g = Grid.fromCols[Int](
      Arr(Arr(1, 2, 3), Arr(4, 5, 6), Arr(7, 8, 9)),
    )
    assertEquals(g.ccwQuads.length, 4)

  test("CircleAll seam-closing quads — count matches quadCount"):
    val g = Grid.fromCols[Int](
      Arr(Arr(1, 2), Arr(3, 4), Arr(5, 6)),
      CoordOps.CircleAll,
    )
    val (qw, qh) = g.quadCount
    assertEquals(qw, 3) // circular: same as width
    assertEquals(qh, 2) // circular: same as height
    assertEquals(g.ccwQuads.length, 6)

  // ---------------------------------------------------------------------------
  // fromRows
  // ---------------------------------------------------------------------------

  test("fromRows builds correct grid"):
    val g = Grid.fromRows[Int](Arr(Arr(1, 2), Arr(3, 4)))
    // row 0 = [1, 2] → col0[0]=1, col1[0]=2
    // row 1 = [3, 4] → col0[1]=3, col1[1]=4
    assertEquals(g.get(0, 0), 1)
    assertEquals(g.get(1, 0), 2)
    assertEquals(g.get(0, 1), 3)
    assertEquals(g.get(1, 1), 4)
