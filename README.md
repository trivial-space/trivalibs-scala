# trivalibs-scala

A collection of libraries for **interactive graphics on the web**, written in
Scala.js.

> **Beta** — the Painter API is converging toward stabilization.

## Goal

Expressive, type-safe **WebGPU** graphics, with as little boilerplate as
possible between an idea and the image. trivalibs gets there with:

- **One language for CPU and GPU.** Shaders are written in a Scala DSL, next to
  the code that drives them, and composed with ordinary Scala functions. Math
  types, values and helper libraries (noise, color, coordinates) work the same
  on both sides.
- **Typed GPU resources.** Shader inputs and bindings are declared as Scala
  types; WGSL structs and WebGPU layouts are derived from them at compile time.
- **One concept for every image.** The Panel unifies render targets, drawn
  scenes, post-processing and static textures (see below).
- **Embeddable.** The Painter renders into a browser canvas, into plain pixel
  data in a headless script, or on a GPU device the host already owns. The app
  around it stays with the host.
- **Lean output.** Everything compiles to small, direct JavaScript.

## The Painter

The Painter is a thin layer over WebGPU that replaces its many low-level objects
with a handful of entities that each describe one part of an image: what to
draw, how to shade it, and how passes build on each other. You declare these
pieces and how they connect; the painter turns them into GPU pipelines, buffers,
bind groups and render passes. Rendering stays explicit: you decide when each
image is painted and which one is shown.

### Vocabulary

The names follow a painting metaphor: a painter paints panels, and shapes are
formed and shaded. Six entities cover the whole rendering model:

| Entity      | What it is                                                                                                                          | Classic CG term                                       |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| **Painter** | Owns the GPU device and the output surface. Factory for every other entity; drives the frame with `paint` and `show`.               | renderer, GPU context + swap chain                    |
| **Form**    | Geometry: vertex buffers, optional index buffers, topology.                                                                         | mesh, vertex/index buffer                             |
| **Shade**   | A compiled shader program plus its typed schema (attributes, varyings, uniforms).                                                   | shader program, pipeline, material template           |
| **Shape**   | A Form drawn with a Shade, plus its bound uniform and texture values. Optionally instanced.                                         | renderable, draw call, mesh + material                |
| **Layer**   | A full-screen fragment pass over a panel's current image. Needs no Form.                                                            | post-processing pass, full-screen quad, image effect  |
| **Panel**   | A rendered image: its textures plus the recipe that fills them. Bindable as a texture anywhere; `show` presents one to the surface. | render target, framebuffer + render pass, and texture |

### The Panel: one concept for a rendered image

The Panel is where trivalibs departs most from other renderers. Elsewhere, a
render target, a scene of meshes drawn into it, a post-processing chain, and a
texture loaded from an image file are separate kinds of object with separate
APIs. Here they are all one Panel: an image, plus every participant that
contributes to it, stacked in a fixed order:

```
clear color → source → shapes → layers
```

1. **Clear color**: the background. Without one, the previous contents are kept.
2. **Source**: a static image (`PixelImage`, `ImageBitmap`, `<canvas>`, video
   frame, …), uploaded on `set` and redrawn at the start of every paint.
3. **Shapes**: drawn geometry, with depth testing and MSAA if enabled.
4. **Layers**: full-screen passes in order, each reading the previous result.

A panel only declares its contributors, in `painter.panel(…)` or later with
`panel.set(…)`. Nothing renders until the painter paints it: `painter.paint`
runs the stack and fills the panel's textures. Painted once, a panel is a
static, baked image; painted every frame, a live one.

Every step is optional, and the combinations cover the usual cases:

- **source only**: a plain texture, and the one case with no paint. The upload
  on `set` already is the panel's content.
- **layers only**: a procedural image or generated texture.
- **shapes, then layers**: a scene with post-processing.
- **source, then layers**: image processing that starts from the untouched image
  on every paint, so passes never compound.

Any panel can be bound as a texture input to any shape or layer. Multi-pass
rendering (bloom, deferred shading, composition) therefore needs no extra
concept: it is panels sampling panels, painted in dependency order.

### Example

```scala
import trivalibs.graphics.buffers.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.painter.*
import trivalibs.graphics.shader.dsl.{*, given}
import trivalibs.graphics.shader.{*, given}
import trivalibs.utils.animation.animate

Painter.init(canvas): painter =>
  type Attribs  = (position: Vec2, color: Vec3)
  type Varyings = (color: Vec3)
  type Uniforms = (
      tintColor:   FragmentUniform[Vec3],
      translation: VertexUniform[Vec2],
  )

  val shade = painter.shade[Attribs, Varyings, Uniforms]: program =>
    program.vert: ctx =>
      val pos = LetVec2("pos")
      Block(
        pos              := ctx.in.position + ctx.bindings.translation,
        ctx.out.position := vec4(pos, 0.0, 1.0),
        ctx.out.color    := ctx.in.color,
      )
    program.frag: ctx =>
      ctx.out.color := vec4(ctx.in.color * ctx.bindings.tintColor, 1.0)

  val vertices = allocateAttribs[Attribs](3)
  vertices(0).set0(0.0, 0.3)
  vertices(0).set1(1.0, 0.2, 0.2)
  // ...

  val form  = painter.form(vertices = vertices)
  val shape = painter.shape(form, shade).bind("translation" := Vec2(0.0, 0.55))
  val panel = painter.panel(clearColor = (0.08, 0.08, 0.12, 1.0), shapes = Arr(shape))

  animate: tpf =>
    shape.bind("tintColor" := Vec3(/* ... */))
    painter.paint(panel)
    painter.show(panel)
```

A `Form` and a `Shade` compose into a `Shape`, shapes are drawn into a `Panel`,
`paint` renders it and `show` presents it. Bindings are typed by uniform name.
For the panel stack in action, see `examples/panel_triangle`,
`examples/panel_layer`, `examples/panel_tex`, `examples/texture_source`,
`examples/source_composition`, `examples/blur`, `examples/mipmaps` and
`examples/deferred`.

### Embedding

The Painter makes no assumptions about its host. `Painter.init(canvas)` takes a
canvas element and never looks it up in the DOM. An offscreen painter renders
into plain JS pixel data (`PixelFrames`, `PixelImage`) that a host script
encodes to PNG or video. The rule is **Scala describes, the host handles I/O**;
see the [painter setup guide](docs/guide/painter-setup-guide.md) and
`examples/headless_triangle`, `examples/headless_sequence`.

The same applies to the DOM around the canvas: UI controls, overlays and layout
are best built in the host app with a JS framework, which drives the compiled
painter code. `trivalibs.preact` holds experimental type-safe Preact bindings
(HTML DSL, signals) as one way to keep that layer in Scala. It is not a current
focus.

## Why Scala

Why Scala, and not TypeScript, Rust or another language? Its metaprogramming,
contextual abstraction and elegant language features are what make the shared
CPU/GPU programming model possible, and Scala is exceptionally good at concise
DSLs over complex domains like graphics programming. The result is very
expressive code with a uniquely good signal-to-noise ratio compared to other
graphics engines, which all need far more boilerplate and context switching
between languages, files and APIs.

**How we use Scala** is deliberately unidiomatic. We skip the functional
programming patterns and the rich standard library that people usually choose
Scala for. The library is **imperative** and targets JavaScript APIs directly
through raw JS interop, to keep runtime overhead and bundle size minimal. Data
structures are JS objects, arrays, TypedArrays and `js.UndefOr`; async is plain
Promises. Scala's type system and compile-time features carry the DSLs, and user
code compiles down to lean native JS.

## Contents

- **graphics/** — the GPU rendering stack
  - `math/` — Vec2–4, Mat2–4 (mutable / immutable / buffer representations)
  - `lib/` — CPU-side helpers (noise, color, coords) mirroring `shader/lib/`
  - `shader/` — `ShaderDef`, WGSL generation, the Scala shader DSL,
    `shader/lib/` function libraries (noise, color, blur, hashing, coords)
  - `buffers/` — `BufferBinding`, typed vertex attributes
  - `geometry/` — mesh, plane, grid, polygon, shape geometry
  - `scene/` — transform, camera, scene graph
  - `painter/` — the Painter abstraction + the painter-internal WebGPU facades
- **preact/** — experimental type-safe Preact bindings: HTML DSL, signals,
  `component` macro
- **utils/** — `bufferdata.scala` (zero-cost typed binary buffers),
  `numbers.scala` (numeric extensions), `js.scala` (JS interop helpers),
  `animate.scala` (`requestAnimationFrame` lifecycle)
- **examples/** — one runnable example per feature; idiomatic-usage docs that
  ship with the library

## Usage

Include as source via git submodule:

```bash
git submodule add <repo-url> trivalibs
```

In the consumer's `project.scala`:

```scala
//> using exclude trivalibs/project.scala
//> using exclude trivalibs/test/**
//> using exclude trivalibs/examples/**
//> using dep org.scala-js::scalajs-dom::2.8.1
```

Or with explicit inclusion instead of the exclude trick:

```scala
//> using file trivalibs/src
//> using dep org.scala-js::scalajs-dom::2.8.1
```

## Development

trivalibs can be opened directly as its own workspace — `project.scala` at the
root is the single scala-cli config for lib + tests + examples.

### Prerequisites

- [scala-cli](https://scala-cli.virtuslab.org/)
- [Bun](https://bun.sh) (for the examples dev server)
- a WebGPU-capable browser

### Scripts

```bash
bun run check            # type-check the library in isolation
bun run test             # run all tests
bun run examples:build   # build all examples → examples/out/
bun run examples:watch   # incremental examples build with file watching
bun run examples:dev     # static dev server for the examples (port 5000)
bun run examples:dist    # assemble the deployable examples site → dist/
bun run deploy           # examples:dist + wrangler deploy (manual, see below)
bun run docs             # generate the Scaladoc API site → docs/api/html/
bun run publish:local    # publish a Scala artifact to ~/.ivy2/local
```

Run `examples:watch` and `examples:dev` side-by-side to iterate on the library
and its examples together.

### Deploying the examples

The live examples site is
[trivalibs-examples.trivialspace.net](https://trivalibs-examples.trivialspace.net/),
a Cloudflare Worker serving static assets (`wrangler.jsonc`). Deployment is
**manual** (`bun run deploy`) rather than CI-driven: building the examples needs
the Scala toolchain, and this library's examples change far less often than the
consuming sketch repo (which Cloudflare rebuilds automatically from Vite on
every push). Run it once after adding or changing an example.

## Documentation

Consumable docs (for _using_ the library) live under [`docs/`](docs/); internal
feature-planning docs (for _extending_ it) live under `documents/`.

- **Guides** — [`docs/guide/`](docs/guide/): the
  [sketch authoring guide](docs/guide/sketch-authoring-guide.md) (end-to-end
  flow + render model), the [shader DSL guide](docs/guide/shader-dsl-guide.md)
  (schemas, ctx, ops, `WgslFn`), the
  [painter setup guide](docs/guide/painter-setup-guide.md) (offscreen and
  headless rendering, readback, adapter selection), and
  [gotchas](docs/guide/gotchas.md).
- **API reference** — `bun run docs` generates the full Scaladoc site to
  `docs/api/html/` (gitignored; published by CI to
  [GitHub Pages](https://trivial-space.github.io/trivalibs-scala/)). Public
  doc-comments cover the painter, shader DSL, buffers, and CPU/GPU math. The
  painter entities (`Panel`/`Layer`/`Shape`/`Form`) are opaque handles —
  construct them via `painter.*` factories and configure via `set`/`bind`.
- **AI authoring skill** —
  [`docs/skills/write-sketch/`](docs/skills/write-sketch/) is a Claude Code
  skill for writing sketches against this library.

### Metals MCP (live API for editors & AI agents)

Doc-comments are surfaced three ways: IDE hover, the generated Scaladoc site,
and the **Metals MCP server** — so an AI agent can query signatures + docstrings
live instead of reading source. To enable it (these settings live in the
gitignored `.vscode/settings.json`, so each dev sets them):

```jsonc
// .vscode/settings.json
{ "metals.startMcpServer": true, "metals.mcpClient": "claude" }
```

Metals then writes `.mcp.json` (gitignored, dynamic port) exposing tools like
`get-docs`, `inspect`, `get-source`, `glob-search`. Start the Claude session
_after_ `.mcp.json` exists, then activate the server with **`/mcp`** → `metals`
→ connect (`/mcp` should then show it **connected**). To skip that step on every
session, pre-approve it once in your local Claude settings — the CLI otherwise
prompts on first run, and the VS Code extension silently skips an un-approved
server:

```jsonc
// .claude/settings.local.json  (gitignored, per machine)
{ "enabledMcpjsonServers": ["metals"] }
```

Note the **dynamic port**: each Metals restart rewrites `.mcp.json`, so sessions
started earlier lose the tools — `/mcp` → reconnect, or restart the session.

## Background

trivalibs is the toolkit of [trivial space](https://www.trivialspace.net), an
art project started in 2012 to explore the web as a presentation platform for
virtual art spaces. After a first prototype and exhibition in 2013, the focus
shifted to the tools for building such spaces by code: a live coding workflow
with hot code and shader reloading and preserved application state, carried
through a TypeScript WebGL painter, Rust libraries compiled to WebAssembly, and
a Rust WGPU painter.

This Scala library is the culmination of that work. It brings the
live-reloadable workflow and highly expressive graphics exploration together in
one typed language, from the CPU math to the GPU shaders. The research into
virtual art spaces continues here, in the same philosophy as when trivial space
started. Current work is live at
[sketches.trivialspace.net](https://sketches.trivialspace.net).

Its direct predecessor,
[trivalibs-rs](https://github.com/trivial-space/trivalibs-rs), remains an
alternative for native targets, but is currently not actively developed.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file
for details.
