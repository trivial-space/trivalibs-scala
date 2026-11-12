---
name: write-sketch
description:
  Author a trivalibs painter WebGPU sketch (Scala.js) — the
  shade→form→shape→panel→paint flow, the shader DSL, bindings, and the gotchas.
  Use when creating or editing a sketch under sketches/ or a trivalibs example.
---

# Writing a trivalibs sketch

A sketch is a self-contained WebGPU canvas program built on the trivalibs
painter. This skill is the procedure + the traps. For depth, read the guides and
query the live API — don't read library source by default.

## Reference sources (in order)

1. **Guides** (consumable docs):
   `trivalibs/docs/guide/sketch-authoring-guide.md` (the spine),
   `shader-dsl-guide.md` (DSL + op catalog), `gotchas.md` (traps).
2. **Live API via Metals MCP** — for exact signatures/docstrings of any symbol:
   `get-docs` / `inspect <fqcn>` (e.g. `trivalibs.graphics.painter.Painter`),
   `glob-search` to find a name, `get-source` to read a file with bodies
   stripped. Prefer this over grepping source.
3. **Canonical code**: `trivalibs/examples/*` and existing `sketches/*`
   (`base-triangle` is the starter template; `post/bloom` shows multi-pass +
   mips; `rooms/base` shows 3D mesh + camera).

## Procedure

1. **Scaffold**: `cp -r sketches/base-triangle sketches/<category>/<name>`; set
   `package sketches.<category>.<name>` and rename the `@main def`.
2. **Write the shader(s)** with the DSL (`p.shade[A,V,U]` for geometry,
   `p.layerShade[U]` for full-screen passes). Schemas are named tuples; field
   order = layout index, field name = WGSL name.
3. **Geometry**: for 3D, the default path is a **`Mesh`** of `Quad`/`Triangle`
   faces → `p.form(geometry = toBufferedGeometry(mesh, MeshBufferType.…))`
   (builders: `Box`, `sphereMesh`, `Grid` → `Mesh(grid.ccwQuads)`;
   `…WithFaceNormal`/`…WithVertexNormal` generate normals). Use
   `allocateAttribs[A](n)` + `p.form(vertices = …)` only for simple one-off
   primitives. Then `p.binding[T]` / `p.binding(init)` for uniforms.
4. **Compose**: `p.shape(form, shade).bind("name" := value, …)` and/or
   `p.layer(shade).bind(...)`, then
   `p.panel(shape = …, layer = …, clearColor = …)`.
5. **Loop**: `p.onResize((w,h) => …)` for aspect/resolution;
   `animate(tpf => { updates; p.paint(panels…); p.show(panel) })`.
6. **Build**: `bun run sketch <category>/<name>` (from repo root). Add an entry
   to `sketches/index.html`.

## Minimal template (full-screen layer)

```scala
package sketches.demo.gradient

import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.document
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.math.gpu.{*, given}
import trivalibs.graphics.painter.*
import trivalibs.graphics.shader.dsl.{*, given}
import trivalibs.graphics.shader.{*, given}
import trivalibs.utils.animation.animate

@main def gradient(): Unit =
  val canvas = document.getElementById("canvas").asInstanceOf[HTMLCanvasElement]
  Painter.init(canvas): p =>
    type U = (time: Float)
    val shade = p.layerShade[U]: program =>
      program.frag: ctx =>
        ctx.out.color := vec4(ctx.in.uv, (ctx.bindings.time.sin * 0.5 + 0.5), 1.0)
    val time  = p.binding(0.0f)
    val panel = p.panel(layer = p.layer(shade).bind("time" := time))
    var t = 0.0
    animate: tpf =>
      t += tpf * 0.001
      time.set(t.toFloat)
      p.paint(panel)
      p.show(panel)
```

## Key API (idiomatic surface)

Construct via the painter; the entities are opaque handles whose only public
mutators are `set(...)` / `bind(...)` / `instances`:

- `p.shade[A,V,U]{…}` / `p.shade[A,V,U,P]{…}` / `p.layerShade[U]{…}` — shaders
- `p.form(vertices=…)` / `p.form(geometry=…)` — geometry
- `p.shape(form, shade).bind("n" := v)` — drawable; `shape.instances.add(...)`
- `p.layer(shade, blendState=, mipSource=, mipTarget=).bind(...)` — full-screen
  pass
- `p.panel(shape=/shapes=, layer=/layers=, clearColor=, format=, mips=, depthTest=, multisample=)`
- `p.binding[T]` / `p.binding(init)`; update `b.set(v)` / `b := v`
- `p.samplerLinear` / `p.samplerNearest` / `p.sampler(...)`
- `p.paint(panels*)` then `p.show(panel)` (or `p.paintAndShow`);
  `p.draw(shape, clearColor)`
- `panel.binding(index=, mipLevel=, depth=)` to bind one panel texture view
- `panel.freeze()` after the `paint` of a one-shot panel — frees MSAA, pong and
  unsampled depth; painting it again throws

## Gotchas (see gotchas.md for the full list)

- **Double on the left of an operator doesn't convert**: write `expr * 0.5`, not
  `0.5 * expr`; for a leading constant use `(0.5: FloatExpr) * expr`. Put the
  expression first inside `vec2(...)` args too.
- **`tpf` is milliseconds** (Rust used seconds) — scale (`t += tpf * 0.001`).
- **`onResize` runs immediately** and on every resize — init aspect/resolution
  uniforms there.
- **HDR needs a float format**: `format = TextureFormat.Rgba16Float` (default
  canvas format clamps > 1.0). Formats are typed `TextureFormat` constants, not
  strings.
- **Mip chains**: `mips = true` auto-generates; to hand-build a pyramid use
  mip-target layers (`mipSource`/`mipTarget`) — the painter then skips
  auto-generation. A `Layer`'s first texture slot auto-reads the previous pass
  unless you bind it to an external panel.
- **`paint` order matters**: list panels so a sampler comes after its source;
  nothing shows until `show`.
- **Prefer trivalibs helpers** (`Arr`, `Vec3`, NumExt `.sin`/`.sqrt`) over Scala
  stdlib / `math.*`. One statement per line; no semicolons.

## Conventions

Sketch code may use Scala conveniences (for-comprehensions, string
interpolation) — readability wins; the bundle-size discipline applies to library
code, not sketches. Mirror the package to the path. When new shader code is
needed, prefer the DSL over raw WGSL.
