package trivalibs.graphics.painter

import trivalibs.graphics.painter.*
import trivalibs.utils.js.*

import scala.annotation.publicInBinary
import scala.scalajs.js

/** A compiled shader — the WGSL module plus its vertex/bind-group/pipeline
  * layouts. Opaque handle in sketch code: create via [[Painter.shade]] /
  * [[Painter.layerShade]] and pass to [[Painter.shape]] / [[Painter.layer]];
  * its fields drive the render pipeline internally and aren't part of the
  * sketch API. (`uniformIndices`/`panelIndices` are public only because the
  * inline `bind` machinery reads them.)
  */
class Shade[U, P] @publicInBinary private[painter] (
    private[painter] val id: Int,
    private[painter] val shaderModule: GPUShaderModule,
    private[painter] val vertexBufferLayout: Opt[js.Dynamic],
    private[painter] val valueBindGroupLayout: Opt[GPUBindGroupLayout],
    private[painter] val panelBindGroupLayout: Opt[GPUBindGroupLayout],
    private[painter] val pipelineLayout: GPUPipelineLayout,
    private[painter] val isLayer: Boolean,
    val uniformIndices: Dict[Int],
    val panelIndices: Dict[Int],
):
  /** Slot index → uniform field name; inverse of [[uniformIndices]]. */
  private[painter] val uniformNames: Arr[String] = slotNames(uniformIndices)

  /** Slot index → panel field name; inverse of [[panelIndices]]. */
  private[painter] val panelNames: Arr[String] = slotNames(panelIndices)

  private def slotNames(indices: Dict[Int]): Arr[String] =
    val keys =
      js.Object.keys(indices.asInstanceOf[js.Object]).asInstanceOf[Arr[String]]
    val names = Arr[String]()
    var i = 0
    while i < keys.length do
      val idx = indices.at(keys(i))
      while names.length <= idx do names.push("")
      names(idx) = keys(i)
      i += 1
    names
