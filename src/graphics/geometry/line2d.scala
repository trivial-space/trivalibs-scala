package trivalibs.graphics.geometry

import trivalibs.bufferdata.F32
import trivalibs.bufferdata.StructArray
import trivalibs.bufferdata.StructRef
import trivalibs.graphics.math.*
import trivalibs.graphics.math.cpu.Vec2
import trivalibs.graphics.math.cpu.Vec2Buffer
import trivalibs.graphics.math.cpu.given
import trivalibs.utils.js.*
import trivalibs.utils.numbers.NumExt.given

// ===========================================================================
// LineVertex
// ===========================================================================

/** One vertex of a [[Line]]: a position with a stroke `width`, the length and
  * direction of the segment leading to the **next** vertex (`len` / `dir`, both
  * filled in by [[Line.addVert]] once that next vertex arrives), plus arbitrary
  * user `data`. Use `LineVertex(pos, width)` for data-less lines.
  *
  * `width` is the **full** stroke width in the line's own units — the outline
  * sits at `±width/2` from the centre line, as `lineWidth` does in SVG and
  * Canvas.
  */
class LineVertex[T](
    val pos: Vec2,
    var width: Double,
    var len: Double,
    var dir: Vec2,
    val data: T,
):
  /** A detached copy — line transformations never alias vertices between the
    * source and the result line.
    */
  def copy: LineVertex[T] = new LineVertex(pos, width, len, dir, data)

  /** Aim this vertex at `point`: sets `len` to the distance and `dir` to the
    * normalized direction towards it.
    */
  def pointTo(point: Vec2): Unit =
    val vx = point.x - pos.x
    val vy = point.y - pos.y
    val l = (vx * vx + vy * vy).sqrt
    len = l
    dir = Vec2(vx / l, vy / l)

  /** Replace a sharp corner with two bevel vertices, lerped `ratio` of the way
    * back towards `prev` and forward towards `next`. Returns a single copy of
    * this vertex when the turn is flatter than `angleThreshold` (measured as
    * `1 - dot(dir, prev.dir)`).
    */
  def smoothEdge(
      prev: LineVertex[T],
      next: LineVertex[T],
      ratio: Double,
      angleThreshold: Double,
  )(using Lerp[T]): Arr[LineVertex[T]] =
    val d = 1.0 - dir.dot(prev.dir)
    if d > angleThreshold then
      Arr(lerpVert(prev, this, 1.0 - ratio), lerpVert(this, next, ratio))
    else Arr(copy)

object LineVertex:
  def apply[T](pos: Vec2, width: Double, data: T): LineVertex[T] =
    new LineVertex(pos, width, 0.0, Vec2.zero, data)

  def apply(pos: Vec2, width: Double): LineVertex[Unit] =
    new LineVertex(pos, width, 0.0, Vec2.zero, ())

/** Interpolates position, width and data. `len` / `dir` are left at zero — they
  * are re-derived when the vertex is added to a line.
  */
private def lerpVert[T: Lerp](
    a: LineVertex[T],
    b: LineVertex[T],
    t: Double,
): LineVertex[T] =
  LineVertex(
    a.pos.lerp(b.pos, t),
    a.width + (b.width - a.width) * t,
    a.data.lerp(b.data, t),
  )

// ===========================================================================
// Line
// ===========================================================================

/** A variable-width 2D polyline. Build it by adding vertices — each `add` links
  * the previous vertex to the new one, filling in its segment length and
  * direction and accumulating [[totalLength]]. The transformation methods
  * ([[smoothEdges]], [[cleanup]], [[splitAtAngle]]) are non-mutating and return
  * fresh lines. Convert to GPU geometry with `toBufferedGeometry` (single line)
  * or `toBufferedGeometries` (an `Arr` of fragments, e.g. from
  * [[splitAtAngle]]).
  *
  * All widths — `defaultWidth` and every `add` — are **full** stroke widths in
  * the line's own units; the outline sits at `±width/2`.
  *
  * `lenOffset` is where this line starts along a longer conceptual stroke —
  * [[splitAtAngle]] threads it through the fragments so `uv.x` stays continuous
  * across the whole stroke.
  *
  * Construct via the companion: `Line(20.0)` for a data-less `Line[Unit]`,
  * `Line(20.0, 0.0, myDefaultData)` for a `Line[T]`.
  */
class Line[T](
    val defaultWidth: Double,
    val lenOffset: Double,
    val defaultData: T,
):
  /** The vertices, in order. Mutating this array directly desyncs
    * [[totalLength]] — prefer the `add*` methods.
    */
  val verts: Arr[LineVertex[T]] = Arr()

  /** Sum of all segment lengths (excluding [[lenOffset]]). */
  var totalLength: Double = 0.0

  /** The length this line will have once fully built, if it is known ahead of
    * time. `localUv.x` is normalised against it instead of [[totalLength]].
    *
    * Set it when rendering a line that is still being built — e.g. animating a
    * brush travelling along its path. Without it `localUv.x` spans 0..1 over
    * whatever has been added so far, so anything keyed on it (an end fade, a
    * texture) rescales and slides every time a vertex is appended. With it,
    * `localUv.x` only reaches the fraction actually drawn, and the geometry's
    * own end cap tapers the unfinished tip.
    */
  var plannedLength: Opt[Double] = null

  def vertCount: Int = verts.length
  def get(i: Int): LineVertex[T] = verts(i)
  def getOpt(i: Int): Opt[LineVertex[T]] =
    if i >= 0 && i < verts.length then verts(i) else null
  def first: LineVertex[T] = verts(0)
  def last: LineVertex[T] = verts(verts.length - 1)

  // --- building ---

  def add(pos: Vec2): Unit =
    addVert(LineVertex(pos, defaultWidth, defaultData))

  def add(pos: Vec2, width: Double): Unit =
    addVert(LineVertex(pos, width, defaultData))

  def add(pos: Vec2, width: Double, data: T): Unit =
    addVert(LineVertex(pos, width, data))

  /** Append `vert`, linking it to the current last vertex — that vertex gets
    * its `len` / `dir` towards `vert`, and `vert` inherits its direction.
    */
  def addVert(vert: LineVertex[T]): Unit =
    val n = verts.length
    if n > 0 then
      val prev = verts(n - 1)
      prev.pointTo(vert.pos)
      totalLength += prev.len
      vert.dir = prev.dir
    verts.push(vert)

  /** Append `vert` without re-linking — keeps the `len` / `dir` it already
    * carries. Used when re-assembling vertices whose directions are already
    * known (see [[splitAtAngle]]).
    */
  def addVertRaw(vert: LineVertex[T]): Unit =
    val n = verts.length
    if n > 0 then totalLength += verts(n - 1).len
    verts.push(vert)

  // --- transformations ---

  /** Rebuild the line, replacing each vertex by the vertices `f` returns for it
    * (`prev` / `next` are `null` at the ends). Returning an empty `Arr` drops
    * the vertex; returning several inserts them.
    */
  def flatMapWithNeighbours(
      f: (
          prev: Opt[LineVertex[T]],
          curr: LineVertex[T],
          next: Opt[LineVertex[T]],
      ) => Arr[LineVertex[T]],
  ): Line[T] =
    val line = new Line[T](defaultWidth, lenOffset, defaultData)
    val n = verts.length
    var i = 0
    while i < n do
      val prev: Opt[LineVertex[T]] = if i == 0 then null else verts(i - 1)
      val next: Opt[LineVertex[T]] = if i == n - 1 then null else verts(i + 1)
      val res = f(prev, verts(i), next)
      var j = 0
      while j < res.length do
        line.addVert(res(j))
        j += 1
      i += 1
    line

  /** Bevel sharp corners — every vertex whose turn exceeds `angleThreshold` is
    * replaced by two vertices at `ratio` along its neighbouring segments.
    * Segments shorter than `minDist` are left alone. Apply repeatedly for
    * rounder joins.
    */
  def smoothEdges(
      ratio: Double,
      minDist: Double,
      angleThreshold: Double = 0.0,
  )(using Lerp[T]): Line[T] =
    flatMapWithNeighbours: (prev, curr, next) =>
      if prev == null || next == null then Arr(curr.copy)
      else
        val p = prev
        if p.len < minDist || curr.len < minDist then Arr(curr.copy)
        else curr.smoothEdge(p, next, ratio, angleThreshold)

  /** Drop vertices that carry no shape information: those closer together than
    * `avgWidth * minLenWidRatio` (but at least `minLenFloor`), and those whose
    * width and direction barely differ from both neighbours (`widthThreshold`
    * as a relative width difference, `angleThreshold` as `1 - dot` of the
    * directions). The first and last vertex are always kept.
    *
    * `minLenWidRatio` is a fraction of the **full** stroke width, so `0.25`
    * means "drop vertices closer than a quarter of the local stroke width".
    *
    * `minLenFloor` defaults to `1.0`, i.e. "never bother below one pixel" for a
    * line measured in pixels. A line in any other unit has to say so — left at
    * the default, a line laid out in normalized units is thinned down to its
    * first and last vertex.
    */
  def cleanup(
      minLenWidRatio: Double,
      widthThreshold: Double,
      angleThreshold: Double,
      minLenFloor: Double = 1.0,
  )(using Lerp[T]): Line[T] =
    var travelled = 0.0
    flatMapWithNeighbours: (prev, curr, next) =>
      if prev == null || next == null then Arr(curr.copy)
      else
        val p = prev
        val nx = next
        val len = p.len + curr.len + travelled
        val avgWidth = (p.width + curr.width * 2.0 + nx.width) / 4.0
        val minLen = (avgWidth * minLenWidRatio).max(minLenFloor)

        if len < minLen then
          // too close to the last kept vertex — skip, but remember the
          // distance already travelled so it counts towards the next one
          travelled += p.len
          Arr[LineVertex[T]]()
        else if p.len + travelled < minLen then
          // the gap only clears minLen part way into the next segment —
          // emit an interpolated vertex exactly there
          val dist = curr.len - (len - minLen)
          travelled = -dist
          Arr(lerpVert(curr, nx, dist / curr.len))
        else
          travelled = 0.0
          val sameWidthPrev = p.width == curr.width ||
            (1.0 - p.width / curr.width).abs < widthThreshold
          val sameWidthNext = curr.width == nx.width ||
            (1.0 - nx.width / curr.width).abs < widthThreshold
          val sameDirection = 1.0 - p.dir.dot(curr.dir) < angleThreshold
          if sameWidthPrev && sameWidthNext && sameDirection then
            Arr[LineVertex[T]]()
          else Arr(curr.copy)

  /** Narrow the stroke wherever the line turns tighter than its own width can
    * carry, so the inner outline never folds back through the corner.
    *
    * A ribbon is the centre line offset by half its width. Offsetting by more
    * than the turn can accommodate makes the inner offset self-intersect: it
    * runs forward, doubles back through the corner and runs forward again,
    * covering that patch three times. This pass prevents it by construction —
    * the widths come down, so the ribbon stays a valid ribbon, and there is no
    * special case anywhere downstream.
    *
    * Two limits bind, and the tighter one wins:
    *
    *   - **an isolated corner**, where the inner offsets of the two segments
    *     meet `halfWidth * tan(turn/2)` back along each of them, so that has to
    *     fit inside the shorter neighbour;
    *   - **a run of small turns**, where no single vertex turns much but the
    *     line still comes round faster than the width allows. Over a window of
    *     arc length `s` carrying total turn `Δθ` the line achieves a radius of
    *     about `s / Δθ`, and the half width has to fit inside it.
    *
    * **Apply this after [[splitAtAngle]], to each fragment.** Before the split a
    * reversal corner is still an interior vertex turning by nearly `Pi`, so the
    * limit there collapses to nothing and takes the width with it; after it, the
    * corner is a fragment endpoint, which the split already handles.
    *
    * `factor` scales the result — below `1.0` leaves headroom, above `1.0`
    * allows a little folding back. `proximity` adds the non-local half of the
    * limit, which is off by default; see `maxHalfExtentAt` for what it costs
    * and when it is worth it. This is the `NarrowWidth` treatment: the
    * stroke visibly thins through a tight turn, and `uv.y` still spans the full
    * `0..1`, so a cross-stroke pattern stays complete and is squeezed rather
    * than clipped. Compare [[Line.narrowAtTightTurns]] against clamping the
    * inner outline instead, which keeps the width and crops the pattern.
    */
  def narrowAtTightTurns(
      factor: Double = 1.0,
      proximity: Boolean = false,
  ): Line[T] =
    val n = verts.length
    val out = new Line[T](defaultWidth, lenOffset, defaultData)
    var i = 0
    while i < n do
      val copy = verts(i).copy
      val maxWidth = maxHalfExtentAt(verts, i, proximity) * 2.0 * factor
      if maxWidth < copy.width then copy.width = maxWidth
      out.addVert(copy)
      i += 1
    out

  /** Split the line into fragments wherever it turns by more than
    * `angleThreshold` radians. The corner vertex is duplicated into both
    * fragments (ending one, starting the next), and each fragment's
    * [[lenOffset]] continues where the previous one ended — so
    * `toBufferedGeometries` can render them as one continuous stroke.
    */
  def splitAtAngle(angleThreshold: Double): Arr[Line[T]] =
    val lines = Arr[Line[T]]()
    val cosThreshold = angleThreshold.cos
    var line = new Line[T](defaultWidth, lenOffset, defaultData)
    var prev: Opt[LineVertex[T]] = null
    var offset = lenOffset
    var i = 0
    while i < verts.length do
      val v = verts(i)
      line.addVertRaw(v.copy)
      if prev != null then
        val p = prev
        if v.dir.dot(p.dir) <= cosThreshold then
          offset += line.totalLength
          // the fragment ends here, so its last vertex points backwards along
          // the incoming segment rather than around the corner
          line.last.dir = p.dir
          lines.push(line)
          line = new Line[T](defaultWidth, offset, defaultData)
          line.addVertRaw(v.copy)
      prev = v
      i += 1
    lines.push(line)
    lines

// ===========================================================================
// Line -> BufferedGeometry
// ===========================================================================

/** Vertex schema produced by `toBufferedGeometry` — use it as the shader
  * `Attribs`. `uv.x` runs `0..1` along the whole stroke (all fragments),
  * `localUv.x` along this fragment alone; `y` is `0`/`1` across the stroke and
  * `0.5` at the two end caps. `length` is the accumulated distance in the
  * line's own units.
  *
  * `width` is the **full stroke width the geometry actually produced** at that
  * rib — measured across it, after mitring and smoothing, so it reports the
  * narrowing at a bevelled corner rather than the width that was requested. Cap
  * ribs borrow their neighbour's, since their own is zero and `uv.y = 0.5`
  * already places them on the centre line.
  *
  * A width change along the stroke shears the quads, so interpolating `uv.y`
  * directly kinks at every triangle diagonal. Divide instead: pass
  * `uv.y * width` and `width` as varyings, then in the fragment stage
  * `v = num / den` (exact) and `d = num - 0.5 * den` for a signed distance from
  * the centre line in world units.
  */
type LineAttribs = (
    position: Vec2,
    width: Double,
    length: Double,
    uv: Vec2,
    localUv: Vec2,
)

/** The buffer-field tuple `LineAttribs` derives — spelled out so
  * `toBufferedGeometry` can name its return type.
  */
type LineAttribsBuffer =
  Vec2Buffer *: (F32 *: EmptyTuple) *: (F32 *: EmptyTuple) *: Vec2Buffer *:
    Vec2Buffer *: EmptyTuple

private def normalOf(dir: Vec2): Vec2 = Vec2(dir.y, -dir.x)

/** What `toBufferedGeometry` does where the line turns tighter than its width
  * can carry, i.e. where the inner outline would fold back through the corner.
  *
  *   - [[FoldTreatment.Leave]] — nothing. The fold is emitted as it falls out
  *     of the mitre, covering the corner two or three times. The default, so
  *     existing geometry is unchanged.
  *   - [[FoldTreatment.ClampInner]] — pull the inner vertex back to where the
  *     two inner offsets actually meet, keeping the requested width. The stroke
  *     stays full width and a cross-stroke pattern keeps its scale, cropped on
  *     the inside of the corner.
  *
  * The third treatment, narrowing the stroke so the fold cannot arise, is
  * [[Line.narrowAtTightTurns]] — a transformation rather than a build option,
  * since it changes the line's own data.
  */
opaque type FoldTreatment = Int
object FoldTreatment:
  val Leave: FoldTreatment = 0
  val ClampInner: FoldTreatment = 1
  extension (t: FoldTreatment) inline def id: Int = t

/** Turn angle between two travel directions, `0` straight and `Pi` a reversal. */
private def turnBetween(a: Vec2, b: Vec2): Double =
  a.dot(b).clamp(-1.0, 1.0).acos

/** Turn angle carrying its direction, negative one way and positive the other.
  *
  * The window in [[maxHalfExtentAt]] has to accumulate this rather than the
  * magnitude: an S-curve's two bends turn opposite ways, and a window spanning
  * the inflection between them must let them cancel. Summing magnitudes would
  * read the S as one long bend and narrow the stroke exactly where it is
  * straightest and nothing can fold.
  */
private def signedTurnBetween(a: Vec2, b: Vec2): Double =
  Math.atan2(cross2d(a, b), a.dot(b))

/** How far the outline can be offset at `verts(i)` before the inner side runs
  * into itself — `PositiveInfinity` where nothing constrains it.
  *
  * The single question both fold treatments ask, so that they answer it the
  * same way: [[Line.narrowAtTightTurns]] brings the width down to fit inside
  * it, `FoldTreatment.ClampInner` leaves the width alone and pulls the inner
  * vertex in to it.
  *
  * This is the line's **local feature size**: an offset curve stays free of
  * self-intersection exactly while the offset stays under the distance to the
  * medial axis, and that has a curvature part and a proximity part. Three
  * limits bind here, and the tightest wins.
  *
  * Curvature, which is local:
  *
  *   - **an isolated corner**, where the two inner offsets meet
  *     `halfExtent * tan(turn/2)` back along each segment and so have to fit
  *     inside the shorter neighbour;
  *   - **a run of small turns**, where no single vertex turns much but the line
  *     still comes round faster than the offset allows. Over a window of arc
  *     length `s` carrying total turn `Δθ` the line achieves a radius of about
  *     `s / Δθ`, and the offset has to fit inside that. The window grows
  *     outward, shorter side first, and stops once it is much wider than the
  *     limit it constrains — a window that large cannot tighten it further, and
  *     walking the whole line at every vertex would be quadratic.
  *
  * Proximity, which is not local at all, and **off unless asked for**:
  *
  *   - **another part of the line passing close by in space** while being far
  *     away along it. A narrow V that thickens along both arms is the pure
  *     case: every vertex turns gently, so no curvature test fires, yet each
  *     arm's inner edge reaches across the gap and through the other arm. The
  *     offset has to stay under half the distance to the nearest such point,
  *     half because both sides advance toward each other.
  *
  * What counts as "another part" is decided by the ratio of arc length to
  * straight-line distance — see [[proximityLimitAt]]. Without that exclusion a
  * vertex's own neighbours would constrain it and every line would collapse.
  *
  * Proximity is opt-in because it is the blunt half. The curvature limits are
  * exact and parameterless — they fall out of the geometry. Proximity needs two
  * thresholds picked by judgement, and it works by suppressing overlap, which a
  * stroke crossing itself deliberately wants to keep. It earns its place on a
  * stroke that folds close alongside itself and looks wrong for it, not by
  * default.
  *
  * A caveat before turning it on with `FoldTreatment.ClampInner`: that
  * treatment reads which side is "inner" from the turn direction, which is the
  * right question for a curvature limit and meaningless for a proximity one —
  * there the side that matters is the one facing the near part, and on a
  * near-straight run the turn direction is close to noise. Until that is fixed,
  * proximity pairs properly only with narrowing, which is symmetric and needs
  * no side.
  */
private def maxHalfExtentAt[T](
    verts: Arr[LineVertex[T]],
    i: Int,
    proximity: Boolean,
): Double =
  val curvature = curvatureLimitAt(verts, i)
  if !proximity then curvature
  else
    val near = proximityLimitAt(verts, i)
    if near < curvature then near else curvature

/** The distance to the nearest part of the line that is close in space while
  * being far away along it, halved — both sides advance toward each other, so
  * each may claim half the gap.
  *
  * "Far away along it" is the whole difficulty: every vertex has neighbours a
  * millimetre away, and counting those would collapse the line to nothing. The
  * test is the ratio of arc length to straight-line distance. Walking along a
  * straight or gently curving stretch those are nearly equal, so nothing
  * counts; where the line has come back around toward itself the arc is much
  * longer than the gap, and the ratio picks it out. It is scale-free, which
  * matters — a width-based exclusion would have to be tuned per stroke.
  *
  * The search reaches [[ProximityArcReach]] widths along the line and no
  * further — see there for why a stroke that crosses itself elsewhere must be
  * left alone.
  */
private def proximityLimitAt[T](verts: Arr[LineVertex[T]], i: Int): Double =
  val n = verts.length
  val p = verts(i).pos
  val maxArc = verts(i).width * ProximityArcReach
  var limit = Double.PositiveInfinity

  var arc = 0.0
  var j = i - 1
  while j >= 0 && arc <= maxArc do
    arc += verts(j).len
    val gap = (verts(j).pos - p).length
    if arc > gap * ProximityChordRatio && gap * 0.5 < limit then
      limit = gap * 0.5
    j -= 1

  arc = 0.0
  j = i
  while j < n - 1 && arc <= maxArc do
    arc += verts(j).len
    val gap = (verts(j + 1).pos - p).length
    if arc > gap * ProximityChordRatio && gap * 0.5 < limit then
      limit = gap * 0.5
    j += 1

  limit

/** The curvature half of the limit — how tightly the line turns at this vertex,
  * both as an isolated corner and as a run of small turns.
  */
private def curvatureLimitAt[T](verts: Arr[LineVertex[T]], i: Int): Double =
  val n = verts.length
  if i <= 0 || i >= n - 1 then Double.PositiveInfinity
  else
    val turn = turnBetween(verts(i - 1).dir, verts(i).dir)
    if turn <= MinTurn then Double.PositiveInfinity
    else
      var limit = verts(i - 1).len.min(verts(i).len) / (turn * 0.5).tan
      var lo = i - 1
      var hi = i + 1
      var arc = verts(i - 1).len + verts(i).len
      var sum = signedTurnBetween(verts(i - 1).dir, verts(i).dir)
      var searching = true
      while searching do
        val canGrowLo = lo > 0
        val canGrowHi = hi < n - 1
        if !canGrowLo && !canGrowHi then searching = false
        else
          if canGrowLo && (!canGrowHi || verts(lo - 1).len <= verts(hi).len) then
            lo -= 1
            sum += signedTurnBetween(verts(lo).dir, verts(lo + 1).dir)
            arc += verts(lo).len
          else
            hi += 1
            sum += signedTurnBetween(verts(hi - 1).dir, verts(hi).dir)
            arc += verts(hi - 1).len
          val net = sum.abs
          if net > MinTurn then
            val radius = arc / net
            if radius < limit then limit = radius
          if arc > limit * WindowReach then searching = false
      limit

/** Below this a vertex counts as straight and constrains nothing. */
private inline val MinTurn = 1e-6

/** How much longer the way along the line has to be than the way straight
  * across before a vertex counts as a different part of the stroke. `1` would
  * count every neighbour; a V with a 50° interior angle gives about `2.4`.
  */
private inline val ProximityChordRatio = 1.5

/** How far along the line, in stroke widths, the proximity test looks.
  *
  * This is what separates a stroke folding back on itself — where the two sides
  * are a few widths apart along the line and the sliver between them is an
  * artifact — from a stroke crossing itself somewhere else entirely, which is
  * pigment laid over pigment and wanted. Without the bound a wandering stroke
  * finds some other part of itself near almost everywhere and thins to nothing.
  *
  * It also keeps the scan local, so the pass stays linear rather than
  * quadratic in the vertex count.
  */
private inline val ProximityArcReach = 3.0

/** Below this a rib spans no `uv.y` range worth dividing by — the cap ribs, and
  * anything smoothing has collapsed onto them.
  */
private inline val MinUvSpan = 1e-9

/** How far past the current limit the window walk keeps going before giving up
  * — a window much wider than the width it is constraining cannot tighten it
  * further, and walking the whole line for every vertex would be quadratic.
  */
private inline val WindowReach = 4.0

/** How far `ClampInner` pulls the inner vertex in, as a fraction of the
  * geometric limit — the distance at which the two inner offsets actually meet.
  */
private inline val ClampStrength = 1.0

/** True when this vertex is a corner worth bevelling on its own contour: both
  * neighbouring segments are long enough, and the turn exceeds the threshold.
  */
private def wantsBevel[T](
    line: Line[T],
    i: Int,
    minDist: Double,
    angleThreshold: Double,
): Boolean =
  val prev = line.get(i - 1)
  val curr = line.get(i)
  if prev.len < minDist || curr.len < minDist then false
  else 1.0 - curr.dir.dot(prev.dir) > angleThreshold

/** One bevel pass over **both** contours at once, keeping them rib-paired.
  *
  * `Line.smoothEdges` run on each contour separately lets them drift apart:
  * a corner cut on one side but not the other leaves the two with different
  * vertex counts, so the strip can no longer pair them and `uv` disagrees
  * across a rib. Here the decision is made per rib index and applied to both —
  * when either side wants a bevel, both emit two vertices at the same lerp
  * ratios. On a side with no turn those land on its existing edge, so its shape
  * is unchanged and only its vertex density rises.
  */
private def smoothEdgesPaired[T: Lerp](
    top: Line[T],
    bottom: Line[T],
    ratio: Double,
    minDist: Double,
    angleThreshold: Double,
): (top: Line[T], bottom: Line[T]) =
  val outTop = new Line[T](top.defaultWidth, top.lenOffset, top.defaultData)
  val outBottom =
    new Line[T](bottom.defaultWidth, bottom.lenOffset, bottom.defaultData)
  val n = top.vertCount
  var i = 0
  while i < n do
    if i == 0 || i == n - 1 then
      outTop.addVert(top.get(i).copy)
      outBottom.addVert(bottom.get(i).copy)
    else if wantsBevel(top, i, minDist, angleThreshold) ||
      wantsBevel(bottom, i, minDist, angleThreshold)
    then
      outTop.addVert(lerpVert(top.get(i - 1), top.get(i), 1.0 - ratio))
      outTop.addVert(lerpVert(top.get(i), top.get(i + 1), ratio))
      outBottom.addVert(lerpVert(bottom.get(i - 1), bottom.get(i), 1.0 - ratio))
      outBottom.addVert(lerpVert(bottom.get(i), bottom.get(i + 1), ratio))
    else
      outTop.addVert(top.get(i).copy)
      outBottom.addVert(bottom.get(i).copy)
    i += 1
  (top = outTop, bottom = outBottom)

private def cross2d(a: Vec2, b: Vec2): Double = a.x * b.y - a.y * b.x

private def writeLineVert(
    ref: StructRef[LineAttribsBuffer],
    pos: Vec2,
    width: Double,
    length: Double,
    uvX: Double,
    uvY: Double,
    localUvX: Double,
): Unit =
  ref.set0((pos.x, pos.y))
  ref.set1(Tuple1(width.toFloat))
  ref.set2(Tuple1(length.toFloat))
  ref.set3((uvX, uvY))
  ref.set4((localUvX, uvY))

object Line:
  def apply(defaultWidth: Double): Line[Unit] =
    new Line(defaultWidth, 0.0, ())

  def apply(defaultWidth: Double, lenOffset: Double): Line[Unit] =
    new Line(defaultWidth, lenOffset, ())

  def apply[T](
      defaultWidth: Double,
      lenOffset: Double,
      defaultData: T,
  ): Line[T] = new Line(defaultWidth, lenOffset, defaultData)

  /** A `Line[Unit]` of uniform `width` through `points`. */
  def fromPoints(width: Double, points: Arr[Vec2]): Line[Unit] =
    val line = new Line(width, 0.0, ())
    var i = 0
    while i < points.length do
      line.add(points(i))
      i += 1
    line

  extension [T](line: Line[T])
    /** Expand the line into a triangle-strip quad mesh with mitre joins, ready
      * for `painter.form(geometry = …, topology =
      * PrimitiveTopology.TriangleStrip)`. The shader `Attribs` is
      * [[LineAttribs]].
      *
      * The last four parameters describe this line's place in a longer stroke;
      * `toBufferedGeometries` fills them in for you.
      *
      * @param smoothDepth
      *   how many bevel passes to run over the generated outline (0 = hard
      *   mitres)
      * @param smoothAngleThreshold
      *   minimum turn (as `1 - dot`) a corner needs before it gets bevelled
      * @param smoothMinLength
      *   outline segments shorter than this are never bevelled
      * @param totalLength
      *   stroke length `uv.x` is normalized against; defaults to this line's
      *   own length. Set it to the sum over all fragments for a continuous
      *   `uv.x`.
      * @param prevDirection
      *   direction the preceding fragment arrives with — extends the start cap
      *   so the two fragments meet without a gap
      * @param nextDirection
      *   direction the following fragment leaves with, for the end cap
      * @param swapTextureOrientation
      *   flips `uv.y`, alternated per fragment by `toBufferedGeometries`
      * @param foldTreatment
      *   what to do where a turn is too tight for the width — see
      *   [[FoldTreatment]]
      */
    def toBufferedGeometry(
        smoothDepth: Int = 0,
        smoothAngleThreshold: Double = 0.05,
        smoothMinLength: Double = 3.0,
        totalLength: Opt[Double] = null,
        prevDirection: Opt[Vec2] = null,
        nextDirection: Opt[Vec2] = null,
        swapTextureOrientation: Boolean = false,
        foldTreatment: FoldTreatment = FoldTreatment.Leave,
    ): BufferedGeometry[LineAttribsBuffer] =
      val clampInner = foldTreatment.id == FoldTreatment.ClampInner.id

      // The two outlines of the stroke. Their vertex data carries the
      // accumulated stroke length and the vertex's `uv.y`, so both survive the
      // bevel passes below. `uv.y` is a payload rather than a constant because
      // `ClampInner` moves the inner vertex inward, and where it sits across
      // the stroke is exactly what that treatment records.
      var topLine = new Line[Vec2](line.defaultWidth, 0.0, Vec2.zero)
      var bottomLine = new Line[Vec2](line.defaultWidth, 0.0, Vec2.zero)
      var lineLength = line.lenOffset

      val src = line.verts
      val n = src.length
      var i = 0
      while i < n do
        val v = src(i)
        val hasPrev = i > 0
        val hasNext = i < n - 1

        // --- mitre positions: offset along the bisector of the two segment
        // normals, capped at 5x the half-width so needle-sharp turns stay finite
        val halfWidth = v.width * 0.5
        val nextNormal = normalOf(v.dir)
        var normal = nextNormal
        var offset = halfWidth
        if hasPrev then
          val prevDir = src(i - 1).dir
          if prevDir.x != v.dir.x || prevDir.y != v.dir.y then
            val prevNormal = normalOf(prevDir)
            normal = (nextNormal + prevNormal).normalize
            offset = (halfWidth / normal.dot(prevNormal)).min(halfWidth * 5.0)

        // `ClampInner`: pull the inner vertex in to the furthest the corner can
        // carry — the same limit `narrowAtTightTurns` brings the width down to.
        // `normalOf` points right of travel, so a turn with a positive cross
        // product curves away from it and `bottom` is the inner side.
        //
        // The outer vertex keeps `uv.y = 0` / `1` — it is the stroke's edge,
        // whatever the mitre does with it — while the clamped one records where
        // it actually sits, relative to the unclamped rib so that `ribWidth`
        // divides the width back out of it. A cross-stroke pattern therefore
        // holds its scale and is cropped on the inside of the corner.
        var topOffset = offset
        var bottomOffset = offset
        var topUv = 0.0
        var bottomUv = 1.0
        // only where there is a turn to have an inside: which side is inner is
        // read from the turn direction, and a fragment's end vertices have no
        // turn at all
        if clampInner && hasPrev && hasNext then
          // curvature only: this treatment picks the inner side from the turn
          // direction, which a proximity limit cannot supply — see
          // `maxHalfExtentAt`
          val reach = maxHalfExtentAt(src, i, proximity = false) * ClampStrength
          if reach < offset then
            if cross2d(src(i - 1).dir, v.dir) > 0.0 then
              bottomOffset = reach
              bottomUv = 0.5 + reach / (offset * 2.0)
            else
              topOffset = reach
              topUv = 0.5 - reach / (offset * 2.0)

        var top = normal * topOffset + v.pos
        var bottom = normal * -bottomOffset + v.pos

        if !hasPrev then
          // start cap: a degenerate vertex on the centre line
          topLine.add(v.pos, v.width, Vec2(lineLength, 0.5))
          bottomLine.add(v.pos, v.width, Vec2(lineLength, 0.5))

          if prevDirection != null then
            // extend the cap so it meets the preceding fragment's end
            val prevDir = prevDirection
            val c = halfWidth / (prevDir * -1.0 + v.dir).normalize.dot(v.dir)
            val a = (c * c - halfWidth * halfWidth).sqrt
            if a > 0.001 then
              if cross2d(v.dir, prevDir) > 0.0 then
                top = top + v.dir * -a
                bottom = bottom + v.dir * a
              else
                top = top + v.dir * a
                bottom = bottom + v.dir * -a

        if !hasNext && nextDirection != null then
          val nextDir = nextDirection
          val c = halfWidth / (v.dir * -1.0 + nextDir).normalize.dot(nextDir)
          val a = (c * c - halfWidth * halfWidth).sqrt
          if a > 0.001 then
            if cross2d(nextDir, v.dir) > 0.0 then
              top = top + v.dir * a
              bottom = bottom + v.dir * -a
            else
              top = top + v.dir * -a
              bottom = bottom + v.dir * a

        topLine.add(top, v.width, Vec2(lineLength, topUv))
        bottomLine.add(bottom, v.width, Vec2(lineLength, bottomUv))

        if !hasNext then
          // end cap
          topLine.add(v.pos, v.width, Vec2(lineLength, 0.5))
          bottomLine.add(v.pos, v.width, Vec2(lineLength, 0.5))

        lineLength += v.len
        i += 1

      var d = 0
      while d < smoothDepth do
        val smoothed = smoothEdgesPaired(
          topLine,
          bottomLine,
          0.25,
          smoothMinLength,
          smoothAngleThreshold,
        )
        topLine = smoothed.top
        bottomLine = smoothed.bottom
        d += 1

      val uvLength = totalLength.getOr(lineLength)
      val localLength = line.plannedLength.getOr(line.totalLength)
      val ribCount = topLine.vertCount
      val vertCount = ribCount * 2
      val out = StructArray.allocate[LineAttribsBuffer](vertCount)
      val indices = Arr[Int]()

      // The width the geometry actually produced — measured across the rib
      // after mitring and smoothing, not the width that was asked for.
      //
      // Divided by the `uv.y` range the rib spans, because the outline does not
      // always sit at 0 and 1: `ClampInner` pulls the inner vertex in and
      // records where it landed, so the rib covers less than the full range.
      // Dividing recovers the width the rib would have had unclamped, which
      // keeps `d = uv.y * width - 0.5 * width` the true signed distance on both
      // sides. With no clamping the span is 1 and this is just the rib length.
      //
      // Cap ribs are degenerate — both vertices sit on the centre line at
      // `uv.y = 0.5`, so the rib has no length and no span — and borrow their
      // neighbour's. That keeps the divisor positive while `uv.y = 0.5` is what
      // places them at distance zero.
      def ribWidth(r: Int): Double =
        val i =
          if r == 0 then (ribCount - 1).min(1)
          else if r == ribCount - 1 then (ribCount - 2).max(0)
          else r
        val tv = topLine.get(i)
        val bv = bottomLine.get(i)
        val span = bv.data.y - tv.data.y
        if span < MinUvSpan then 0.0
        else (tv.pos - bv.pos).length / span

      // The two outlines are rib-paired, so the strip is just every rib in
      // order: top, bottom, top, bottom.
      var r = 0
      while r < ribCount do
        val tv = topLine.get(r)
        val bv = bottomLine.get(r)
        val width = ribWidth(r)
        val topUvY =
          if swapTextureOrientation then 1.0 - tv.data.y else tv.data.y
        val bottomUvY =
          if swapTextureOrientation then 1.0 - bv.data.y else bv.data.y

        writeLineVert(
          out(r * 2),
          tv.pos,
          width,
          tv.data.x,
          tv.data.x / uvLength,
          topUvY,
          (tv.data.x - line.lenOffset) / localLength,
        )
        writeLineVert(
          out(r * 2 + 1),
          bv.pos,
          width,
          bv.data.x,
          bv.data.x / uvLength,
          bottomUvY,
          (bv.data.x - line.lenOffset) / localLength,
        )
        indices.push(r * 2)
        indices.push(r * 2 + 1)
        r += 1

      BufferedGeometry(out, makeIndexArray(indices, vertCount))

  extension [T](lines: Arr[Line[T]])
    /** Expand stroke fragments (typically from [[Line.splitAtAngle]]) into one
      * geometry each, threading the shared stroke length and the neighbouring
      * fragments' directions through so the result reads as a single continuous
      * brush mark. Feed straight into
      * `painter.form(geometries = …, topology = PrimitiveTopology.TriangleStrip)`.
      *
      * `totalLength` overrides the length `uv.x` is normalised against, which is
      * otherwise the sum of `lines`. Pass the finished stroke's length when
      * rendering a **partial** stroke — e.g. animating a brush travelling along
      * its path — so `uv.x` keeps its final scale instead of restretching over
      * the growing prefix every frame.
      */
    def toBufferedGeometries(
        smoothDepth: Int = 0,
        smoothAngleThreshold: Double = 0.05,
        smoothMinLength: Double = 3.0,
        totalLength: Opt[Double] = null,
        foldTreatment: FoldTreatment = FoldTreatment.Leave,
    ): Arr[BufferedGeometry[LineAttribsBuffer]] =
      var total = 0.0
      if totalLength != null then total = totalLength
      else
        var i = 0
        while i < lines.length do
          total += lines(i).totalLength
          i += 1

      val out = Arr[BufferedGeometry[LineAttribsBuffer]]()
      var i = 0
      while i < lines.length do
        val prevDir: Opt[Vec2] = if i == 0 then null else lines(i - 1).last.dir
        val nextDir: Opt[Vec2] =
          if i == lines.length - 1 then null else lines(i + 1).first.dir
        out.push(
          lines(i).toBufferedGeometry(
            smoothDepth = smoothDepth,
            smoothAngleThreshold = smoothAngleThreshold,
            smoothMinLength = smoothMinLength,
            totalLength = total,
            prevDirection = prevDir,
            nextDirection = nextDir,
            swapTextureOrientation = i % 2 != 0,
            foldTreatment = foldTreatment,
          ),
        )
        i += 1
      out
