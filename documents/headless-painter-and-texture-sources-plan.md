# Headless painter, render-to-file, and texture sources

**Status: Milestone 1 (M1.0–M1.4) done: implemented, verified (including the
browser check of all examples by hand), and its code reviewed in PR #3 with all
review comments addressed (§9, 2026-10-26). Milestone 2: M2.0a, M2.0, M2.0b and
M2.1 implemented and headless-tested, the browser check of the new examples by
hand pending; M2.2 dropped (§6). §1–3 (Milestone 1) reviewed and approved; §4
onward (Milestone 2) not yet reviewed.** Scope is **offline render to file**,
not on-screen rendering outside the browser. Takes over the host seam and the
headless phase of [non-browser-hosts-plan.md](non-browser-hosts-plan.md) (its
`RenderHost` becomes `Surface` here); native-window hosts (Deno windows,
NativeScript) stay a separate issue in that document, and §3.11 lists what this
API must keep compatible with them. Also absorbs the
`with_static_texture_data(bytes)` gap from
[independent-todos.md](independent-todos.md).

Two milestones:

1. **Headless painter + render to file.** The painter runs without a DOM, on any
   runtime that hands it a spec-conformant `GPU` object. Verification targets
   node-webgpu only, on Node (and on Bun, if Bun can load it; to verify).
   Instead of a canvas it is given a plain data container: a `PixelFrames`,
   where every `show()` appends one frame, or a `PixelImage`, where every
   `show()` overwrites the single image. Panels can be read back into the same
   containers — the path for offline-baked static assets that replace today's
   init-time bakes (`src/utils/bake/Bake.scala` in the consuming repo). Encoding
   and file writing happen afterwards, in whatever the host prefers.
2. **Texture sources on panels.** A panel can take uploaded pixels as one more
   contributor to its image, a background beneath its shapes and layers, or the
   whole image on its own. The sources are raw buffers, `ImageData`,
   `HTMLImageElement`, `ImageBitmap` and canvases. Through the same interface
   this extends to video: `HTMLVideoElement` and `VideoFrame` in the browser,
   and decoded frames headless.

The design rule running through both: **Scala describes, the host handles I/O.**
Scala's value is expressiveness in the graphics domain — scenes, math,
animation, shaders in custom DSLs. File handling, codecs and process plumbing
are ordinary host workflows, best written in the host's own language (a short TS
script on Node or Bun). So the boundary between the two is **plain JS data**:
objects of width, height, format and typed arrays, which either side can create
and read without importing anything from the other. Output is a `PixelFrames`
sequence or a `PixelImage` still. Input is a `PixelImage` (§3.5), plus the
browser's native image types as an input-only fast path where they exist.

---

## 1. Research

### 1.1 Runtimes

| Runtime          | How you get a `GPU`                                                   | Backend        | Canvas / surface                                                                                                                     | Image APIs                                                                                                          | Status                                                                                                   |
| ---------------- | --------------------------------------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| **Node**         | `import { create } from 'webgpu'` → `create([...options])`            | Dawn           | none                                                                                                                                 | none (no `ImageBitmap`, no `copyExternalImageToTexture` sources)                                                    | npm `webgpu` 0.6.x, Node ≥ 18                                                                            |
| **Deno**         | `navigator.gpu` (with `--unstable-webgpu` / `"unstable": ["webgpu"]`) | wgpu           | `Deno.UnsafeWindowSurface` (`getContext("webgpu")`, explicit `present()`, `resize()`); desktop windows from Deno 2.9 `BrowserWindow` | `createImageBitmap(Blob \| ImageData \| ImageBitmap)`; formats + `copyExternalImageToTexture` support **to verify** | unstable flag. **Runtime discontinued** ([announcement](https://deno.com/blog/cloudflare)); not a target |
| **Bun (future)** | `navigator.gpu` + `GPU*` globals, no flag                             | wgpu-core 30   | **none** ("there is no canvas")                                                                                                      | `GPUExternalTexture` / `copyExternalImageToTexture` explicitly out of scope                                         | PR [oven-sh/bun#42768](https://github.com/oven-sh/bun/pull/42768) open, being split into a stack         |
| **Bun (today)**  | `bun-webgpu` (Dawn via FFI) or `wgpu-polyfill` (wgpu-native)          | Dawn / wgpu    | none                                                                                                                                 | `Bun.Image` decodes/encodes, but takes **encoded** input only (raw RGBA in/out is an open PR)                       | third-party                                                                                              |
| **NativeScript** | `navigator.gpu`                                                       | Metal / Vulkan | `canvas.getContext("webgpu")` — browser-shaped                                                                                       | asset images, to verify                                                                                             | `@nativescript/canvas` 2.0 webgpu prerelease                                                             |

Takeaways:

- **The common denominator is exactly: a `GPU` object, no canvas, no image
  types.** Node and future-Bun don't have a canvas at all; Deno has one only
  through a native window. A headless design that needs nothing else covers all
  three, and needs no change when Bun's PR lands.
- **Getting the `GPU` is the only runtime-specific step**, and it is one line.
  It belongs to user land — a per-runtime driver script, or Scala entry code if
  the user prefers — never to the library. The extended painter setup takes the
  `GPU` (or a device) as a **required argument**; library code does not read
  `navigator.gpu`, does not shim `navigator`, and does not install WebGPU
  globals. The only exception is the existing browser shortcut
  `Painter.init(canvas)`, which keeps its `navigator.gpu` lookup unchanged.
- **node-webgpu's own example is the reference workflow**
  ([example.js](https://github.com/dawn-gpu/node-webgpu/blob/main/example/example.js)):
  render to an `rgba8unorm` texture with `RENDER_ATTACHMENT | COPY_SRC`,
  `copyTextureToBuffer` into a `MAP_READ` buffer with `bytesPerRow` aligned to
  256, `mapAsync(READ)`, strip the row padding, encode PNG (`pngjs`). Deno's
  `@std/webgpu` `createCapture` + `getRowPadding` is the same pattern. The
  painter's capture surface is this, packaged.
- ~~node-webgpu keeps the process alive while the `GPU` object returned by
  `create()` is referenced.~~ **Disproved in M1.3:** the process exits on its
  own on Node and Bun, with the `GPU`, painter and device still referenced, and
  `process.exit(0)` right after the last readback is clean too (§9). The
  opposite holds instead: the `GPU` must stay referenced while its device works,
  or node-webgpu crashes — so the painter retains it. The OS and driver reclaim
  all GPU memory at process end. No library teardown needed for this milestone;
  explicit freeing (`Painter.destroy()` and per-resource `destroy()`) is
  deferred to long-running hosts, see
  [independent-todos.md](independent-todos.md) "GPU resources".
- **Verification runs on node-webgpu only.** Deno's runtime is being
  discontinued ([announcement](https://deno.com/blog/cloudflare)). Even if a
  foundation continues it, its experimental native WebGPU and window APIs may
  not survive, so this plan no longer bets on it. Bun's native WebGPU is still
  an open PR. node-webgpu is a Node-API addon: on Node it is the reference, and
  on Bun it may load too (to verify, §7). Otherwise drivers run on plain Node.
  The design itself stays runtime-agnostic: the `GPU` object is passed in.
- **Deno windows and NativeScript are on-screen hosts** — a real surface with
  `getContext("webgpu")`, closer to the browser than to headless. Out of scope
  here; tracked in [non-browser-hosts-plan.md](non-browser-hosts-plan.md). The
  rows above are kept so the `Surface` shape is checked against them (§3.11).

### 1.2 Adapter selection

WebGPU cannot enumerate adapters. What it offers is a request with hints and a
description of what you got:

- **Spec, portable:**
  `requestAdapter({ powerPreference: "high-performance" | "low-power", forceFallbackAdapter, featureLevel })`,
  then `adapter.info` (`GPUAdapterInfo`: `vendor`, `architecture`, `device`,
  `description`). `forceFallbackAdapter: true` asks for the software adapter
  (SwiftShader in Dawn).
- **Dawn / node-webgpu, at `create()` time** (README, "dawn node specific
  options"): `backend=vulkan|opengl|opengles|null|…` and `adapter=<name>`. A
  non-existent name fails with `no suitable backends found` and prints
  `Available adapters: * backend: 'vulkan', name: '…'` — the de facto way to
  enumerate; that name is then passed back verbatim. Same for an invalid
  `backend`. The README also points at plain
  `requestAdapter({ powerPreference })` as the normal, portable way.
- **Deno (wgpu), process env:** `DENO_WEBGPU_BACKEND=vulkan|gl|…` (comma list,
  read in `ext/webgpu/lib.rs` `get_or_init_instance`). No adapter-name option;
  combine with the Vulkan-level selectors below.
- **Bun:** wgpu-core underneath; whether it exposes a backend env var is **to
  verify** once the PR lands (`WGPU_BACKEND` is wgpu's own convention).
- **Linux, below WebGPU — works for every runtime on the Vulkan backend:**
  - `VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json` (or `radeon_icd…`,
    `lvp_icd…` for lavapipe) restricts the Vulkan loader to one ICD.
    (`VK_ICD_FILENAMES` is the older name.)
  - `MESA_VK_DEVICE_SELECT=list` prints devices; `MESA_VK_DEVICE_SELECT=vid:did`
    picks one (Mesa device-select layer).
  - GL backends: `__GLX_VENDOR_LIBRARY_NAME=nvidia`,
    `__NV_PRIME_RENDER_OFFLOAD=1` for PRIME offload.

All runtime-level selection happens **before** the `GPU` object exists, so it
cannot be a painter parameter. The painter's part is: accept an explicit `GPU`
or `GPUAdapter`, pass the spec hints, **verify** what it got against a
requirement, and log `adapter.info` so a render never silently runs on lavapipe
when NVIDIA was intended. See §3.3.

### 1.3 Images and video as WebGPU textures (browser)

Three upload paths exist:

| Path                                               | Sources                                                                                                                  | Result                                            | Notes                                                                                                                                                                                                                                                                                                                                                                      |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `queue.writeTexture(dst, data, layout, size)`      | any `ArrayBuffer` / typed array                                                                                          | regular `GPUTexture`                              | universal, every runtime. `bytesPerRow` has no 256 alignment requirement here (only buffer copies do).                                                                                                                                                                                                                                                                     |
| `queue.copyExternalImageToTexture(src, dst, size)` | `ImageBitmap`, `ImageData`, `HTMLImageElement`, `HTMLCanvasElement`, `OffscreenCanvas`, `HTMLVideoElement`, `VideoFrame` | regular `GPUTexture`                              | dst usage must include `COPY_DST \| RENDER_ATTACHMENT`; dst format from a fixed list (`rgba8unorm[-srgb]`, `bgra8unorm[-srgb]`, `rgba16float`, `rgba32float`, `r8/rg8unorm`, `r/rg 16/32float`, `rgb10a2unorm`). Options `flipY`, `premultipliedAlpha`, `colorSpace`. Firefox: `VideoFrame` reportedly unsupported here — wrap in `ImageBitmap`.                           |
| `device.importExternalTexture({ source })`         | `HTMLVideoElement`, `VideoFrame`                                                                                         | `GPUExternalTexture` (`texture_external` in WGSL) | zero-copy (YUV sampled in hardware). Expires: from a video element at the end of the task that used it; from a `VideoFrame` when the frame is closed → bind groups rebuilt every frame. Sample with `textureSampleBaseClampToEdge` / `textureLoad` only, no mips. Not Baseline: listed missing in Firefox 141; Safari status unclear. Not in Bun's PR, not in node-webgpu. |

Video on the web today is therefore done one of two ways: **copy per frame**
(`copyExternalImageToTexture` from the video element or a WebCodecs `VideoFrame`
into an ordinary texture — portable, mips possible, sampled like any texture) or
**external texture** (zero-copy, Chromium-best, a separate binding type with
per-frame bind groups). New-frame pacing is `requestVideoFrameCallback` on
`HTMLVideoElement`, or the decoder output callback for WebCodecs.

Decoding encoded bytes (PNG/JPEG) is never WebGPU's job: browser and Deno use
`createImageBitmap(blob, { colorSpaceConversion: "none", premultiplyAlpha: "none" })`
— the two options matter for **data** textures (baked noise, LUTs), which must
not be color-managed or premultiplied. Node/Bun decode to raw RGBA in JS (see
§4.3).

### 1.4 Encoding output — host-side tooling

Encoding is the host's job (design rule above). What the host has available:

- **PNG:** `pngjs` (pure JS, the node-webgpu example's choice) or `sharp`
  (`sharp(data, { raw: { width, height, channels: 4 } }).png()`, also 16-bit).
  Both run on Node and Bun. `Bun.Image` only accepts encoded input today (raw
  RGBA in/out is an open PR), so it can't take readback pixels directly yet.
- **Float16 / mip-chain assets:** **KTX2** (uncompressed) is the standard GPU
  texture container — any `GPUTextureFormat`, mip levels. `ktx-parse` (npm)
  reads and writes it in every runtime and in the browser. Raw `.bin` + known
  dimensions is the zero-dependency fallback.
- **Video / GIF:** `ffmpeg` as a subprocess, raw frames on stdin
  (`-f rawvideo -pix_fmt rgba -s WxH -r FPS -i -`). For 16-bit output use
  `rgba64le` (integer, so the host quantizes float frames first), or
  `gbrapf32le` for float input. Spawned via `node:child_process` (Node, Bun) or
  `Bun.spawn`. GIF via `split[a][b];[a]palettegen[p];[b][p]paletteuse` in one
  pass.
- A Scala-side codec layer (a small PNG encoder over `CompressionStream`, which
  every runtime has) stays possible later; not planned.

---

## 2. What is browser-coupled today

Verified against the current sources:

| Where                               | Assumption                                                                   |
| ----------------------------------- | ---------------------------------------------------------------------------- |
| `painter/webgpu.scala` `getGPU`     | `dom.window.navigator.gpu` — stays, but only `Painter.init(canvas)` calls it |
| `painter/webgpu.scala` `getContext` | `canvas.getContext("webgpu")`                                                |
| `Painter.init`                      | `gpu.getPreferredCanvasFormat()`, `clientWidth/Height`, `ResizeObserver`     |
| `Painter` fields                    | `canvas`, `context` as constructor vals; `width/height` read `canvas`        |
| `paintPanel`                        | `canvas.width/height` as the panel size fallback                             |
| `show`, `draw`                      | `context.getCurrentTexture()`, no explicit present                           |
| `Painter.input`                     | DOM canvas (method, DCE'd when unused — fine)                                |
| `utils/animate.scala` `Animator`    | global `requestAnimationFrame` — irrelevant offline (user loop); window plan |

No external code reads `p.canvas` / `p.context` (grep over sketches, `src/`,
examples). The painter already defines its own `GPUTextureUsage` /
`GPUBufferUsage` / `GPUShaderStage` constants, so it does not depend on the
WebGPU globals that node-webgpu only installs via
`Object.assign(globalThis, globals)` — **keep that invariant** (add `GPUMapMode`
the same way).

Out of scope, stay browser-only: `utils/events/*`, `dev/*`, `preact/*`.

---

## 3. Milestone 1 — headless painter

### 3.1 `Surface` — what `show()` presents to

`Painter` stops owning a canvas and owns a `Surface`:

```scala
trait Surface:
  /** Drawable size in physical pixels. */
  def width: Int
  def height: Int
  /** Format of the textures `currentTexture` hands out; the painter's
    * `preferredFormat` (panel default + blit target). */
  def format: TextureFormat
  /** Called once with the device, before the first frame. */
  def configure(device: GPUDevice): Unit
  /** Texture `show` / `draw` render into this frame. */
  def currentTexture(): GPUTexture
  /** End of one presented frame: finishes and submits `encoder`. Browser:
    * just the submit. Native window: submit, then present. Capture: append
    * the copy, submit, read back. */
  def present(encoder: GPUCommandEncoder): Unit
  /** Resize notifications; never fires on fixed-size surfaces. */
  def onResize(cb: (Int, Int) => Unit): Unit
```

`present` takes the frame's encoder so a capture surface can append its
`copyTextureToBuffer` to the same submission as the blit. It also submits,
because the capture has to submit before it can `mapAsync` the staging buffer.

Frame scheduling is **not** on the trait: offline renders are driven by a user
loop with a fixed `tpf` (§3.7), and the browser keeps `animate` as is.
Scheduling only becomes a surface concern for native windows (no
`requestAnimationFrame` in Deno's raw backend) — that plan adds it.

| Surface                           | `currentTexture`                               | `present`                                                                | `onResize`       | Phase |
| --------------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------ | ---------------- | ----- |
| `CanvasSurface(canvas)`           | `context.getCurrentTexture()`                  | submit                                                                   | `ResizeObserver` | M1.0  |
| `CaptureSurface` (internal, §3.4) | owned texture, `RENDER_ATTACHMENT \| COPY_SRC` | copy → staging buffer → `mapAsync` → append (frames) / overwrite (image) | never            | M1.1  |

`CaptureSurface` is **not** user-facing: the user hands the painter a plain
`PixelFrames` or `PixelImage` container (§3.5) and the painter wraps it. The
readback machinery (staging buffers, pending `mapAsync`) lives in the wrapper,
so the container stays pure data.

`CanvasSurface` is today's `Painter.init` body moved verbatim: browser behavior
does not change. It is the only place that calls
`gpu.getPreferredCanvasFormat()` and `ResizeObserver`.
`CanvasSurface(canvas, format = …)` takes an optional format. Without one it
uses the browser's preferred canvas format, read from `navigator.gpu`, with
`bgra8unorm` as the fallback. That read is browser-only and takes only a format,
not the GPU. As implemented it takes an `HTMLCanvasElement` only.
`OffscreenCanvas` has no client size and no `ResizeObserver`, so it would need
its own sizing; that is added once something needs it.

One small difference: `onResize` callbacks now receive the integer canvas size.
Before, they got the observer's fractional CSS size, while the canvas itself was
already integer-sized.

**No auto-detection** (reasoning carried over from the host-seam design in
[non-browser-hosts-plan.md](non-browser-hosts-plan.md)): a detecting
`init(anything)` makes every surface reachable from every bundle and defeats
Scala.js DCE. Surfaces are constructed explicitly by the entry point; a browser
bundle links `CanvasSurface` only, an offline bundle links `CaptureSurface`
only. `Painter` references the trait. (Telling a container from a `Surface`
inside `create` is not detection in this sense — it only separates the arguments
that one method accepts.)

### 3.2 Painter init API

> **Renamed after M1 (PR #3 review):** `Painter.create` is now the
> `Painter.init(target, gpu, …)` / `Painter.init(target, device)` overloads, and
> `target` also accepts a raw canvas. This section and the M1 log keep the old
> name; see log entry 2026-10-26, item 7.

Two tiers. The **standard** browser setup stays exactly as it is today. The
**extended** setup takes the GPU from the caller: the library never looks one
up.

```scala
object Painter:
  // standard — unchanged; the only library code that reads navigator.gpu.
  // Sugar over create(CanvasSurface(canvas), <navigator.gpu>).
  def init(canvas: HTMLCanvasElement): js.Promise[Painter]
  def init(canvas: HTMLCanvasElement)(setup: Painter => Unit): js.Promise[Unit]

  // extended — caller supplies the GPU; painter requests adapter + device.
  // Target: a plain container (offline) or a Surface.
  def create(
      target: PixelTarget | Surface,
      gpu: GPU,
      powerPreference: Maybe[PowerPreference] = Maybe.Not,
      forceFallbackAdapter: Boolean = false,
      requireAdapter: Maybe[js.Function1[GPUAdapterInfo, Boolean]] = Maybe.Not,
      requiredFeatures: Maybe[Arr[String]] = Maybe.Not,
      requiredLimits: Maybe[js.Object] = Maybe.Not,
      logAdapter: Boolean = false,
  ): js.Promise[Painter]

  // extended — caller supplies the device; synchronous, no lookups at all
  def create(target: PixelTarget | Surface, device: GPUDevice): Painter
```

`type PixelTarget = PixelFrames | PixelImage`. `Surface` is a Scala trait, so
`isInstanceOf[Surface]` picks it out at runtime. Of the plain objects, one with
a `frames` field is a `PixelFrames`; anything else is a `PixelImage`.

The two `create` alternatives are distinguished by the second parameter's static
type (`GPU` vs `GPUDevice`); only the first has defaults, which Scala allows.
Taking control at the adapter level means doing the two steps by hand and
passing the device, through helpers that are themselves just thin wrappers with
no lookups:

```scala
object WebGPU:
  def requestAdapter(
      gpu: GPU,
      powerPreference: Maybe[PowerPreference] = Maybe.Not,
      forceFallbackAdapter: Boolean = false,
      requireAdapter: Maybe[js.Function1[GPUAdapterInfo, Boolean]] = Maybe.Not,
      logAdapter: Boolean = false,
  ): js.Promise[GPUAdapter]
  def requestDevice(
      adapter: GPUAdapter,
      requiredFeatures: Maybe[Arr[String]] = Maybe.Not,
      requiredLimits: Maybe[js.Object] = Maybe.Not,
  ): js.Promise[GPUDevice]
```

```scala
// adapter-level control, entirely in user code
WebGPU
  .requestAdapter(gpu, requireAdapter = WebGPU.adapterNamed("nvidia"))
  .flatMap(a => WebGPU.requestDevice(a, requiredFeatures = Arr("float32-filterable")))
  .map(device => Painter.create(PixelFrames(1024, 1024), device))
```

Where `gpu` comes from — `create([...])` from npm `webgpu` in a TS driver,
`navigator.gpu` read by user code in a runtime with native WebGPU, a Scala
`@JSImport` in the user's own entry file — is user land in every case. The
browser gets the extended path too, e.g.
`Painter.create(CanvasSurface(canvas), dom.window.navigator.gpu, powerPreference = PowerPreference.HighPerformance)`,
where the `navigator` read is again the caller's.

`Painter` gains `adapterInfo: Opt[GPUAdapterInfo]` (null when handed a device),
and `surface: Surface` (the internal `CaptureSurface` when given a
`PixelTarget`). `width` / `height` / `onResize` delegate to the surface;
`canvas` / `context` leave the constructor. `p.canvas` stays as an accessor,
since the guides attach DOM listeners to it, and it throws on a painter without
a `CanvasSurface`. `p.context` is gone; nothing used it.

### 3.3 Adapter control, offline

Two layers, because selection splits across the `GPU` boundary (§1.2):

**Runtime level — in the driver, before `GPU` exists.** The driver script maps
generic flags to node-webgpu's mechanism (on Node, or on Bun if it loads the
addon):

| Flag                 | node-webgpu                                                                                                      |
| -------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `--backend vulkan`   | `create(["backend=vulkan"])`                                                                                     |
| `--adapter <name>`   | `create(["adapter=<name>"])`                                                                                     |
| `--software`         | `forceFallbackAdapter = true` (no adapter in node-webgpu 0.6.2 on Linux; use `--adapter` with the llvmpipe name) |
| `--vk-driver nvidia` | `VK_DRIVER_FILES=…/nvidia_icd.json`, set before spawn                                                            |

A runtime with its own WebGPU (Bun's native one, if it lands) maps the same
flags to its own mechanism. The flags are the driver's contract, not the
painter's.

Env vars must be set before the runtime initializes its GPU instance, so the
driver either documents them or re-spawns itself with them set.

**Painter level — verify, don't trust.** `requireAdapter` receives
`adapter.info` and rejects the promise with a message listing what was found
(`vendor / architecture / device / description`). A convenience
`WebGPU.adapterNamed("nvidia")` builds a case-insensitive substring predicate
over those fields. `logAdapter = true` prints the info once — default on in the
offline drivers, off in the browser.

A `list-adapters` driver mode: on Node it calls `create(["adapter=?"])` and
relays Dawn's `Available adapters` list (backend + exact name to pass to
`--adapter`). It also prints `info` for default, `high-performance`, `low-power`
and fallback requests. That is as close to enumeration as the spec allows. With
node-webgpu `--adapter` is exact (Dawn picks by name). On a runtime without a
name option it would be a `requireAdapter` check after the fact.

### 3.4 `show` — one present algorithm for every target

`show(panel)` writes slot 0 of a painted panel into the painter's target. One
package-private routine does it for the canvas, for both container types, and
for `showTo` (§3.9). It is the same algorithm for every target: **today's
sampled blit** (full-screen triangle, `textureSample` with a nearest sampler),
then a readback for containers. It scales when the sizes differ and converts
when the formats differ.

**One blit, no size-specific path, deliberately.** A `textureLoad` blit for
equal sizes was considered (the `TODO(perf, micro)` in `painter.scala` `show`).
It would drop only the sampler binding: the pass and the per-`show` bind group
stay. It also buys no exactness. At equal size, the interpolated `uv` of every
pixel center lands mid-texel, with float error orders of magnitude below the
half-texel margin, so the nearest sampler returns the exact texel. The TODO is
removed when this lands.

**Targets** — the destination of the blit, and what follows it:

| Target                                                                                | Destination                                        | After the blit                                                                                       |
| ------------------------------------------------------------------------------------- | -------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| canvas (`CanvasSurface`)                                                              | `context.getCurrentTexture()`, configured as today | nothing — the browser presents                                                                       |
| container: `show` on a `PixelFrames` / `PixelImage` painter, or `showTo(panel, into)` | capture texture in the container's size and format | `copyTextureToBuffer` in the same submission → readback → append (frames) / overwrite `data` (image) |

```scala
// package-private on Painter: blit slot 0 of `panel` into `dst`
private[painter] def blitInto(
    encoder: GPUCommandEncoder,
    panel: Panel,
    dst: GPUTexture,          // canvas: getCurrentTexture(); container: capture texture
    dstFormat: TextureFormat,
): Unit
```

`blitInto` is shared by every path. The canvas `show` is just
`blitInto(getCurrentTexture())`. The package-private `read` is the container
path: `blitInto(captureTexture)` plus `copyTextureToBuffer` and the readback
steps below. It serves `show` on a container painter and `showTo` on any
painter, so it runs in the browser too.

Every readback is eager: each `show` / `showTo` reads back in its own
submission. The capture texture is a scratch texture
(`RENDER_ATTACHMENT | COPY_SRC`) cached per (size, format). A container
painter's `CaptureSurface` owns the entry for its container. `showTo` on a
canvas painter uses the same cache.

**No raw copies, deliberately.** Skipping the blit when size and format match
(`copyTextureToTexture` to the canvas, `copyTextureToBuffer` straight from the
panel) would save one bandwidth-bound full-screen pass, perhaps 0.05–0.2 ms at
4K, and nothing else. Against that:

- The canvas would need `COPY_DST` in its configuration, plus a fallback for
  surfaces that can't offer it. On some drivers it also disables swap-chain
  compression.
- Reading from panels would need `COPY_SRC` on every panel texture, in every
  sketch, for an offline-only path.
- Two code paths instead of one.

Today's sampled blit has never been a bottleneck, and offline renders don't care
about milliseconds. Panel textures and the canvas configuration stay as they
are.

Consequences:

- **Offline output:** a panel sized like its container comes out exact for every
  container format. 8-bit values round-trip through `i/255`, and half and full
  floats pass through `f32` unchanged (except NaN payloads, which may be
  canonicalized). That covers stills, sequences and texture bakes alike.
- **Pipeline cache per format.** A render pipeline is compiled for a fixed
  target format, so the blit needs one pipeline per destination format: the
  canvas format plus the three container formats, created lazily. The cache
  already exists: mip generation keeps `getMipBlitPipeline(format)`
  (`painter.scala`), a `Dict` of the same `BLIT_WGSL` and the same layout per
  format. Only the sampler in the bind group differs (nearest vs. linear). So
  the show blit uses that cache directly, renamed `blitPipelineFor(format)`, and
  today's single `lazy val blitPipeline` goes away.
- **Cached bind group.** Today `show` calls `device.createBindGroup` on every
  frame. The blit bind group depends only on the panel's slot-0 view and the
  fixed nearest sampler. It doesn't depend on the target format, since all
  per-format pipelines share one layout. So it lives on the
  **`TextureViewBundle`** (`panel.scala`) as a lazily built
  `var blitGroup: GPUBindGroup`, a dedicated blit cache with no epoch tracking
  and no keys. `swapPair` swaps whole bundles, so each pong texture keeps its
  own group. `ensureSize` creates new bundles, so a reallocation starts empty.
  `show` reads `views(0).blitGroup` and builds it on a null. Steady-state `show`
  then creates no GPU objects at all. The layer cache's `(panelId, bindEpoch)`
  key would miss on every pong frame, since `swapPair` bumps the epoch.
- **`rgba32float` needs `float32-filterable`.** The blit layout stays as it is
  (filterable float texture, filtering sampler), and an `rgba32float` panel
  needs the device feature to bind to it. On an `rgba32float` container, panels
  default to `rgba32float`, so that render requests it:
  `Painter.create(out, gpu, requiredFeatures = Arr("float32-filterable"))`.
  Offline renders target known hardware, and every desktop GPU offers it.
  `create` fails fast: an `rgba32float` container without the feature throws
  right away with this hint, instead of a validation error at the first `show`.
  The setup guide (§6.1) documents it.
- **Conversion is free.** The fragment shader returns a `vec4<f32>`, and the GPU
  stores it in the target's format, so the blit has no conversion code.
- MSAA panels need nothing extra: slot 0 is already the resolved single-sample
  texture.

**Format conversion.** Every write is a blit, so a panel of any format can be
shown into any target format. Precision follows the destination:

- Float ↔ float never clamps: HDR values outside `[0,1]` survive both readback
  and upload. The only limits are f16's own: overflow beyond ±65504 to ±inf,
  about 3 significant digits, and flush to 0 below ~6e-8.
- f16 → f32 is lossless. f32 → f16 rounds, and values beyond ±65504 become ±inf.
- `rgba8unorm` → f16 / f32 is lossless: all 256 steps survive, and converting
  back to 8-bit gives the same bytes.
- Any float → `rgba8unorm` clamps to `[0,1]` and rounds to the nearest of the
  256 steps, with no tonemapping. NaN is implementation-defined.
- `bgra8unorm` panels need nothing extra: samples return RGBA order.
- `-srgb` panel formats decode to linear in a blit. Shown into `rgba8unorm`,
  they come out as linear values, not the stored bytes, consistent with what the
  canvas shows.
- Channels the panel lacks are filled as `(·, 0, 0, 1)`, e.g. `r32float` comes
  out as `(r, 0, 0, 1)`.
- `rgba32float` panels need `float32-filterable` (above).
- A `rgba32float` destination is a core render target. The blit needs no
  blending, so no `float32-blendable`.

**HDR end to end** needs every panel in the chain to be float, because an 8-bit
intermediate clamps. Panels without explicit formats use the surface format:
float when the painter is created on a float container, but `bgra8unorm` on a
canvas painter. So an HDR `showTo` from a canvas painter needs explicit float
panel formats.

**Readback** (container targets and `showTo`):

- **`PixelFrames`: one frame appended per `show`.** Nothing else appends, and
  every frame has the container's `width` × `height`. Frames land at their
  submission index, so order is the order of `show` calls.
- **`PixelImage`: each `show` overwrites `data`.** Every `show` is a full
  readback, so a loop of `show`s on an image target costs one readback each.
  That is the expected price, and the sequence case belongs to `PixelFrames`.
  The result is written **in place** (`data.set(…)`): the reference stays valid,
  and a host buffer can be the target directly (e.g. pngjs's `png.data`). `data`
  must already have the right type and length, checked at `create`, which throws
  otherwise. Several readbacks of one image can be in flight at once. Each
  carries its submission index, and a completion older than the last one written
  is skipped, so the last `show` wins regardless of resolve order. A host that
  wants to keep an earlier result copies it.
- **`p.awaitReadback(): js.Promise[Unit]`** resolves once every pending readback
  has landed, including `showTo`'s. **The container is complete only after it
  resolves**; until then its contents are undefined. It returns no container,
  because the caller already holds its own. It is only for readback: `showTo`
  carries its own promise, so a canvas painter needs it only to await several
  un-awaited `showTo`s at once, and with nothing pending it resolves
  immediately. Its scaladoc says so, so it doesn't read as a step of normal
  rendering.
- **Mechanics.** All of this is painter code, since WebGPU yields no keepable
  array:
  1. A staging buffer (`MAP_READ | COPY_DST`) with
     `bytesPerRow = align(width · bpp, 256)`.
  2. `copyTextureToBuffer` from the capture texture, in the same submission as
     the blit.
  3. `mapAsync(READ)`.
  4. Copy out of `getMappedRange()`, which is a temporary view of mapped memory
     and is **detached on `unmap()`**. The copy is therefore required, and the
     unpadding rides along: one `set` per row, or a single copy when
     `width · bpp` is a multiple of 256. The target is a fresh typed array
     (frames) or `data` (image).
  5. `unmap`, and the buffer goes back to a pool.
- The capture texture is in the container's format, so data always arrives in
  that format, RGBA with no swizzle. The display path writes raw (non-sRGB)
  values exactly as the browser canvas does, so an `rgba8unorm` capture looks
  like the browser frame.

**Deliberately not covered:**

- **Other readback APIs.** `show` (with `showTo`) is the only public readback.
  `draw()` output is not captured: draw into a panel and `show` it. A public
  `read` (e.g. for MRT slots) is added later, only if really needed.
- **Mip-level extraction.** Mips differ in size, so they fit neither one
  `PixelFrames` nor an upscaled frame. To export one, sample it through a
  fixed-size intermediate panel (`panel.binding(mipLevel = k)`, size `w >> k` ×
  `h >> k`) and `showTo` it into a `PixelImage` of that size, which comes out
  exact.
- **Integer panel formats** (`*uint` / `*sint`) would need an integer pipeline;
  `show` throws on them.
- **Depth** is not readable (`depth24plus` is not copyable).

### 3.5 The data boundary: `PixelFrames` and `PixelImage`

Two plain JS object shapes, defined as **structural contracts**, not classes: TS
creates them as object literals, Scala as non-native JS traits; neither side
imports anything from the other to do so.

```ts
// the contract, as a host would declare it
type PixelFormat = 'rgba8unorm' | 'rgba16float' | 'rgba32float'
type PixelData = Uint8Array | Float16Array | Float32Array // matches format, see below

interface PixelFrames {
  // a sequence, output only; passed instead of a canvas
  width: number
  height: number
  format: PixelFormat
  frames: PixelData[] // painter appends one per show()
}

interface PixelImage {
  // a single image: output (show() overwrites `data`)
  // and input (texture source, §4)
  width: number
  height: number
  format: PixelFormat
  data: PixelData
}
```

```scala
// Scala side: non-native JS traits — field access compiles to plain property
// access, zero wrapper cost, and a TS literal satisfies them as-is.
trait PixelFrames extends js.Object:
  val width: Int
  val height: Int
  val format: PixelFormat
  val frames: Arr[PixelData]
object PixelFrames:
  def apply(width: Int, height: Int,
            format: PixelFormat = PixelFormat.Rgba8Unorm): PixelFrames

trait PixelImage extends js.Object:
  val width: Int
  val height: Int
  val format: PixelFormat
  val data: PixelData
object PixelImage:
  /** Zeroed `data` of the right type and length — the usual way to make an
    * output target. As a texture source it is all black (transparent). */
  def apply(width: Int, height: Int,
            format: PixelFormat = PixelFormat.Rgba8Unorm): PixelImage
  /** Wraps existing data; `format` follows the array type. Throws if the
    * length is not `width · height · 4`. */
  def apply(width: Int, height: Int, data: PixelData): PixelImage

type PixelData = Uint8Array | Float16Array | Float32Array
type PixelTarget = PixelFrames | PixelImage
```

The Scala factories only build the literal; they add no behavior a TS-made
object would lack. The contract is the shape, so a `.d.ts` with the two
interfaces ships next to the offline examples for TS hosts. `PixelFormat` is an
opaque `String` type in Scala with the three values below.

**Formats — exactly three:**

| `format`      | per channel     | array per frame                | bytes / px | use                                               |
| ------------- | --------------- | ------------------------------ | ---------- | ------------------------------------------------- |
| `rgba8unorm`  | uint8, `[0,1]`  | `Uint8Array`, length `w·h·4`   | 4          | display frames, 8-bit PNG, video (default)        |
| `rgba16float` | IEEE half float | `Float16Array`, length `w·h·4` | 8          | HDR stills and sequences, compact float bakes     |
| `rgba32float` | IEEE float      | `Float32Array`, length `w·h·4` | 16         | numeric processing, LUTs / data bakes, EXR, sharp |

All three arrays are native and hold exactly the GPU's bytes, so readback and
upload need no CPU conversion. Elements read and write as plain JS numbers,
including half floats. `Float16Array` is ES2025: Baseline since Apr 2025 (Chrome
135, Firefox 129, Safari 18.2), and present in Node 24 and Bun 1.4 (checked).
Scala.js 1.22 has no facade for it, so trivalibs adds a `@js.native @JSGlobal`
one.

Choosing between the float formats: `rgba16float` is the HDR default, half the
memory, and what long sequences should use. `rgba32float` costs twice as much
and fits stills and data textures, where a downstream tool wants `float32`
anyway (sharp raw `float` input, most EXR writers) or the data needs full
precision. It requires the `float32-filterable` device feature, both as a render
target container (§3.4) and as a texture **source** (§4). Where that feature is
unavailable, use `rgba16float`.

**Layout:** rows tightly packed (row padding stripped), top row first, RGBA
order, straight (non-premultiplied) alpha as rendered. In the browser, an
`rgba8unorm` frame `d` (`out.frames[i]` or `img.data`) becomes an `ImageData` in
one line, without copying:

```ts
new ImageData(new Uint8ClampedArray(d.buffer, d.byteOffset, d.length), w, h)
```

The new array is a second view onto the same memory. `byteOffset` is needed
because a typed array may start partway into its `ArrayBuffer`. Painter-made
arrays start at 0, but host-supplied ones may not, e.g. Node `Buffer`s sliced
from a shared pool.

### 3.6 End-to-end shape

The host creates the container and the `GPU`, calls an exported Scala function,
and processes the result. The Scala side is pure description:

```scala
@JSExportTopLevel("render")
def renderLoop(gpu: GPU, out: PixelFrames): js.Promise[Unit] =
  Painter.create(out, gpu, requireAdapter = WebGPU.adapterNamed("nvidia"))
    .flatMap: p =>
      val scene = buildScene(p)
      var i = 0
      while i < 120 do
        scene.step(1000.0 / 60.0)
        p.paint(scene.panel)
        p.show(scene.panel)
        i += 1
      p.awaitReadback()
```

```scala
@JSExportTopLevel("bake")
def bakeWall(gpu: GPU, out: PixelImage): js.Promise[Unit] =
  Painter.create(out, gpu).flatMap: p =>
    val wall = TextureBaker.bake(p, wallForm, out.width, out.height)(wallShade)
    p.show(wall) // same size as `out` → exact
    p.awaitReadback()
```

Per-runtime drivers — user land, the only runtime-specific code. They hand the
`GPU` straight to the exported function; no `navigator` shim, no
`Object.assign(globalThis, globals)` (the painter carries its own WebGPU
constants, §2):

```ts
// node:  node render.node.ts --backend vulkan
import { create } from 'webgpu'
import { render } from './render.js'
const out = { width: 1920, height: 1080, format: 'rgba8unorm', frames: [] }
await render(create(['backend=vulkan']), out)
// … encode out.frames (§3.8) …
process.exit(0) // Dawn keeps the event loop alive while the GPU is referenced
```

```ts
// bun:  bun render.node.ts --backend vulkan
// The same driver, if Bun loads node-webgpu's Node-API addon (to verify, §7).
// If Bun's native WebGPU lands: render(navigator.gpu, out) instead.
```

Doing it all in Scala instead (create the container in Scala, encode via an npm
package through `@JSImport`) is equally valid — the boundary does not force the
split, it only makes the TS split cheap.

The consuming repo grows a
`scripts/render.ts <path> [--runtime node|bun] [--backend …] [--adapter …]` that
builds the offline entry with scala-cli and spawns the chosen runtime — a
consumer-side plan, not trivalibs.

**Entry-point signature.** Browser sketches keep
`sketch(canvas: HTMLCanvasElement)`; offline entries take `gpu: GPU` and a
`PixelFrames` / `PixelImage` container (or build one themselves) and run their
own loop, since size, frame rate and frame count belong to the render. Whether
browser sketches should later take a `Surface` instead (so one body runs on
every host) is the native-window plan's decision; nothing here forces it.

**`Painter.input`** needs a DOM canvas and stays browser-only. It must not be
reached from code meant to run offline: a portable body takes inputs as
parameters (or none — an offline render has no user). Moving it onto
`CanvasSurface` is the native-window plan's call.

### 3.7 Motion sequences: a user loop with fixed `tpf`

No scheduler, no `p.animate`: the offline script owns the loop and passes a
fixed `tpf` to whatever it animates. Deterministic and frame-exact regardless of
GPU speed:

```scala
val tpf = 1000.0 / 60.0
var i = 0
while i < 120 do
  step(tpf)
  p.paint(scene)
  p.show(scene)
  i += 1
p.awaitReadback()   // → js.Promise[Unit], all 120 frames in `frames` once it resolves
```

**Memory is the known limit of this simple version.** All frames end up in the
container before the host sees any. And since `mapAsync` only resolves when the
event loop runs, which a synchronous loop never yields to, every frame also
holds a staging buffer until the loop ends. Roughly 8 MB per 1080p `rgba8unorm`
frame (16 MB for `rgba16float`): fine for stills, short loops and GIFs,
impractical for minutes of video. Streaming is the planned enhancement for that
(§3.10); the container API is shaped so it can be added without changing this
one.

`p.animate` / a surface-driven frame loop is deferred to the native-window plan,
where the host (not the script) owns the frame rate.

### 3.8 Processing the frames — host side

After `awaitReadback()`, the container is ordinary JS data, processed by
whatever the user prefers. The intended default is a short host script (§1.4 for
the tooling); trivalibs ships no codec or file layer in this plan.

```ts
// PNG still, Node/Bun — pngjs. The PNG's own buffer is the target, so
// nothing is copied: the painter writes straight into png.data.
import { PNG } from 'pngjs'
import { writeFileSync } from 'node:fs'
const png = new PNG({ width: 1024, height: 1024 })
const img = { width: 1024, height: 1024, format: 'rgba8unorm', data: png.data }
await bake(gpu, img)
writeFileSync('assets/wall.png', PNG.sync.write(png))
```

```ts
// mp4 / gif — ffmpeg reading raw frames from stdin
import { spawn } from 'node:child_process'
const ff = spawn('ffmpeg', [
  '-y',
  '-f',
  'rawvideo',
  '-pix_fmt',
  'rgba',
  '-s',
  `${out.width}x${out.height}`,
  '-r',
  '60',
  '-i',
  '-',
  '-c:v',
  'libx264',
  '-pix_fmt',
  'yuv420p',
  'out/loop.mp4',
])
for (const f of out.frames) ff.stdin.write(f)
ff.stdin.end()
await new Promise((r) => ff.on('close', r))
```

### 3.9 Ad-hoc targets: `showTo` and `atSize`

The init-time target (canvas or container) stays the default for `show`. Any
painter, canvas-backed included, can also show a panel into a caller-supplied
container, e.g. for screenshots next to canvas rendering, a different
resolution, a second format, or a dev "download bake" button in the browser.

```scala
// on Painter
/** Writes `panel` into `into` by the §3.4 rules, then appends one frame
  * (PixelFrames) or overwrites `data` (PixelImage). Resolves with `into`
  * once this readback has landed: `into` is complete, no `awaitReadback`
  * needed. Other readbacks still in flight are not awaited. */
def showTo[T <: PixelTarget](panel: Panel, into: T): js.Promise[T]

/** Runs `body` with the target size overridden: panels without their own
  * size use `width`×`height`, `p.width` / `p.height` report it, and `onResize`
  * callbacks fire with it. Restored after `body`, whose result is returned. */
def atSize[A](width: Int, height: Int)(body: => A): A
```

**A distinct name, not a `show` overload.** The return type differs
(`js.Promise` vs `Unit`), and an overload would hide the asynchrony at the call
site. Like `show` on a container, every `showTo` reads back right away.

A screenshot at canvas resolution is just `p.showTo(final, shot)` after the
regular paint. At a different resolution:

```scala
val shot = PixelImage(3840, 2160)
val saved = p.atSize(shot.width, shot.height):
  p.paint(scene, bloom, final)
  p.showTo(final, shot)
// the next regular p.paint(...) sizes the panels back to the canvas
```

**Why a size scope rather than `paint(width, height, panels*)`:**

- `paint` has 13 overloads (varargs plus inline arities 1–12). A sized variant
  doubles them.
- Size affects more than panel textures. The `onResize` callbacks (camera
  aspect, resolution uniforms) and `p.width` / `p.height` must follow too, or a
  16:9 shot renders with the canvas aspect. One scope covers `paint`, `draw`,
  `show` and `showTo` together.
- Restoring textures costs nothing extra. `ensureSize` is lazy and compares
  against the current target, so the next regular `paint` reallocates. Only the
  `onResize` callbacks need an explicit restore: at scope end they fire again
  with the surface size. The ordering is safe: `writeBuffer` and submits are
  ordered on the queue, and `texture.destroy()` after a submit waits for
  in-flight work.

**Mechanics:**

- `paintPanel` sizes from `targetWidth` / `targetHeight`: the override if set,
  else the surface size. Nested scopes save and restore it.
- `body` is synchronous. A `ResizeObserver` callback can't run inside it, and
  `showTo`'s promise waits only on a readback that is already submitted.

**Costs and caveats:**

- Each scope reallocates every panel that follows the target size twice, once
  going in and once coming out. That's fine for screenshots. Capturing every
  frame at a second resolution next to the canvas (recording 4K while
  previewing) would thrash. The later fix, only if needed: a second texture set
  per panel, keyed by the override size and swapped instead of reallocated. That
  doubles memory, but each size keeps its own history.
- Panels that carry history across frames (no clear, feedback, accumulation)
  lose their content on reallocation, the same as on a window resize today.
- Fixed-size panels are unaffected, by design. Setting `width` / `height` is the
  explicit opt-out of automatic sizing.
- CPU-side data built once from `p.width` / `p.height` at setup does not follow.
  That covers geometry and layouts, e.g. `examples/bevel_lines_2d`
  `generateGeometry(p.width, p.height)` and the line2d test sketches. It already
  ignores window resizes today. To follow the target, rebuild it in `onResize`,
  which the scope fires.
- Pixel-unit parameters (line widths and blur radii in px) change their size
  relative to the frame, as on a resized window. That is user land.

No `screenshot(out, panels*)` convenience is proposed. The three lines above are
the whole pattern.

### 3.10 Later: frame streaming (enhancement, not in this plan)

For long sequences the container gains an optional field instead of a new API —
so §3.5 stays valid unchanged:

```ts
interface PixelFrames {
  // …as before…
  sink?: WritableStream<PixelData> // if set: frames are written here, not kept
}
```

Web Streams exist in browsers, Node and Bun with built-in backpressure, and
every host can put one in front of ffmpeg's stdin (`Writable.toWeb(proc.stdin)`
on Node, a three-line `WritableStream` over `Bun.spawn`'s sink). A host wanting
to pull instead uses a `TransformStream` and reads its readable side.

**No new wait API.** With a sink, a frame has "landed" once it is written to the
stream, so `awaitReadback()` (§3.4) also resolves only once the writer is ready
again (`writer.ready`, the stream's backpressure). Awaiting it per frame bounds
memory. It also serializes GPU work and readback, which costs nothing that
matters offline. A loop that wants the two to overlap awaits every k frames
instead.

The loop on the Scala side is a recursive step that binds each wait with a `for`
over the promise extensions in `utils/js.scala`. The recursion does not grow the
stack, because every step runs in its own microtask:

```scala
def renderFrom(i: Int): js.Promise[Unit] =
  if i == FrameCount then js.Promise.resolve(())
  else
    update(Tpf)
    p.paint(scene, final)
    p.show(final)
    for
      _ <- p.awaitReadback()
      _ <- renderFrom(i + 1)
    yield ()
```

Deferred only because nothing needs it yet. The first long render that does adds
`sink` and makes `awaitReadback` wait on it.

### 3.11 Kept compatible: native-window surfaces (separate issue)

Not implemented here, but the `Surface` trait must admit them with at most one
**additive** change (frame scheduling, below) — anything beyond that means this
design was wrong. Deno's window API is now uncertain (§1.1), but its constraints
stay as a check. They are the general shape of a native-window surface, and
NativeScript or a successor host may share them:

- **Explicit present** (Deno `UnsafeWindowSurface.present()`) — covered by
  `present(encoder)`, called once per `show`.
- **Resize driven by the host** (Deno window `resize` event →
  `surface.resize(w, h)`; NativeScript layout events) — covered by `onResize`;
  panels already re-size from `surface.width/height` in `paintPanel`.
- **No `requestAnimationFrame`** (Deno raw backend loops with `setTimeout`) —
  **not covered yet, deliberately**: the window plan adds `requestFrame` to
  `Surface` plus `p.animate` routed through it, and a scheduler-taking
  `Animator`. Additive: offline never needs it, and nothing in `Painter` touches
  `requestAnimationFrame` today (only the free `animate` does).
- **Host-chosen format** (`getPreferredCanvasFormat` may be missing; fall back
  to `bgra8unorm`) — covered by `Surface.format`.
- **Device before surface** (Deno: `getNativeWindow()` throws before
  `requestAdapter()`) — `Painter.create` resolves the device first, then calls
  `surface.configure(device)`, so a surface may construct its context lazily in
  `configure`.

---

## 4. Milestone 2 — texture sources on panels

### 4.1 One source type

```scala
/** Browser-native images; types only, never `instanceof`-checked at runtime. */
type ExternalImage =
  dom.ImageBitmap | dom.ImageData | dom.HTMLImageElement | dom.HTMLCanvasElement |
  OffscreenCanvas | dom.HTMLVideoElement | VideoFrame

type TextureSource = PixelImage | ExternalImage
```

Raw buffers enter as a `PixelImage` (§3.5) — `{ width, height, format, data }`,
the same exchange format as the output side, creatable in TS or Scala. It is the
equivalent of Rust's `with_static_texture_data(bytes)`, with size and format
explicit because bytes carry neither. One frame of a `PixelFrames` container
converts with the extension `frames.pixelsAt(i)`, which pairs it with the
container's size and format (the container's shape stays the plain data
contract), so a frame read back in one run can be fed straight back in.

Runtime dispatch is duck-typed on our contract: **an object with both a `data`
and a `format` field is a `PixelImage` → `writeTexture`; anything else →
`copyExternalImageToTexture`**. (`ImageData` has `data` but no `format`, a
`VideoFrame` has `format` but no `data`, so both take the external path.) Never
`instanceof HTMLImageElement` — referencing a DOM global throws in Node. Size of
external sources is duck-typed in this order: `videoWidth` (video) →
`naturalWidth` (img) → `displayWidth` (VideoFrame) → `width` (bitmap, ImageData,
canvas). The order matters: `<video>` and `<img>` also have a `width` that is
the layout size, not the pixel size.

### 4.2 Panel API

```scala
p.panel(source = bitmap)                              // size from source
p.panel(source = PixelImage(256, 256, noiseHalfs)) // Float16Array → rgba16float
p.panel(source = img, format = TextureFormat.Rgba8UnormSrgb, mips = true)
p.panel(source = video)                               // current frame; re-upload by set (WebVideo later, §4.3)
p.panel(source = photo, followSurface = true, shapes = scene) // canvas-sized, photo stretched behind
p.panel(source = img, layers = Arr(blurX, blurY))     // effect stack over the image
panel.set(source = nextFrame)                         // explicit re-upload
```

New `Panel.set` / `Painter.panel` params: `source: Maybe[Opt[TextureSource]]`
(`null` unsets), `sourceBlend: Maybe[Opt[BlendState]]`,
`followSurface: Maybe[Boolean]`, `flipY: Maybe[Boolean]`,
`premultipliedAlpha: Maybe[Boolean]`.

#### The contributor stack

A source is one more contributor to the panel's image. **It is not a panel
kind.** Every contributor can coexist with every other, and each paint builds
the image in this fixed order:

```
clear color  →  source  →  shapes  →  layers
(if set)        (if set)   (shape pass)  (as today)
```

- **Clear color.** Unchanged. Set → `loadOp: clear`. `null` → `loadOp: load`:
  the previous content stays, and new draws stack on top of it.
- **Source.** Drawn as the **first draw of the shape pass**: a fullscreen
  triangle that samples the source texture.
  - Without `sourceBlend` it replaces the whole target, so the clear color is
    overdrawn. With `sourceBlend` it blends over the clear color, or over the
    previous content when there is no clear color.
  - Its pipeline uses `depthCompare: "always"` and `depthWriteEnabled: false`.
    Depth stays at its clear value of 1.0, so shapes always land on top of the
    background, even with `depthTest`.
  - It goes through the same pipeline cache as shapes. The key covers the
    formats, depth, MSAA and blend, so MSAA panels need nothing extra: the
    background is drawn into the multisample attachment and resolved with the
    shapes. `getPipeline` hard-codes `less` with depth writes on today, so the
    key gains a depth mode.
  - **Not on MRT panels.** `set` throws for a source on a panel with several
    `formats`. MRT stays strictly generative: shapes, or later a single
    generative layer. To use an image in an MRT render, bind a source panel as a
    texture (`"albedo" := imagePanel`), which also makes the data flow explicit.
  - Because it is a draw, it can be scaled. Panel size and source size are
    independent, and a linear sampler scales the source. At equal size it
    samples texel centers, which is exact. An `rgba32float` source needs
    `float32-filterable`, as in §3.4.
- **Shapes.** Unchanged, drawn on top of the background.
- **Layers.** Unchanged. They read slot 0 as before, so they process the
  background together with the shapes. Pong layers don't compound across paints,
  because the source is redrawn at the start of every paint.

The old one-or-the-other rule (from the TS painter, and the previous draft of
this plan: shapes or layers on a source panel throw) is dropped.

#### Size

A source gives the panel an **intrinsic size**, the way an `<img>` has one. The
panel's size is resolved in this order:

1. Explicit `width` / `height`. The source is scaled into them.
2. `followSurface = true`. The panel tracks the surface size (inside `atSize`,
   the override, as for every unsized panel), and the source is stretched over
   it. Use it for a background under a canvas-sized scene.
3. Neither: **the source's size**.
4. No source either: the panel tracks the surface, as today.

The default is the source's size, rather than "unsized panels track the
surface", for three reasons:

- The main cases (an asset, a video, an offline input) want the image at its own
  pixels. With tracking as the default, every one of them would have to repeat
  the source's size. For an external image that size is not even one field: it
  is `videoWidth`, `naturalWidth`, `displayWidth` or `width`, by type (§4.1).
- The two failure modes differ. A wrongly tracked asset fails silently: it is
  resampled, its aspect distorted, and it is redrawn on every resize. A wrongly
  intrinsic background fails visibly: it doesn't fill the canvas. Both are fixed
  with one argument.
- The policy holds. Tracking is what a panel does when it has no size of its
  own. A source gives it one, and tracking a different size is an explicit
  opt-in, just as explicit sizes are an opt-out today.

The size is resolved in `ensureSize`, not stored in `specWidth` / `specHeight`.
Unsetting the source therefore falls back to rule 4.

**Later: fit modes (follow-up feature, not in this plan).** When the sizes
disagree, the source is stretched. A follow-up adds a choice modeled on a subset
of CSS `background-size` / `background-position`:

- `stretch`, the default;
- `cover`, which fills and crops;
- `contain`, which fits whole and leaves the rest showing the clear color or the
  previous content;
- `none`, at the source's own pixels;
- an alignment for the last three.

All of them are a UV scale and offset in the background draw, plus discarding
fragments outside `[0,1]` for `contain` and `none`. The draw path already
carries everything this needs, so adding the feature changes nothing else.

#### Paths

The path is decided from the configuration, never from a size that only matches
by coincidence. A `followSurface` panel always takes the drawn path.

- **Direct path.** It applies when the panel's size equals the source's size (by
  rule 3, or an explicit equal size), its format equals the format of the
  source's pixels, and there are **no shapes, no layers and no `sourceBlend`**.
  A `PixelImage`'s pixels are in its own format, because `writeTexture` doesn't
  convert; an external image's are in the panel's format at upload time,
  converted by `copyExternalImageToTexture`. A clear color doesn't disqualify
  the path, because a replacing source overdraws it anyway. The upload goes
  **straight into slot 0**: eagerly on `set(source = …)`, the only upload
  trigger. Paint does nothing at all; mips are generated after the upload. This
  is the static-asset case: an image that is only ever sampled. The upload can
  be eager because this path's size never depends on the surface, so `set` can
  allocate slot 0 itself.
- **Drawn path.** Every other case: shapes, layers, `sourceBlend`, a size or
  format that differs, or `followSurface`. The source keeps its own texture at
  its own size and format, and each paint draws it as described above. With
  layers (blur, color grading, any effect stack) the draw restores the untouched
  source on every paint, so pong layers process the image fresh and never
  compound.

  _As built:_ a **source-only** drawn panel — a size or format that differs, but
  no shapes, layers, `sourceBlend` or `followSurface` — is drawn by `set`
  instead of by paint, which skips it like a direct one. Its content depends on
  the source alone, so the "a source alone needs no paint" rule covers every
  fixed-size case. `set` reuses the painter's `renderPanel`, the body of paint
  after `ensureSize`. `followSurface` stays painted: redrawing on resize would
  need the painter to track panels, and the flag already says the panel follows
  the paint loop.

  _As built:_ the planned "layered direct path" — a `copyTextureToTexture` into
  slot 0 instead of the draw when only layers are added — is dropped. At equal
  size the draw samples texel centers and is exact too, so the copy would only
  save one fullscreen draw per paint, for a third path to maintain.

**Switching paths** keeps the pixels. When the source needs its own texture and
has none, the current slot-0 texture becomes the source texture, and a fresh
slot 0 is allocated. When it no longer needs one, the source texture is copied
into slot 0 (same size and format by the direct path's conditions) and freed. No
re-upload is needed, which matters for a `VideoFrame` the caller has already
closed. A reallocation on the direct path (say `mips = true` after the upload)
goes the same way: slot 0 is kept as the source texture across it and copied
back.

#### Format and usage

- **Format.** In order: an explicit `format`, the `PixelImage`'s format,
  **`Rgba8Unorm`**. Not `preferredFormat`: that is `bgra8unorm` on many browsers
  and would make the asset path format-dependent.
- **Usage.** On a source panel, every color slot (pong slot 1 included, since
  `swapPair` rotates it into slot 0) and the source texture share one usage set,
  so that switching paths can trade them: the usual
  `RENDER_ATTACHMENT | TEXTURE_BINDING`, plus `COPY_DST` (uploads, and the
  layered direct copy) and `COPY_SRC` (the copy's source).
  `copyExternalImageToTexture` also requires `RENDER_ATTACHMENT`. Panels without
  a source keep today's usage.
- **Allocation: one routine, one trigger (M2.0a).** The source usage set applies
  to every color slot, not only slot 0. Pong's `swapPair` rotates slots 0 and 1,
  so the layered copy can write into either. So a source change is a
  reallocation of the color slots, just like a change of format, MSAA, depth,
  mips or pong need, which today never reallocates after the first paint. Both
  use one mechanism:
  - the size-changed branch of `ensureSize` becomes a private
    `reallocate(w, h)`;
  - `set` compares the properties that affect allocation before and after, and
    sets `needsRealloc` on a difference. Those are the effective formats,
    `multisample`, `depthTest`, the mip count, the pong need, and the usage set.
    Usage is checked as coverage, not equality: only a missing bit reallocates,
    and a surplus bit is harmless, so unsetting a source never reallocates;
  - `ensureSize` calls `reallocate` when the size changed or the flag is set;
  - the direct path's eager upload calls the same `reallocate` with the source's
    size before uploading.

  The alternative was a source-only partial realloc in `set` now, and the
  general fix later. That would mean two allocation routines that duplicate
  texture creation and get merged afterwards.

  `set` only raises a flag, rather than reallocating itself, because it doesn't
  know the size of an unsized panel: that size is only resolved at paint time.
  The exception is the direct path, whose size comes from the source. A
  reallocation drops the pixels, which is expected for a format or MSAA change.
  A later refinement can do less in the cheap cases: a newly needed pong slot
  only appends slot 1, and `depthTest` only runs `allocDepth()`.

- **Upload options.** `flipY` and `premultipliedAlpha` are options of
  `copyExternalImageToTexture` and apply to external images only. A `PixelImage`
  is uploaded as it is.

#### Unsetting, and the uncleared background

`set(source = null)` removes the contributor and frees the source texture. If
the size stays the same, slot 0 keeps its pixels. That holds for an explicit
size or `followSurface`, and not for a size that came from the source, which
falls back to tracking the surface. To keep that size, pin it in the same call:
`set(source = null, width = w, height = h)`. A panel without a clear color then
loads the kept pixels, and its shapes go on stacking on top. Set the source,
paint once, unset it, and the panel has a frozen background that later paints
build on.

This works because a texture is only **reallocated when an allocation input
changes**: the size, or one of the properties that M2.0a's `needsRealloc` check
compares. Clear color, shapes and non-pong layers are not among them, so
changing them through `set` keeps the pixels. Unsetting the source keeps them
too: it only drops the need for the source usage bits, and a surplus bit never
reallocates.

Today, by contrast, only the size is compared
(`targetW != _width || targetH != _height`). That is a bug M2.0a fixes as a side
effect. A change after the first paint, at an unchanged size, never reaches the
GPU (from reading the code, not run):

- **First pong layer added** → no slot 1. `pongTargetView` reads an undefined
  `views(1)` and throws a TypeError.
- **`format` / `formats` changed** → the textures keep the old format while the
  pipelines use the new one, so validation fails.
- **`multisample` or `depthTest` turned on** → no MSAA or depth texture, so the
  render pass fails validation.
- **`mipLevels` / `mips` changed** → the old mip count stays.

A resize reallocates everything and repairs the panel. Changes made before the
first paint are fine.

#### Rest

- `mips = true` → the existing `generateMipmaps` runs after each paint. On the
  direct path it runs after each upload instead.
- **The core uploads only on `set(source = …)`.** Calling it again with the same
  object re-uploads, and at an unchanged size and format the texture is reused.
  `paint` never uploads, and the core has no change detection and no per-paint
  checks. Whoever knows that the source changed calls `set`:
  - user code, for a `PixelImage` whose `data` it rewrote, or for a canvas it
    drew into;
  - the caller, for each new `VideoFrame`, closing the old one;
  - the `WebVideo` helper, for a `<video>` element (§4.3).

  Content that changes every frame belongs in a generative panel, not in a
  re-uploaded buffer.

- Everything downstream is unchanged. A source panel is sampled exactly like a
  rendered one (`"tex" := panel`, `panel.binding(mipLevel = …)`), because the
  result lives in slot 0 on both paths.

### 4.3 Loading

**Browser** — one small helper, since it is pure web API and every browser
sketch wants it:

```scala
// trivalibs.graphics.painter.WebImages
def load(url: String, colorManaged: Boolean = false): js.Promise[dom.ImageBitmap]
// fetch → blob → createImageBitmap(blob, colorSpaceConversion = "none",
//                                       premultiplyAlpha = "none")
```

`colorSpaceConversion: "none"` and `premultiplyAlpha: "none"` are the defaults
on purpose: baked data textures must arrive bit-exact. A photo that should be
color-managed passes `colorManaged = true`.

```scala
// trivalibs.graphics.painter.WebVideo — deferred, see below
/** Re-`set`s `video` as `panel`'s source on every newly presented video
  * frame. Returns a stop function. */
def autoUpload(video: dom.HTMLVideoElement, panel: Panel): () => Unit
```

**`WebVideo` is deferred** until the first video sketch needs it; no sketch is
blocked on it. Until then, a video works by calling `panel.set(source = video)`
by hand. If it is still unneeded once the M2 core has landed, it moves to
`independent-todos.md`. The design, for then:

`WebVideo.autoUpload` is event-driven, not tied to `paint`:

- A `requestVideoFrameCallback` loop (re-registered in each callback) calls
  `panel.set(source = video)`. So it uploads exactly **once per new video
  frame**.
- A paused or ended video uploads nothing.
- Seeking while paused presents a frame, which triggers one upload.
- No `readyState` check is needed, because the callback only fires for a frame
  that is there.
- The stop function calls `cancelVideoFrameCallback`.

The callback is Baseline, so there is no `currentTime` fallback. Polling
`currentTime` would be the wrong signal anyway: it is a continuous clock, so a
30 fps video in a 60 Hz loop would upload every frame twice.

A canvas that runs its own animation gets no helper: the code that draws into it
calls `panel.set(source = canvas)` afterwards.

**Offline** — host side, by the design rule: the host decodes (e.g.
`sharp(file).raw().ensureAlpha()` → `Uint8Array`, `ktx-parse` for KTX2) and
passes `PixelImage` objects into the exported Scala function. No `node:fs` in
the library, so nothing can leak into a Vite bundle.

### 4.4 Video, consistently

| Environment | Source                                                                                             | Path                                        |
| ----------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------- |
| Browser     | `HTMLVideoElement`, `panel.set(source = video)` (later `WebVideo.autoUpload`)                      | copy per `set` (later per new video frame)  |
| Browser     | WebCodecs `VideoFrame`, `panel.set(source = f)`                                                    | copy per frame (Firefox: via `ImageBitmap`) |
| Offline     | host decodes (ffmpeg → raw RGBA) into a `PixelFrames`; Scala sets `frames.pixelsAt(i)` as `source` | `writeTexture` per frame                    |

So video is "a source that changes every frame" — the same panel API as a still.
Offline, the same exchange format runs both ways: the host hands in a decoded
`PixelFrames`, Scala processes it through panels frame by frame and fills an
output `PixelFrames`, the host encodes it — no browser types involved. (Input is
subject to the same all-in-memory limit as output, §3.7; streaming input is part
of the later streaming enhancement, §3.10.)

**Zero-copy external textures are deliberately not the first step.** They need a
new binding kind (`texture_external` + a DSL marker beside `FragmentPanel`),
per-frame bind group rebuilds, no mips, and are missing on Firefox, node-webgpu
and Bun's PR. Copy-per-frame costs one GPU copy per frame — negligible below 4K.
Add `p.videoTexture(video)` as an opt-in binding later, only if a profile asks
for it; the panel path stays.

### 4.5 Offline bake → static asset → sketch

The workflow this milestone enables, replacing init-time bakes where startup
time matters:

1. Offline entry runs the same `TextureBaker` code on a painter created on a
   `PixelImage` of the bake's size, and `p.show(panel)`s it (same size, so
   exact, §3.4). The host script writes `img.data` as PNG (`rgba8unorm`) or KTX2
   / raw (`rgba16float`) into the sketch's `assets/`. The same `PixelImage` can
   also go straight back in as a texture `source`, with no file in between. Mip
   chains are regenerated on load (`mips = true`), not exported (§3.4).
2. The sketch loads it:
   `WebImages.load("assets/wall.png").map(img => p.panel(source = img, mips = true))`;
   a float asset via `fetch` → `arrayBuffer` → `PixelImage` (raw) or a KTX2
   parse.
3. A bake-or-load switch is consumer-side code (`sketchlib`), proposed
   separately — not extracted as part of this work.

---

## 5. WebGPU facade additions

`webgpu.scala` gains, all as plain `@js.native` members:

- `GPUAdapter.info: GPUAdapterInfo` (`vendor`, `architecture`, `device`,
  `description`), `features`, `limits`
- `GPUDevice.features`, for the fail-fast `float32-filterable` check in
  `create(target, device)` (§3.4)
- `GPUDevice.destroy()`, `lost: js.Promise[js.Dynamic]`, `importExternalTexture`
  (later)
- `GPUQueue.writeTexture`, `copyExternalImageToTexture`, `onSubmittedWorkDone`
- `GPUCommandEncoder.copyTextureToBuffer`, `copyTextureToTexture`
- `GPUBuffer.mapAsync`, `getMappedRange`, `unmap`, `size`
- `GPUTexture.width/height/format`
- `object GPUMapMode { val READ = 1; val WRITE = 2 }`
- `PowerPreference` opaque type (`"high-performance"`, `"low-power"`)
- `OffscreenCanvas`, `VideoFrame` facades if scalajs-dom 2.8 lacks them (types
  only)
- `Float16Array` (`@js.native @JSGlobal`, §3.5); Scala.js 1.22 has none. Not
  WebGPU, but it lands with the same milestone.
- Later, with `WebVideo`: `requestVideoFrameCallback` /
  `cancelVideoFrameCallback` on `HTMLVideoElement`

---

## 6. Phasing

Each step lands independently and leaves `examples/` and the sketches working.

### Milestone 1 — headless painter ✅ done, code reviewed (PR #3)

**M1.0 — host seam.** ✅ verified in a desktop browser by hand.

- `Surface` + `CanvasSurface`.
- `Painter.create(surface, gpu | device)`, which takes `Surface` targets only.
  Containers come in M1.1.
- `WebGPU.requestAdapter` / `requestDevice`, and the `create` options
  `powerPreference`, `forceFallbackAdapter`, `requiredFeatures`,
  `requiredLimits`.
- `Painter.init(canvas)` rebased on `create`, with unchanged behavior.
- Facade additions (§5).
- _Proof:_ no behavior change; all examples render.

**M1.0b — shared blit (§3.4).** ✅ verified in a desktop browser by hand.

- A shared `blitInto` (sampled blit), with a per-format pipeline cache shared
  with mip generation (`blitPipelineFor`), and the blit bind group cached on
  `TextureViewBundle`.
- Canvas `show` switched over to it, and the perf TODO removed.
- _Proof:_ browser only; all examples render identically.

**M1.1 — containers and readback (§3.3–3.5).** ✅ verified on Node and Bun.

- `PixelFrames` / `PixelImage` / `PixelFormat` + `.d.ts`, and the `Float16Array`
  facade.
- `create` accepts containers through the internal `CaptureSurface`. It fails
  fast for an `rgba32float` container without `float32-filterable`.
- `show` readback: `blitInto` a capture texture, then a buffer copy, through the
  internal `read`. Then `p.awaitReadback()`.
- Adapter control: `requireAdapter`, `WebGPU.adapterNamed`, `logAdapter`, and
  `adapterInfo` on the painter.
- _Proof:_ `examples/headless-triangle/` with a node-webgpu driver, including
  the `--backend` / `--adapter` / `--software` flags and the `list-adapters`
  mode. It runs on Node, and the same driver is tried on Bun; if Bun can't load
  the addon, Node alone is the target. The TS driver writes a PNG from a
  `PixelImage` target (`rgba8unorm`), and a 16-bit output (`rgba16float`). All
  later headless proofs (M1.2, M1.2b, M1.3, M2) use the same setup.

**M1.2 — motion sequences (§3.7, §3.8).** ✅ verified on Node and Bun.

- An example with a fixed-`tpf` loop + `awaitReadback()`. A host script encodes
  mp4 / gif via ffmpeg.
- _Proof:_ the headless example writes an mp4 and a gif.

**M1.2b — ad-hoc targets (§3.9).** ✅ verified offline and in a desktop browser
by hand.

- `p.showTo(panel, into)`, `p.atSize(w, h)(body)`.
- _Proof:_ a browser example whose "4K screenshot" button downloads a PNG while
  the canvas keeps rendering, and an offline example that writes a second,
  smaller preview image.

**M1.3 — GPU tests and process lifecycle.** ✅ verified on Node and Bun.

- munit GPU integration tests. `scala-cli test` already runs on Node, so with
  `webgpu` installed a test can render and read back (lavapipe in CI).
- Verify the process lifecycle claimed in §1.1:
  - whether node-webgpu keeps the process alive while the `GPU` is referenced,
    so that a driver must end with `process.exit(0)`;
  - whether exiting right after the last readback is safe, with GPU memory
    reclaimed and no driver errors;
  - the same on Bun, if the drivers run there.

  Only verified behavior goes into the setup guide (§6.1).

- _Proof:_ pixel assertions on a known render, and a recorded lifecycle result
  per runtime.

**M1.4 — setup guide (§6.1).** ✅ written. Written once M1.0–M1.2b have landed,
against the shipped API.

- `docs/guide/painter-setup-guide.md`.
- _Proof:_ every snippet in it compiles / runs, and the examples it cites exist.

### Milestone 2 — texture sources

**M2.0a — unified panel allocation (§4.2, "Allocation").** ✅ implemented,
headless-tested; browser check pending.

- Extract `reallocate(w, h)` from `ensureSize`. `set` raises `needsRealloc` when
  a property that affects allocation changes: formats, MSAA, depth, mips, pong
  need, or the usage set (checked as coverage).
- It fixes today's bug, where those changes after the first paint are ignored
  and the first pong layer crashes. It is also the mechanism M2.0 builds on.
- While in `panel.scala`: drop the `_` prefix from its private fields
  (`_textures`, `_width`, `_depthView`, …) and from `_panelIdSeq`.
- _Proof:_ an example that toggles a pong layer and MSAA at runtime. All
  examples render unchanged.

**M2.0 — static sources, the core (§4.1, §4.2).** The asset case. ✅
implemented, headless-tested; browser check pending.

- `TextureSource`, `PixelImage` + external-image upload, with `flipY` and
  `premultipliedAlpha`.
- `source` on panels. Uploads happen only on `set`.
- Size resolution: explicit size, the source's intrinsic size, and the fallback
  to tracking the surface after unsetting.
- The direct path without layers: an eager upload into slot 0, then mips.
- `set` throws for a source on an MRT panel.
- _Proof:_ examples with a raw-buffer panel and an image panel, sampled
  downstream. Close the `with_static_texture_data` entry in
  `independent-todos.md`.

**M2.0b — sources in composition (§4.2).** The contributor stack. ✅
implemented, headless-tested; browser check pending.

- The drawn path: the source as the first draw of the shape pass. It needs a
  depth mode in the pipeline key, and supports `sourceBlend` and MSAA.
- `followSurface` and scaling by panel size.
- ~~The layered direct path, which starts each paint with a
  `copyTextureToTexture` into slot 0.~~ Dropped: layers take the drawn path
  (§4.2, "Paths").
- Path switching that trades the textures instead of re-uploading.
- _Proof:_ examples with an image background under shapes (with depth), an image
  with a blur / grade layer stack, and a `followSurface` background.

**M2.1 — loading (§4.3).** ✅ implemented; the offline roundtrip runs, the
browser check is pending.

- `WebImages.load`.
- _Proof:_ a browser example with a loaded image panel, and an offline driver
  that decodes a PNG it wrote and feeds it back in as a `PixelImage` (decoded by
  the helpers' own `decodePng` over `node:zlib`, not sharp, so `webgpu` stays
  the drivers' only dependency).
- _Proof, roundtrip:_ `headless_triangle` grows into the full §4.5 workflow. Its
  committed `assets/pre-rendered-triangle.png` is loaded by a second Scala entry
  and an `index.html` in the same folder, with `WebImages.load`, and drawn as a
  sprite texture. `build-dist.ts` already copies an example's `assets/`.
- _Docs:_ setup guide §1.5 ("Bake to an asset") gets a short load snippet — the
  baked file loaded back as a panel texture with `WebImages.load` (PR #3
  review).

**~~M2.2 — offline video input (§4.4).~~** Dropped: it adds no functionality of
its own. Feeding decoded frames back in is manual —
`panel.set(source = frames.pixelsAt(i))` per frame — with `pixelsAt` added to
M2.0 as a `PixelFrames` extension, so the container keeps its plain data shape.
An offline video-processing example can follow whenever one is wanted.

### Later — follow-ups, not in this plan

Each one is designed far enough that it adds to this plan without changing it:

- Frame streaming (§3.10).
- A public `read`, e.g. for MRT slots (§3.4).
- A second texture set per panel for repeated `atSize` captures (§3.9).
- Fit modes for sources (§4.2).
- `WebVideo.autoUpload` (§4.3). If it is still unneeded after M2, it moves to
  `independent-todos.md`.
- An external-texture binding (§4.4).
- A Scala-side codec layer (§1.4).

Related, tracked elsewhere:

- In `independent-todos.md`: MRT with one generative layer, and explicit
  GPU-resource freeing (`Painter.destroy()`).
- Native-window surfaces and frame scheduling, in
  [non-browser-hosts-plan.md](non-browser-hosts-plan.md).
- The bake-or-load switch, consumer side (§4.5).

### 6.1 Deliverable: painter setup guide

A standalone guide, `docs/guide/painter-setup-guide.md`, next to
`sketch-authoring-guide.md` and `shader-dsl-guide.md`. It is kept separate
because browser sketches need none of it: `Painter.init(canvas)` stays covered
by the sketch authoring guide, which only links to this one. Write it after
M1.0–M1.2b land, against the real API rather than this plan; this plan is not
its source of truth.

Contents:

- **Use cases first**, each with a minimal working setup. Browser canvas (the
  default), offline still, offline motion sequence, bake to asset, screenshot
  next to canvas rendering (`showTo`, `atSize`), and a painter on a device the
  user already has (`Painter.create(target, device)`).
- **Init API reference.** `Painter.init` vs `Painter.create`, every option
  (`powerPreference`, `forceFallbackAdapter`, `requireAdapter`,
  `requiredFeatures`, `requiredLimits`, `logAdapter`), the
  `WebGPU.requestAdapter` / `requestDevice` split, and what the painter owns.
  Feature-dependent setups, first of all `rgba32float` output, which needs
  `requiredFeatures = Arr("float32-filterable")`.
- **Getting a `GPU` per runtime.** Node (`webgpu` `create([...])`), Bun (the
  same, if verified), and the browser, with driver scripts in TS and the
  Scala-only variant.
- **Adapter selection workflows.** A worked guide built from §1.2 / §3.3.
  Listing adapters, then picking by backend, by name, or by software fallback.
  The Linux layer below WebGPU (`VK_DRIVER_FILES`, `MESA_VK_DEVICE_SELECT`,
  PRIME offload env vars). Verifying the choice with `requireAdapter` /
  `logAdapter`. A troubleshooting table: wrong GPU picked, no adapter, lavapipe
  in CI.
- **`PixelFrames` / `PixelImage`.** The contract, the two formats, the layout,
  the `awaitReadback()` semantics, the memory limits of the simple container,
  and host-side processing recipes (PNG, ffmpeg mp4 / gif, `ImageData` in the
  browser).
- **Process lifecycle.** Only what M1.3 verified: whether and when a driver must
  call `process.exit`, and a pointer to the deferred `destroy()` for
  long-running processes.

Native-window surfaces are not a phase of this plan — see
[non-browser-hosts-plan.md](non-browser-hosts-plan.md).

Examples are browser-served today; headless examples carry their own driver
scripts and are run, not deployed.

---

## 7. To verify at implementation time

- ✅ **Bun + node-webgpu** (checked in M1.1, webgpu 0.6.2, Bun 1.4.2, Node
  24.16): Bun loads the addon. `create()`, `mapAsync`, and the `rgba8unorm` /
  `rgba16float` / `rgba32float` readbacks give identical results on both
  runtimes. So the drivers run on Node and Bun.
- ✅ **Process exit** (verified in M1.3): exits on its own on Node and Bun,
  without `process.exit`, also with the `GPU` held globally; `process.exit(0)`
  right after the readback is clean. That contradicts the §1.1 claim, which is
  corrected. The `GPU` must stay referenced while the device works (§9, M1.3).
- **Software adapter** (observed in M1.1): `forceFallbackAdapter: true` returned
  no adapter, or on a reused `GPU` the hardware one. Dawn in node-webgpu 0.6.2
  on Linux ships no SwiftShader. Mesa's llvmpipe is listed as a Vulkan adapter
  and renders identically when picked by name (`--adapter`).
- Bun native WebGPU: merge status of the PR stack, backend env var, whether
  `GPUAdapterInfo` is populated. Only relevant once it lands.
- `Float16Array`: Node 24.16 and Bun 1.4.2 are checked.
- `rgba32float` capture and readback on node-webgpu's backends (Vulkan, and the
  software fallback).
- ✅ `rgba16float` readback (M1.3 tests): exact on Intel Vulkan and on llvmpipe.
- ✅ A non-native JS trait (`trait PixelFrames extends js.Object`) accepts a
  plain TS object literal without wrapping, and frames pushed from Scala are
  visible to the TS caller (`headless_sequence`).
- Firefox `importExternalTexture` / `copyExternalImageToTexture(VideoFrame)`
  current status.
- `requestVideoFrameCallback`: confirm Baseline status in all target browsers,
  and that it fires after a seek while paused. Also confirm how the callback
  orders against `requestAnimationFrame`: it should run before the frame's rAF,
  and otherwise a frame of upload latency is acceptable.

## 8. Open decisions

- **Naming** — decided with the §3 review: `Surface`, `CaptureSurface`,
  `PixelFrames`, `PixelImage`, `PixelFormat`, `Painter.create`, `showTo`,
  `atSize`, `awaitReadback`.
- **Encoding** — decided: host side; no codec layer in trivalibs for now (§3.8).
  A Scala-side layer remains possible later.
- **Streaming** — decided: later enhancement, as an optional `sink` on the
  container (§3.10).
- **Sketch entry for offline renders** — deferred, user land in the consuming
  repo. Not decided here, and it may change with real usage. The starting intent
  is strict separation: an offline render is its own sketch folder, with its own
  code modules, a script with no `index.html`.
- **Source on an MRT panel** — decided: not allowed, `set` throws (§4.2).
- **Default size of a source panel** — decided: the source's intrinsic size, not
  sticky after unsetting, with `followSurface = true` as the opt-in to tracking
  (§4.2).

## 9. Implementation log

### 2026-10-24 — M1.0, M1.0b, M1.1

Implemented together, in one change, because M1.1's readback is built on M1.0b's
`blitInto`. Not committed yet.

**Landed:**

- `src/graphics/painter/surface.scala`: the `Surface` trait and `CanvasSurface`.
- `src/graphics/painter/pixels.scala`: `PixelFormat`, `PixelData`,
  `PixelFrames`, `PixelImage`, `PixelTarget`, and the `Float16Array` facade.
- `src/graphics/painter/readback.scala`: `Readback` (capture textures, staging
  pool, read, `awaitAll`), the internal `CaptureSurface`, and a `WeakIntMap`
  facade.
- `src/graphics/painter/painter.scala`:
  - the constructor is now `(device, surface)`;
  - `create` ×2 and the internal `setup` / `build`;
  - `adapterInfo`, `awaitReadback`, `blitInto` and `blitPipelineFor`;
  - `show`, `draw` and `paintPanel` go through the surface.
- `src/graphics/painter/panel.scala`: `TextureViewBundle.blitGroup`.
- `src/graphics/painter/webgpu.scala`: the facade additions, `GPUMapMode`,
  `PowerPreference`, and `WebGPU.requestAdapter` / `requestDevice` /
  `adapterNamed` / `describe`.
- `examples/headless_triangle/`: `HeadlessTriangle.scala`, `pixels.d.ts`, and
  `render.node.ts`.
- `package.json`: the dev dependency `webgpu` 0.6.2. `.gitignore`: the example's
  `out/`.

**Verified:**

- `bun run check`, clean.
- All 22 sketches of the consuming repo compile against the change (compile
  only, so no checked-in `main.js` was rebuilt).
- The headless example on Node 24.16 and Bun 1.4.2 gives identical results:
  - an `rgba8unorm` PNG (top row first, correct orientation);
  - `rgba16float` keeps values above 1 (max 3.97 at intensity 4);
  - `rgba32float` works with `float32-filterable`;
  - `PixelFrames` gets 3 distinct frames in `show` order;
  - a `PixelImage` keeps the last of 5 in-flight `show`s;
  - a wrong array type, a wrong length and an unknown format are each rejected
    at `create`.
- Adapter flags:
  - `--list-adapters` relays Dawn's list (Intel Vulkan, llvmpipe);
  - `--require nvidia` rejects with the adapter info;
  - `--require intel` and `--backend vulkan` work;
  - `--backend opengles` gives no adapter here;
  - llvmpipe renders identically when picked with `--adapter`.
- Browser, in headless Chromium with WebGPU, against a baseline built from
  `git archive HEAD`:
  - all 19 examples load with no console errors;
  - every deterministic example is pixel-identical (blur, deferred, mipmaps,
    panel\_\*, painter\_\*, sdf_trace, noise_tests, simple_triangle);
  - the animated and random ones differ even baseline against baseline, so they
    are inconclusive;
  - `noise_cpu_gpu` sometimes stays blank in headless Chromium, in both builds.
- **Not exercised:** the `rgba32float` fail-fast without the feature. The test
  GPU supports `float32-filterable`, and the example requests it.

**Deviations from the plan** (§3 updated where marked):

1. **`Surface.present` submits.** It finishes and submits the frame's encoder;
   `CanvasSurface.present` is just the submit. The capture has to submit before
   `mapAsync`, so the surface owns the end of the frame. (§3.1)
2. **`Painter.init` doesn't call the union-typed `create`.** Both go through a
   private `setup(surface, gpu)` and `build(surface, device)`, so a browser
   bundle never links `CaptureSurface`. The plan described `init` as sugar over
   `create`; the behavior is the same.
3. **`CanvasSurface`** has a private constructor and
   `CanvasSurface(canvas, format = …)`. A constructor parameter named `format`
   would clash with the `format` member. (§3.1)
   - The default format is `navigator.gpu.getPreferredCanvasFormat()`, or
     `bgra8unorm` when that is missing.
   - It takes an `HTMLCanvasElement` only, not
     `HTMLCanvasElement | OffscreenCanvas`.
4. **`p.canvas` stays as an accessor**, since the guides attach DOM listeners to
   it. It throws on a painter without a `CanvasSurface`. `p.context` is removed.
   (§3.2)
5. **`onResize` fires with the integer canvas size.** Before, it fired with the
   observer's fractional CSS size. (§3.1)
6. **The `Painter` constructor is `private[painter] (device, surface)`.**
   `queue` and `preferredFormat` are now derived vals.
7. **The phasing was collapsed.** M1.0's "Surface targets only" didn't happen:
   `create` accepts containers from the start, because M1.0–M1.1 landed
   together.
8. **The blit uses `loadOp: "clear"`**, where `show` used `"load"`. The output
   is the same, because the full-screen triangle with no blending writes every
   pixel.
9. **The blit's shader module and pipeline layout are created once.** The old
   mip cache created both again for every format. `getMipBlitPipeline` and the
   `lazy val blitPipeline` are gone; `blitPipelineFor` is used by both `show`
   and mip generation.
10. **Readback ownership.** The plan had `CaptureSurface` own its
    capture-texture entry. Instead a separate `Readback` class owns the
    capture-texture cache (per size and format) and the staging pool.
    - `create` builds one for a container painter and passes it to both the
      surface and the painter.
    - Any other painter creates one lazily (for `showTo`, M1.2b).
11. **Readback ordering mechanics**, which the plan left open:
    - a `PixelFrames` reserves its frame slot (a `null` placeholder) at submit
      time;
    - a `PixelImage` keeps its last-written submission index in a `WeakMap`;
    - `awaitAll` waits for a snapshot of the pending reads, then repeats until
      none are left. That also covers reads submitted while waiting.
12. **`Float16Array` facade.** It lives in `trivalibs.graphics.painter`, not in
    `utils`. Its primary constructor is `(length: Int)`: Scala.js rejects a
    private constructor on a native class, and `private[this]` is deprecated.
13. **Example details:**
    - the render function is
      `render(gpu, out, frameCount, intensity, software, requireName)`;
    - the driver uses a small PNG encoder (8 and 16 bit) over `node:zlib`, in
      place of pngjs, so `webgpu` is the only dependency. It lives in
      `examples/example_helpers/png.ts`, so the driver shows only library usage;
    - the 16-bit output is a PNG clamped to `[0,1]`;
    - the PNGs go to the example's committed `assets/`
      (`pre-rendered-triangle.png`, `pre-rendered-triangle-16.png`), the
      examples' convention for runtime-loaded files, ready for the M2.1
      roundtrip. That replaces the gitignored `out/` of the first version;
    - one driver, `render.node.ts`, serves Node and Bun;
    - `pixels.d.ts` lives in the example folder (moved to
      `examples/example_helpers/` in M1.2, once a second driver used it).
14. **Facade scope.** Not added: `OffscreenCanvas` and `VideoFrame` (M2),
    `importExternalTexture`, and `requestVideoFrameCallback` (later).
    `writeTexture`, `copyExternalImageToTexture` and `copyTextureToTexture` are
    added now, unused until M2.

**Findings:**

- Bun loads node-webgpu's Node-API addon, so the drivers run on both runtimes
  (§7).
- The headless process exits on its own, without `process.exit`, on Node and
  Bun. That contradicts §1.1. It is verified in M1.3 before it goes into the
  guide.
- `forceFallbackAdapter: true` gives no adapter in node-webgpu 0.6.2 on Linux,
  since that build ships no SwiftShader. The software path is llvmpipe, picked
  by name (§3.3, §7).
- **Library trap:** `.get(x)` on a `js.Dynamic` resolves to trivalibs' `Opt.get`
  extension, because every value is an `Opt[A]`. It then calls the result as a
  function: `(0, this.l1)(target) is not a function`. It is worked around here
  with a typed facade (`WeakIntMap`). The fix at the cause is planned in
  [opt-maybe-extension-shadowing-plan.md](opt-maybe-extension-shadowing-plan.md).
- Shade creation logs every generated WGSL to the console (`log(wgsl)`, which
  predates this change). That is noisy in offline driver output. Leave it, or
  gate it, later.

**Closed (with M1.2–M1.4):** all browser examples were checked by hand in a
desktop browser: they work and render unchanged.

### 2026-10-24 — M1.2, M1.2b, M1.3, M1.4

Not committed yet.

**Landed:**

- `src/graphics/painter/painter.scala`:
  - `showTo(panel, into)` and `atSize(width, height)(body)`;
  - `paintPanel`, `width`, `height` and `onResize` follow the `atSize` override
    (`targetWidth` / `targetHeight`);
  - the constructor takes the `adapter` instead of its info; `adapterInfo` is
    derived from it;
  - `retainWithDevice` keeps the `GPU` and the adapter alive with the device.
- `src/graphics/painter/readback.scala`: the container validation is a shared
  `validatePixelTarget`, used by `create` and `showTo`.
- `src/prelude/painter.scala`: exports `PixelFormat`, `PixelData`,
  `PixelTarget`, `PixelFrames`, `PixelImage`, `Surface`, `CanvasSurface`,
  `WebGPU`, `PowerPreference`, and a `GPU` alias.
- `examples/headless_sequence/` (M1.2 + the offline M1.2b proof): a seamless
  48-frame loop at 24 fps, written as `assets/sequence.mp4` and `sequence.gif`,
  plus a 128×72 `preview.png` rendered with `atSize` + `showTo`.
- `examples/screenshot/` (M1.2b browser proof): a "4K screenshot" button that
  downloads a 3840×2160 PNG while the canvas keeps animating. Registered in
  `examples/index.html`.
- `examples/example_helpers/`: `ffmpeg.ts` (mp4 / gif from raw frames),
  `download.js` (browser PNG download), and `pixels.d.ts`, moved here from
  `headless_triangle/`. `build-dist.ts` copies the folder's `.js` files.
- `test/graphics/painter/HeadlessPainter.test.scala` (M1.3): 9 GPU tests.
- `docs/guide/painter-setup-guide.md` (M1.4), linked from the sketch authoring
  guide.
- The three headless / screenshot examples use the preludes.

**Verified:**

- `bun run check`, clean; all examples build; `bun run test` green.
- The 9 GPU tests pass on Intel Vulkan and on llvmpipe
  (`TRIVALIBS_WEBGPU_ADAPTER`): exact rgba8 clears, top-row-first orientation at
  pixel centers, rgba16float HDR values, the rgba32float fail-fast, frame order,
  last-show-wins, `showTo` resolving with its target (scaled), `showTo`
  validation, `atSize` sizes and `onResize` in / out.
- `headless_sequence` on Node and Bun: 48 frames, mp4 and gif with 48 frames
  each, a 128×72 preview.
- `screenshot`, in a desktop browser by hand: the PNG downloads while the canvas
  keeps rendering.
- Lifecycle, 10 runs each on Node 24.16 and Bun 1.4.2: `GPU` passed inline,
  `GPU` held globally, and `process.exit(0)` right after the render. All 60 exit
  with code 0 and no driver output.
- Every Scala snippet in the setup guide compiles; the Scala-only variant (§3 of
  the guide) runs on Node.

**Deviations from the plan** (§3 updated where marked):

1. **`atSize` returns its body's result** (`atSize[A](w, h)(body: => A): A`), so
   the `showTo` promise comes out of the scope. (§3.9)
2. **`showTo` needs no `float32-filterable` for an `rgba32float` target**: the
   capture texture is a core render target, and only an `rgba32float` panel
   needs the feature.
3. **The painter retains its `GPU` and adapter** as a JS property on the device.
   Not planned; see the findings.
4. **The preludes export the offline vocabulary**, including the native `GPU` as
   a type alias. The prelude comment says why `GPU`, `WebGPU` and
   `PowerPreference` are the exception to "no raw facades".
5. **M1.2b's offline proof lives in `headless_sequence`** (the preview image)
   rather than a separate example.

**Findings:**

- **node-webgpu crashes when the `GPU` object is collected mid-render.** Dawn's
  device does not keep its instance alive. Passing `create([])` inline aborted
  the process at 256 px and above (`futex`, `pthread_mutex_lock` assertion,
  `std::system_error`) or hung it. Holding the `GPU` in a variable avoided it,
  which is why the M1.1 driver never hit it. The painter now keeps the `GPU` and
  adapter alive with its device; after `create(target, device)` that is the
  caller's job (setup guide §6).
- **The Scala.js linker drops fields that are never read.** A first attempt held
  the adapter in a constructor `val`; the linked JS did not store it. The
  retention therefore writes a JS property on the device, which the linker
  keeps.
- **Headless Chromium cannot verify a canvas readback.** In headless Chromium
  155 (SwiftShader), once a device has presented one frame to a canvas, every
  `mapAsync` on it rejects with "A valid external Instance reference no longer
  exists". Plain WebGPU without the library does the same, with every flag
  combination tried. A container painter, and a device that only configured a
  canvas, read back fine. The screenshot example was therefore checked by hand
  in a desktop browser.
- The gif is the bulk of the committed assets (≈750 KB at 256×144, 48 frames,
  bayer dither); the mp4 is ≈30 KB.

### 2026-10-26 — PR #3 review

1. **Docs say "offscreen painter" and "pixel target"**, not "offline" or
   "container". This plan keeps "container" where it separates the target data
   from the surface that fills it.
2. **The painter holds `gpu` and `adapter`**, replacing the JS property on the
   device. Both are `@JSExport` constructor vals: an exported getter is a read
   the linker keeps (checked in the `full` link). The retention helper is gone;
   the factories call `new Painter(…)` then `painter.initializeSurface()`, which
   wires the surface (`Surface.configure` takes the finished painter).
3. **`CaptureSurface` lives in `surface.scala`** and owns its one capture
   texture; it gets the readback from `painter.ensureReadback()` during
   `configure`, so `createOn` builds nothing by hand. `readback.scala` is
   readback only.
4. **`showTo` takes `cache: Boolean = true`.** The default keeps the capture
   texture (cached on the painter) and staging buffer (pooled in `Readback`) per
   size and format for the painter's lifetime — the usual case, fixed export
   sizes. `cache = false` is the opt-in for freely varying sizes, where that
   cache would grow without bound: both are allocated for the call and destroyed
   once the pixels have landed (the texture right after submit).
   `freeCaptureCache()` is the other way out: it destroys every cached capture
   texture and idle staging buffer; in-flight reads hold theirs outside the pool
   and still land. `Dict` gained native `keys` / `delete` for it.
5. **`PixelData` sizes are pixel counts**; RGBA is fixed (`PixelData.Channels`).
   `copyRows` moved to `PixelData`, the offset view is private to it, and
   `rgba8unorm` is the default branch. Validation is `PixelTarget.validate`.
6. **Class layout convention** (trivalibs `CLAUDE.md`): fields first, then
   constructor statements, then defs public-first; `this` escapes only via a
   setup def the factory calls after `new`. `Painter`: fields, then content
   sections — device and surface, size (`width`/`height` replace
   `targetWidth`/`Height`), readback, samplers, factories, rendering API, and
   the private rendering internals last.
7. **`create` folded into `init`.** Four overloads: `init(canvas)`,
   `init(canvas)(setup)`, `init(target, gpu, …)` (the only one with defaults, as
   Scala requires — hence no `(setup)` form for it) and `init(target, device)`.
   `target` is `PixelTarget | HTMLCanvasElement | Surface`: a `Surface` by type
   test, a pixel target by its `frames` / `data` field, anything else as a
   canvas — never `instanceof HTMLCanvasElement`, which throws where that global
   is missing and rejects NativeScript canvases. A raw canvas gets the `gpu`'s
   preferred format. `WebGPU.getGPU()` checks for `navigator` first, so
   `init(canvas)` and a device-only canvas painter no longer throw a
   `ReferenceError` outside browsers.
8. **Companion cleanup.** `setup` is inlined into `init(canvas)`; `make` is
   `createPainter`, and the post-construction hook `start()` is
   `initializeSurface()`.
9. **`PixelTarget.validate` is public** (`where` defaults to
   `"PixelTarget.validate"`), for targets built or received outside the painter,
   and the prelude exports the `PixelTarget` object.
10. **Work-binding fields keep their place**, renamed without the `_` prefix;
    the merge helpers read them directly instead of taking them as parameters.
    Resolving bindings per draw without them is its own plan,
    [binding-resolution-plan.md](binding-resolution-plan.md). The `_` prefixes
    in `panel.scala` go with M2.0a.

**Review closed: M1 done.**

### 2026-10-26 — M2.0a, M2.0, M2.0b, M2.1

Not committed yet. Headless tests (`HeadlessPainter.test.scala`, 12 new) cover
every path; the browser examples build but are not checked by hand yet.

1. **Allocation (M2.0a).** `Panel.ensureSize` resolves the size and calls a
   private `reallocate(w, h)` when the size, `needsRealloc` or the usage
   coverage changed. `set` compares an allocation key (formats — a source's
   default one when none are set —, MSAA, depth, mip setting, pong need) before
   and after. Usage is compared in `ensureSize`, as coverage. Turning depth off
   now frees the depth texture. The `_` prefixes in `panel.scala` are gone.
2. **Sources (M2.0, M2.0b)** live in `panel.scala` (state, paths, upload) and
   the new `sources.scala` (`ExternalImage`, `TextureSource`, the upload, the
   `VideoFrame` type facade, `WebImages`). The painter adds the source draw: the
   blit shader as an internal shade through `getPipeline`, whose key gained a
   depth mode (`depthAlways`: compare `always`, no depth writes).
3. **Deviations from §4:**
   - `PixelImage` dispatch checks `data` _and_ `format`: a `VideoFrame` has a
     `format` field (§4.1).
   - No layered direct path; layers take the drawn path (§4.2, "Paths").
   - `set(source = null)` on a panel without explicit `formats` pins the
     source's format, so the pixels survive the unset instead of being
     reallocated into the preferred format.
   - `writeTexture` gets the typed array's `ArrayBuffer` plus offset: Dawn
     (node-webgpu) rejects a `Float16Array` view.
   - An rgba32float source on the drawn path needs `float32-filterable` (a
     linear sampler), checked with a clear error; the direct path does not.
4. **`PixelFrames.pixelsAt(i)`** (extension in the companion) replaces M2.2.
5. **Examples:** `panel_realloc` (M2.0a), `texture_source` (M2.0: a `PixelImage`
   re-uploaded every second, a 2D canvas with mips), `source_composition`
   (M2.0b: background under depth-tested MSAA shapes, a blur + grade stack,
   `sourceBlend`; click to cycle), and `headless_triangle` with an `index.html`
   loading the baked PNG as a sprite texture (M2.1). Its `render.node.ts`
   decodes the PNG again and feeds it back in through a `roundtrip` export: byte
   for byte.
6. **Docs:** sketch authoring guide §2d (image as a panel source), setup guide
   §1.5 load snippet and §5.1 input direction. The `with_static_texture_data`
   todo and its port-comparison gap are closed.

## Sources

- node-webgpu: [repo](https://github.com/dawn-gpu/node-webgpu),
  [example.js](https://github.com/dawn-gpu/node-webgpu/blob/main/example/example.js)
- Deno:
  [`createImageBitmap`](https://docs.deno.com/api/web/~/createImageBitmap),
  [`@std/webgpu` createCapture](https://jsr.io/@std/webgpu/doc/create-capture/~/createCapture),
  `DENO_WEBGPU_BACKEND` in
  [ext/webgpu/lib.rs](https://github.com/denoland/deno/blob/main/ext/webgpu/lib.rs)
- Bun: [WebGPU PR #42768](https://github.com/oven-sh/bun/pull/42768),
  [`Bun.Image`](https://bun.com/docs/runtime/image),
  [`Bun.Image.raw()` PR #43623](https://github.com/oven-sh/bun/pull/43623),
  [bun-webgpu](https://github.com/kommander/bun-webgpu),
  [wgpu-polyfill](https://github.com/wiedymi/wgpu-polyfill)
- Video/images:
  [MDN `importExternalTexture`](https://developer.mozilla.org/en-US/docs/Web/API/GPUDevice/importExternalTexture),
  [MDN `copyExternalImageToTexture`](https://developer.mozilla.org/docs/Web/API/GPUQueue/copyExternalImageToTexture),
  [Chrome WebGPU 116 (WebCodecs)](https://developer.chrome.com/blog/new-in-webgpu-116),
  [Image loading on the web](https://www.ludicon.com/castano/blog/2026/05/image-loading-on-the-web/),
  [Firefox 141 WebGPU](https://linuxiac.com/webgpu-lands-in-firefox-141-on-windows-eyes-linux-and-macos-next/)
- Native-window sources (Deno desktop, NativeScript) are in
  [non-browser-hosts-plan.md](non-browser-hosts-plan.md).
