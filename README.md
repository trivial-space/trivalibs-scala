# trivalibs-scala

A collection of libraries for **interactive graphics on the web**, written in
Scala.js.

This is the Scala line of trivalibs and the active one for web work. Its
painter is a port of the [trivalibs-rs](https://github.com/trivial-space/trivalibs-rs)
painter with a similar API, plus the ergonomics Scala allows. It targets
Scala.js and WebGPU in the browser only; trivalibs-rs remains the
multi-target/native line. Main consumer:
[sketches-scala](https://github.com/trivial-space/sketches-scala), live at
[sketches.trivialspace.net](https://sketches.trivialspace.net).

> **Beta** — the API is converging toward stabilization. The two halves of the
> library, the **Painter** (GPU rendering) and the **Preact bindings**
> (interactive DOM), are now roughly aligned and being polished together.

## Goal

One coherent, type-safe toolkit for building interactive graphics web apps in
Scala.js: write your GPU rendering and your UI in the same language, with the
same value types flowing between them. The long-term vision is a unified CPU/GPU
programming model — Scala types and math working identically on both sides,
replacing WGSL strings with a Scala shader DSL — wrapped in a reactive DOM layer
so the rendered canvas and the UI around it are one app, not two.

## Philosophy

This is a deliberately **imperative** library. It targets JavaScript APIs
directly and avoids functional-programming patterns and the Scala standard
library to keep runtime overhead and bundle size minimal. Data structures are JS
objects, arrays, TypedArrays and `js.UndefOr`; async is plain Promises.

Scala 3 earns its place through metaprogramming and contextual abstraction: we
use it to build tailored, type-safe DSLs on top of the raw JS interop, so user
code reads like ordinary Scala while compiling down to lean native JS.

## The two halves

### Painter — typed GPU rendering

The Painter wraps WebGPU behind a typed API. Vertex attributes, varyings and
uniforms are described as **named tuples**; the field names and types drive
compile-time WGSL struct generation and WebGPU layout derivation. Shader bodies
can be raw WGSL or written in the Scala shader DSL.

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

`Form` (geometry) + `Shade` (pipeline) compose into a `Shape`; shapes group into
a `Panel`; `paint` renders, `show` presents. Bindings are typed by uniform name.
See `examples/painter_triangle`, `examples/panel_triangle`, `examples/blur`,
`examples/deferred` for the full range.

### Preact bindings — reactive interactive DOM

The Preact bindings provide a type-safe HTML DSL, signal-based reactivity
(`Var`), and a `component` macro that derives a strongly typed modifier API from
a `Props` type. This is the layer that wraps and drives the graphics canvases —
UI controls, overlays, layout — so the interactive surface around the rendered
output is built with the same language and value types.

```scala
import trivalibs.preact.html.HtmlTags.*
import trivalibs.preact.component.*
import trivalibs.preact.signals.Var

trait CounterProps extends Props:
  val initialCount: Int

val Counter = component[CounterProps]: props =>
  val count = Var(props.initialCount)
  div(
    button("onClick" := (() => count(_ + 1)), "increment"),
    span(s"count: ${count()}"),
  )

// Usage: Counter("initialCount" := 2) — modifier types are derived from CounterProps
```

`Var(initial)` creates a reactive variable; `v()` reads, `v(x)` / `v(f)` write.
Signals automatically pick global vs. component context at runtime.

## Contents

- **graphics/** — the GPU rendering stack
  - `math/` — Vec2–4, Mat2–4 (mutable / immutable / buffer representations)
  - `shader/` — `ShaderDef`, WGSL generation, the Scala shader DSL,
    `shader/lib/` function libraries (noise, color, blur, hashing, coords)
  - `buffers/` — `BufferBinding`, typed vertex attributes
  - `geometry/` — mesh, plane, grid, polygon, shape geometry
  - `scene/` — transform, camera, scene graph
  - `painter/` — the Painter abstraction + the painter-internal WebGPU facades
- **preact/** — type-safe Preact bindings: HTML DSL, signals, `component` macro
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
  (schemas, ctx, ops, `WgslFn`), and [gotchas](docs/guide/gotchas.md).
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

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file
for details.
