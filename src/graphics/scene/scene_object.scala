package trivalibs.graphics.scene

import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.utils.js.*

// ---------------------------------------------------------------------------
// Normal matrix helper
// ---------------------------------------------------------------------------

/** Upper-left 3×3 of the model matrix, inverted and transposed. Used to
  * transform surface normals correctly under non-uniform scale.
  */
def normalMat(m: Mat4): Mat3 =
  Mat3.fromUpperLeft(m).inverse.transpose

// ---------------------------------------------------------------------------
// SceneObject type class — anything with a local Transform + optional parent.
// Hierarchies are computed lazily by walking the parent chain.
// ---------------------------------------------------------------------------

trait SceneObject[T]:
  extension (obj: T)
    def transform: Transform
    def parent: Opt[T]

  extension (obj: T)

    def worldTransform: Transform =
      val t = obj.transform
      val p = obj.parent
      if p != null then p.worldTransform * t
      else t

    def modelMat: Mat4 = obj.worldTransform.matrix

    def modelViewMat(cam: PerspectiveCamera): Mat4 = cam.viewMat * obj.modelMat
    def modelViewProjMat(cam: PerspectiveCamera): Mat4 =
      cam.viewProjMat * obj.modelMat
    def modelNormalMat: Mat3 = normalMat(obj.modelMat)
    def viewNormalMat(cam: PerspectiveCamera): Mat3 =
      normalMat(obj.modelViewMat(cam))

given SceneObject[Transform]:
  extension (t: Transform)
    inline def transform = t
    inline def parent = null
