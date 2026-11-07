package trivalibs.graphics.shader.lib.line

import trivalibs.graphics.math.gpu.{*, given}

/** Cross-stroke coordinates for geometry built by `Line.toBufferedGeometry`.
  *
  * `uv.y` must **not** be interpolated directly on a stroke whose width varies:
  * a width change shears each quad, and the rasterizer fits an affine function
  * to each of its two triangles independently, so iso-`v` lines kink at every
  * diagonal — a visible zig-zag along the stroke. The value wanted is
  * `(y + h) / 2h`, which is not affine and so cannot be interpolated, but *is* a
  * ratio of two affine functions. Interpolate those two separately and divide
  * per fragment.
  *
  * Pack in the vertex stage, unpack in the fragment stage:
  *
  * ```scala sc:nocompile
  * type Varyings = (uv: Vec2, cross: Vec2)
  *
  * program.vert: ctx =>
  *   ctx.out.cross := LineCross.pack(ctx.in.uv.y, ctx.in.width)
  *
  * program.frag: ctx =>
  *   val v = ctx.in.cross.lineV            // 0..1 across the stroke
  *   val d = ctx.in.cross.lineOffset       // world units from the center line
  * ```
  *
  * Only the **cross** direction takes the divide. `uv.x` stays as it is: it is
  * arc length along the stroke, and dividing it too would make it hyperbolic
  * along a taper, stretching anything keyed on stroke progress toward the wide
  * end.
  *
  * One packed `cross` covers both uv sets. `uv` and `localUv` differ only in
  * `x` — the whole stroke against this fragment alone — and carry the identical
  * `y`, so pack once from either and pair the result with whichever along
  * coordinate is wanted. Packing `localUv.y` as well just spends a second
  * varying on the same number.
  *
  * GPU only: the cross coordinates are varyings, there is no CPU mirror.
  */
object LineCross:
  /** Packs the cross-stroke varying from `uv.y` and the stroke `width`, in the
    * vertex stage. Unpack with `cross.lineV` / `cross.lineOffset`.
    */
  inline def pack(uvY: FloatExpr, width: FloatExpr): Vec2Expr =
    vec2(uvY * width, width)

extension (cross: Vec2Expr)
  /** Normalized position across the stroke, `0` on one outline and `1` on the
    * other. Stretches as the stroke widens — the mark of one brush pressed
    * harder.
    */
  inline def lineV: FloatExpr = cross.x / cross.y

  /** Signed distance from the center line in the line's own units, `∓width/2`
    * at the outlines. Keeps its physical scale as the stroke widens — the mark
    * of a bigger brush, where a wider stroke shows more features rather than
    * stretched ones.
    *
    * Needs no divide: it is a linear combination of two affinely interpolated
    * varyings, so it is affine and exact.
    */
  inline def lineOffset: FloatExpr = cross.x - cross.y * 0.5
