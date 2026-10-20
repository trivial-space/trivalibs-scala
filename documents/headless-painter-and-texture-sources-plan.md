# Headless painter, render-to-file, and texture sources

**Status: planned, not implemented.** Scope is **offline render to file**, not
on-screen rendering outside the browser. Takes over the host seam and the
headless phase of [non-browser-hosts-plan.md](non-browser-hosts-plan.md) (its
`RenderHost` becomes `Surface` here); native-window hosts (Deno windows,
NativeScript) stay a separate issue in that document, and §3.8 lists what this
API must keep compatible with them. Also absorbs the
`with_static_texture_data(bytes)` gap from
[independent-todos.md](independent-todos.md).

Two milestones:

1. **Headless painter + render to file.** The painter runs without a DOM, on any
   runtime that hands it a spec-conformant `GPU` object (node-webgpu, Deno, Bun
   once its WebGPU lands). Instead of a canvas it is given a plain data
   container: a `PixelFrames`, where every `show()` appends one frame, or a
   `PixelImage`, where every `show()` overwrites the single image. Panels can be
   read back into the same containers — the path for offline-baked static assets
   that replace today's init-time bakes (`src/utils/bake/Bake.scala` in the
   consuming repo). Encoding and file writing happen afterwards, in whatever the
   host prefers.
2. **Texture sources on panels.** A panel can hold uploaded pixels instead of
   rendered content: raw buffers, `ImageData`, `HTMLImageElement`,
   `ImageBitmap`, canvases, and — through the same interface — video
   (`HTMLVideoElement`, `VideoFrame`) in the browser and decoded frames
   headless.

The design rule running through both: **Scala describes, the host handles I/O.**
Scala's value is expressiveness in the graphics domain — scenes, math,
animation, shaders in custom DSLs. File handling, codecs and process plumbing
are ordinary host workflows, best written in the host's own language (a short TS
script on Node/Deno/Bun). So the boundary between the two is **plain JS data**:
objects of width, height, format and typed arrays, which either side can create
and read without importing anything from the other. Output is a `PixelFrames`
sequence or a `PixelImage` still. Input is a `PixelImage` (§3.4), plus the
browser's native image types as an input-only fast path where they exist.

---

## 1. Research

### 1.1 Runtimes

| Runtime          | How you get a `GPU`                                                   | Backend        | Canvas / surface                                                                                                                     | Image APIs                                                                                                          | Status                                                                                           |
| ---------------- | --------------------------------------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| **Node**         | `import { create } from 'webgpu'` → `create([...options])`            | Dawn           | none                                                                                                                                 | none (no `ImageBitmap`, no `copyExternalImageToTexture` sources)                                                    | npm `webgpu` 0.6.x, Node ≥ 18                                                                    |
| **Deno**         | `navigator.gpu` (with `--unstable-webgpu` / `"unstable": ["webgpu"]`) | wgpu           | `Deno.UnsafeWindowSurface` (`getContext("webgpu")`, explicit `present()`, `resize()`); desktop windows from Deno 2.9 `BrowserWindow` | `createImageBitmap(Blob \| ImageData \| ImageBitmap)`; formats + `copyExternalImageToTexture` support **to verify** | unstable flag                                                                                    |
| **Bun (future)** | `navigator.gpu` + `GPU*` globals, no flag                             | wgpu-core 30   | **none** ("there is no canvas")                                                                                                      | `GPUExternalTexture` / `copyExternalImageToTexture` explicitly out of scope                                         | PR [oven-sh/bun#42768](https://github.com/oven-sh/bun/pull/42768) open, being split into a stack |
| **Bun (today)**  | `bun-webgpu` (Dawn via FFI) or `wgpu-polyfill` (wgpu-native)          | Dawn / wgpu    | none                                                                                                                                 | `Bun.Image` decodes/encodes, but takes **encoded** input only (raw RGBA in/out is an open PR)                       | third-party                                                                                      |
| **NativeScript** | `navigator.gpu`                                                       | Metal / Vulkan | `canvas.getContext("webgpu")` — browser-shaped                                                                                       | asset images, to verify                                                                                             | `@nativescript/canvas` 2.0 webgpu prerelease                                                     |

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
- node-webgpu keeps the process alive while the `GPU` object returned by
  `create()` is referenced. For render-and-exit scripts the driver ends with
  `process.exit(0)` (`Deno.exit(0)`) once the render promise — file writes and
  encoder subprocesses included — has resolved; the OS and driver reclaim all
  GPU memory at process end. No library teardown needed for this milestone;
  explicit freeing (`Painter.destroy()` and per-resource `destroy()`) is
  deferred to long-running hosts, see
  [independent-todos.md](independent-todos.md) "GPU resources".
- **Deno windows and NativeScript are on-screen hosts** — a real surface with
  `getContext("webgpu")`, closer to the browser than to headless. Out of scope
  here; tracked in [non-browser-hosts-plan.md](non-browser-hosts-plan.md). The
  rows above are kept so the `Surface` shape is checked against them (§3.8).

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
  Both run on Node, Bun and Deno (npm compat). `Bun.Image` only accepts encoded
  input today (raw RGBA in/out is an open PR), so it can't take readback pixels
  directly yet.
- **Float16 / mip-chain assets:** **KTX2** (uncompressed) is the standard GPU
  texture container — any `GPUTextureFormat`, mip levels. `ktx-parse` (npm)
  reads and writes it in every runtime and in the browser. Raw `.bin` + known
  dimensions is the zero-dependency fallback.
- **Video / GIF:** `ffmpeg` as a subprocess, raw frames on stdin
  (`-f rawvideo -pix_fmt rgba -s WxH -r FPS -i -`). For 16-bit output use
  `rgba64le` (integer, so the host quantizes float frames first), or
  `gbrapf32le` for float input. Spawned via `node:child_process` (Node, Bun,
  Deno), `Bun.spawn` or `Deno.Command`. GIF via
  `split[a][b];[a]palettegen[p];[b][p]paletteuse` in one pass.
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
  /** End of one presented frame. Browser: no-op. Native window: present.
    * Capture: read the frame back. */
  def present(encoder: GPUCommandEncoder): Unit
  /** Resize notifications; never fires on fixed-size surfaces. */
  def onResize(cb: (Int, Int) => Unit): Unit
```

`present` takes the frame's encoder so a capture surface can append its
`copyTextureToBuffer` to the same submission as the blit.

Frame scheduling is **not** on the trait: offline renders are driven by a user
loop with a fixed `tpf` (§3.5), and the browser keeps `animate` as is.
Scheduling only becomes a surface concern for native windows (no
`requestAnimationFrame` in Deno's raw backend) — that plan adds it.

| Surface                           | `currentTexture`                               | `present`                                                                                         | `onResize`       | Phase |
| --------------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------- | ---------------- | ----- |
| `CanvasSurface(canvas)`           | `context.getCurrentTexture()`                  | no-op                                                                                             | `ResizeObserver` | M1.0  |
| `CaptureSurface` (internal, §3.4) | owned texture, `RENDER_ATTACHMENT \| COPY_SRC` | frames: copy → staging buffer → `mapAsync` → append; image: none (read back at `awaitReadback()`) | never            | M1.1  |

`CaptureSurface` is **not** user-facing: the user hands the painter a plain
`PixelFrames` or `PixelImage` container (§3.4) and the painter wraps it. The
readback machinery (staging buffers, pending `mapAsync`) lives in the wrapper,
so the container stays pure data.

`CanvasSurface` is today's `Painter.init` body moved verbatim: browser behavior
does not change. It is the only place that calls
`gpu.getPreferredCanvasFormat()` and `ResizeObserver`. Typed as
`HTMLCanvasElement | OffscreenCanvas`, duck-typed at runtime
(`getContext("webgpu")`).

**No auto-detection** (reasoning carried over from the host-seam design in
[non-browser-hosts-plan.md](non-browser-hosts-plan.md)): a detecting
`init(anything)` makes every surface reachable from every bundle and defeats
Scala.js DCE. Surfaces are constructed explicitly by the entry point; a browser
bundle links `CanvasSurface` only, an offline bundle links `CaptureSurface`
only. `Painter` references the trait. (Telling a container from a `Surface`
inside `create` is not detection in this sense — it only separates the arguments
that one method accepts.)

### 3.2 Painter init API

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
`navigator.gpu` read by user code in Deno/Bun, a Scala `@JSImport` in the user's
own entry file — is user land in every case. The browser gets the extended path
too, e.g.
`Painter.create(CanvasSurface(canvas), dom.window.navigator.gpu, powerPreference = PowerPreference.HighPerformance)`,
where the `navigator` read is again the caller's.

`Painter` gains `adapterInfo: Opt[GPUAdapterInfo]` (null when handed a device),
and `surface: Surface` (the internal `CaptureSurface` when given a
`PixelTarget`). `width` / `height` / `onResize` delegate to the surface;
`canvas` / `context` leave the constructor.

### 3.3 Adapter control, offline

Two layers, because selection splits across the `GPU` boundary (§1.2):

**Runtime level — in the driver, before `GPU` exists.** The driver script maps
two generic flags to the runtime's mechanism, so one command line means the same
thing everywhere:

| Flag                 | node-webgpu                                                             | Deno                                      | Bun (when native) |
| -------------------- | ----------------------------------------------------------------------- | ----------------------------------------- | ----------------- |
| `--backend vulkan`   | `create(["backend=vulkan"])`                                            | `DENO_WEBGPU_BACKEND=vulkan`              | to verify         |
| `--adapter <name>`   | `create(["adapter=<name>"])`                                            | via `requireAdapter` (below) + Vulkan env | same as Deno      |
| `--software`         | `forceFallbackAdapter = true`                                           | `forceFallbackAdapter = true`             | same              |
| `--vk-driver nvidia` | `VK_DRIVER_FILES=…/nvidia_icd.json` for all runtimes (set before spawn) |                                           |                   |

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
`--adapter`); on every runtime it also prints `info` for default,
`high-performance`, `low-power` and fallback requests. That is as close to
enumeration as the spec allows. On Node `--adapter` is exact (Dawn picks by
name); on Deno/Bun it is a `requireAdapter` check after the fact, so steering
there is `--backend` + the Vulkan env vars.

### 3.4 The data boundary: `PixelFrames` and `PixelImage`

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
interfaces ships next to the offline examples for TS hosts.

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
and fits stills and bakes, where a downstream tool wants `float32` anyway (sharp
raw `float` input, most EXR writers) or the data needs full precision.

GPU-side notes:

- A `rgba32float` capture texture is a core render target. The blit needs no
  blending, so no `float32-blendable`.
- As a texture **source** (§4), `rgba32float` is unfilterable without
  `float32-filterable`. A filtered panel should use `rgba16float`, or request
  that feature.

`PixelFormat` is an opaque `String` type in Scala with these three values.

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

**Capture semantics** (the internal `CaptureSurface` wrapping the container):

- **`show` is the only public readback mechanism** (plus `showTo`, its variant
  for an extra target, §3.10). For a `PixelFrames`, each `show(panel)` reads
  back, so **one frame is appended**. Several `show` calls accumulate several
  frames, and nothing else appends. Every frame has the container's `width` ×
  `height`. `draw()` output is not captured: draw into a panel and `show` it.
  Other readback APIs (e.g. MRT slots) are added later, only if really needed.
- **No mip-level extraction**, deliberately. Mips differ in size, so they fit
  neither one `PixelFrames` nor an upscaled frame. To export one, sample it
  through a fixed-size intermediate panel (`panel.binding(mipLevel = k)`, size
  `w >> k` × `h >> k`) and `showTo` it into a `PixelImage` of that size, which
  is an exact copy.
- **Exact when the size matches.** If the panel's size equals the container's,
  `show` copies 1:1: a direct `copyTextureToBuffer` when the formats match, else
  the `textureLoad` conversion pass below. Nothing is resampled, so this is the
  bake path. Only a size mismatch goes through the sampled, scaling blit. That
  is the same routine the canvas `show` uses after this feature (§3.11, which
  closes its sampler-free 1:1 perf TODO). The copy path is a package-private
  `read`.
- **`PixelImage` target: the last `show` wins.** `show` only writes the capture
  texture (copy or blit); the single readback happens at `awaitReadback()`. So
  repeated `show` calls cost no extra readbacks or staging buffers. The result
  is written **in place** (`data.set(…)`): the reference stays valid, and a host
  buffer can be the target directly (e.g. pngjs's `png.data`). `data` must
  already have the right type and length, checked at `create`, which throws
  otherwise. A host that wants to keep an earlier result across readbacks copies
  it.
- Readback is asynchronous (`mapAsync`), so the container fills in after the
  fact. `p.awaitReadback(): js.Promise[Unit]` resolves once every pending
  readback has landed — **the container is complete only after
  `awaitReadback()`**. Until then its contents are undefined. `awaitReadback`
  returns no container: the caller already holds its own. Frames land at their
  submission index, so order is the order of `show` calls.
- `awaitReadback` is only for readback. A canvas painter needs it only after
  `showTo`; with nothing pending it resolves immediately. Its scaladoc says so,
  so that it doesn't read as a step of normal rendering.
- Per readback: staging buffer with `bytesPerRow = align(width · bpp, 256)`,
  `copyTextureToBuffer` in the same submission as the blit (or at
  `awaitReadback` for an image), `mapAsync(READ)`, unpad into a fresh typed
  array (frames) or into `data` (image), `unmap`, buffer back to a pool. All of
  this is painter code, since WebGPU yields no keepable array.
  `getMappedRange()` is a temporary view of mapped memory and is **detached on
  `unmap()`**, so the copy out is required. The unpadding (one `set` per row, or
  one copy when `width · bpp` is a multiple of 256) rides along in the same
  pass.
- The capture texture is the container's format, so frames are already RGBA — no
  swizzle. The display path writes raw (non-sRGB) values exactly as the browser
  canvas does, so an `rgba8unorm` capture looks like the browser frame.

**Format conversion.** Any panel format can be shown into any container format.
GPU copies never convert: `copyTextureToBuffer` copies texel bytes as they are,
and `copyTextureToTexture` allows only formats that differ by `-srgb`. So
conversion is the painter's job:

- Same format → direct `copyTextureToBuffer`.
- Different format → one conversion pass first, into a scratch texture in the
  container's format, then the copy. The pass uses `textureLoad`, 1:1 with no
  sampler, so it is exact, and unfilterable `rgba32float` panels need no
  feature.
- Precision follows the target:
  - Float ↔ float never clamps: HDR values outside `[0,1]` survive both readback
    and upload. The only limits are f16's own: overflow beyond ±65504 to ±inf,
    about 3 significant digits, and flush to 0 below ~6e-8. HDR needs every
    panel in the chain to be float, because an 8-bit intermediate clamps. Panels
    without explicit formats use the surface format: float when the painter is
    created on a float container, but `bgra8unorm` on a canvas painter. So an
    HDR `showTo` from a canvas painter needs explicit float panel formats.
  - f16 → f32 is lossless.
  - `rgba8unorm` → f16 / f32 is lossless: all 256 steps survive, and reading
    back to 8-bit gives the same bytes.
  - f32 → f16 rounds, and values beyond ±65504 become ±inf.
  - Any float → `rgba8unorm` clamps to `[0,1]` and rounds to the nearest of the
    256 steps, with no tonemapping. NaN is implementation-defined.
- `bgra8unorm` panels need nothing extra: `textureLoad` returns RGBA order.
- `-srgb` panel formats decode to linear in the conversion pass. Shown into
  `rgba8unorm`, they come out as linear values, not the stored bytes. A
  same-format copy keeps the encoded bytes.
- Channels the panel lacks are filled as `(·, 0, 0, 1)`, e.g. `r32float` reads
  as `(r, 0, 0, 1)`.
- Integer panel formats (`*uint` / `*sint`) would need an integer pipeline: out
  of scope, and `show` throws on them.
- Size mismatch with an unfilterable panel (`rgba32float` without
  `float32-filterable`): the scaling blit falls back to a non-filtering
  (nearest) sampler.

The direct copy needs `COPY_SRC` on panel color textures: add it unconditionally
in `ensureSize` (no measurable cost). Everything here works in the browser too,
via `showTo` (e.g. a dev "download bake" button), since the container is plain
data. Depth is not readable (`depth24plus` is not copyable) — out of scope.

### 3.5 Motion sequences: a user loop with fixed `tpf`

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
(§3.9); the container API is shaped so it can be added without changing this
one.

`p.animate` / a surface-driven frame loop is deferred to the native-window plan,
where the host (not the script) owns the frame rate.

### 3.6 Processing the frames — host side

After `awaitReadback()`, the container is ordinary JS data, processed by
whatever the user prefers. The intended default is a short host script (§1.4 for
the tooling); trivalibs ships no codec or file layer in this plan.

```ts
// PNG still, Node/Bun/Deno — pngjs. The PNG's own buffer is the target, so
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

### 3.7 End-to-end shape

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
    p.show(wall) // same size as `out` → exact copy
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
// … encode out.frames (§3.6) …
process.exit(0) // Dawn keeps the event loop alive while the GPU is referenced
```

```ts
// deno:  DENO_WEBGPU_BACKEND=vulkan deno run --unstable-webgpu -A render.deno.ts
import { render } from './render.js'
const out = { width: 1920, height: 1080, format: 'rgba8unorm', frames: [] }
await render(navigator.gpu, out)
// … encode …
Deno.exit(0)
```

```ts
// bun, once native WebGPU ships: identical to the Deno driver.
// today: bun-webgpu / wgpu-polyfill provide the GPU object (API to verify).
```

Doing it all in Scala instead (create the container in Scala, encode via an npm
package through `@JSImport`) is equally valid — the boundary does not force the
split, it only makes the TS split cheap.

The consuming repo grows a
`scripts/render.ts <path> [--runtime node|deno|bun] [--backend …] [--adapter …]`
that builds the offline entry with scala-cli and spawns the chosen runtime — a
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

### 3.8 Kept compatible: native-window surfaces (separate issue)

Not implemented here, but the `Surface` trait must admit them with at most one
**additive** change (frame scheduling, below) — anything beyond that means this
design was wrong:

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

### 3.9 Later: frame streaming (enhancement, not in this plan)

For long sequences the container gains an optional field instead of a new API —
so §3.4 stays valid unchanged:

```ts
interface PixelFrames {
  // …as before…
  sink?: WritableStream<PixelData> // if set: frames are written here, not kept
}
```

Web Streams exist in browsers, Node, Deno and Bun with built-in backpressure,
and every host can put one in front of ffmpeg's stdin
(`Writable.toWeb(proc.stdin)` on Node, `Deno.Command` stdin natively, a
three-line `WritableStream` over `Bun.spawn`'s sink). A host wanting to pull
instead uses a `TransformStream` and reads its readable side. The painter side
then needs a per-frame wait (`p.ready()`, resolving on `writer.ready`) so a loop
can bound memory — that is the part that requires a promise-chained loop in
Scala, and why it is deferred until a long render needs it.

### 3.10 Ad-hoc capture targets: `showTo` and `atSize`

The init-time target (canvas or container) stays the default for `show`. Any
painter, canvas-backed included, can also show a panel into a caller-supplied
container, e.g. for screenshots next to canvas rendering, a different
resolution, or a second format.

```scala
// on Painter
/** Blits `panel` into a capture texture of `into`'s size and format, then
  * appends one frame (PixelFrames) or overwrites `data` (PixelImage).
  * Resolves with `into` once the pixels have landed. */
def showTo[T <: PixelTarget](panel: Panel, into: T): js.Promise[T]

/** Runs `body` with the target size overridden: panels without their own
  * size use `width`×`height`, `p.width` / `p.height` report it, and `onResize`
  * callbacks fire with it. Restored after `body`. */
def atSize(width: Int, height: Int)(body: => Unit): Unit
```

**A distinct name, not a `show` overload.** The return type differs
(`js.Promise` vs `Unit`), and an overload would hide the asynchrony at the call
site.

`showTo` follows `show`'s rules (§3.4): an exact copy when the panel's size
matches `into`, a scaling blit otherwise, and format conversion either way.

Unlike `show` on an image target, every `showTo` reads back right away, since
each call is its own request with its own promise.

A screenshot at canvas resolution is just `p.showTo(final, shot)` after the
regular paint. At a different resolution:

```scala
val shot = PixelImage(3840, 2160)
p.atSize(shot.width, shot.height):
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
- The blit pipeline is cached per target format: the surface format,
  `rgba8unorm`, and `rgba16float`.
- `awaitReadback()` also waits for pending `showTo` frames.

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

### 3.11 One present path for canvas and capture (closes the `show` perf TODO)

`show` today always presents through a full-screen triangle that
`textureSample`s the panel (`painter.scala`, `show`, with a `TODO(perf, micro)`
for a sampler-free 1:1 path). This feature closes that TODO by giving canvas and
capture **one shared routine** that picks the cheapest exact strategy:

```scala
// package-private on Painter: write slot 0 of `panel` into `dst`
private[painter] def presentInto(
    encoder: GPUCommandEncoder,
    panel: Panel,
    dst: GPUTexture,          // canvas: getCurrentTexture(); capture: capture texture
    dstFormat: TextureFormat,
    dstCanCopy: Boolean,      // dst has COPY_DST
): Unit
```

| Condition                                                             | Strategy                                                             |
| --------------------------------------------------------------------- | -------------------------------------------------------------------- |
| same size, copy-compatible formats (equal, or differ only by `-srgb`) | `copyTextureToTexture`, no pass at all                               |
| same size, other formats                                              | **load blit**: `textureLoad(panel, vec2i(fragCoord.xy))`, no sampler |
| different size                                                        | **sampled blit**: today's pipeline (nearest sampler)                 |

- Both blits are cached per destination format: the canvas format, `rgba8unorm`,
  `rgba16float`, `rgba32float`. The load blit is also the §3.4 conversion pass,
  so there is a single conversion shader.
- **Canvas:** `CanvasSurface.configure` adds `COPY_DST` to the context `usage`
  (default is `RENDER_ATTACHMENT` only). Panels default to the canvas format
  (`preferredFormat`) and track its size, so the common
  `paint(...); show(final)` frame becomes a single `copyTextureToTexture`.
- **Capture:** same routine with the capture texture as `dst`, followed by
  `copyTextureToBuffer`. Shortcut for `PixelFrames` targets: when the copy
  strategy applies, `copyTextureToBuffer` reads **straight from the panel
  texture** and the capture texture is skipped. `PixelImage` targets still copy
  into the capture texture, because their readback is deferred to
  `awaitReadback()` and the panel may be repainted before then.
- The package-private `read` (§3.4) is this routine plus the buffer copy. No
  second implementation.
- MSAA panels need nothing extra: slot 0 is already the resolved single-sample
  texture.

Verify at implementation time:

- `COPY_DST` on the canvas context in Chrome, Firefox and Safari, and whether it
  costs anything there, e.g. swap-chain compression.
- That `getCurrentTexture()` accepts `copyTextureToTexture` as its only write in
  a frame.

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

Raw buffers enter as a `PixelImage` (§3.4) — `{ width, height, format, data }`,
the same exchange format as the output side, creatable in TS or Scala. It is the
equivalent of Rust's `with_static_texture_data(bytes)`, with size and format
explicit because bytes carry neither. One frame of a `PixelFrames` container
converts by pairing it with the container's size and format, so a frame read
back in one run can be fed straight back in.

Runtime dispatch is duck-typed on our contract: **an object with a `format`
field is a `PixelImage` → `writeTexture`; anything else →
`copyExternalImageToTexture`**. (`ImageData` also has `width`/`height`/`data`
but no `format`, so it takes the external path.) Never
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
p.panel(source = video, live = true)                  // re-upload on every paint
panel.set(source = nextFrame)                         // explicit re-upload
```

New `Panel.set` / `Painter.panel` params: `source: Maybe[TextureSource]`,
`live: Maybe[Boolean]`, `flipY: Maybe[Boolean]`,
`premultipliedAlpha: Maybe[Boolean]`.

Semantics:

- A source panel's size comes from the source (sets `specWidth/specHeight`), so
  it never tracks the surface. Format: `PixelImage.format`, else the panel's
  `format`, else **`Rgba8Unorm`** (not `preferredFormat`, which is `bgra8unorm`
  on many browsers and would make the asset path format-dependent).
- Texture usage adds `COPY_DST | RENDER_ATTACHMENT` (the latter is required by
  `copyExternalImageToTexture` and by mip generation).
- **Upload is eager on `set(source = …)`** — static panels are typically only
  sampled, never painted. Same size and format → reuse the texture; otherwise
  reallocate through the existing `ensureSize` path (bumps `bindEpoch`, so
  cached layer bind groups rebuild).
- `mips = true` → the existing `generateMipmaps` runs after each upload.
- `live = true` → `paint(panel)` re-uploads from the held source. Video elements
  are skipped while `readyState < 2` and when `currentTime` hasn't changed since
  the last upload. `VideoFrame`s are caller-owned: the caller `set`s each new
  frame and closes the old one.
- Shapes or layers on a source panel throw in `set` (the existing validation
  choke point). Post-processing goes in a second panel that samples it.
  Rationale: a pong layer on a static texture would compound on every paint;
  that semantic is not worth defining until a sketch needs it.
- Everything downstream is unchanged: a source panel is sampled exactly like a
  rendered one (`"tex" := panel`, `panel.binding(mipLevel = …)`), because it
  reuses `_textures` / `views` slot 0.

### 4.3 Loading

**Browser** — one small helper, since it is pure web API and every browser
sketch wants it:

```scala
// trivalibs.graphics.painter.WebImages
def load(url: String): js.Promise[dom.ImageBitmap]
// fetch → blob → createImageBitmap(blob, colorSpaceConversion = "none",
//                                       premultiplyAlpha = "none")
```

`colorSpaceConversion: "none"` and `premultiplyAlpha: "none"` are the defaults
on purpose: baked data textures must arrive bit-exact. A photo that should be
color-managed passes `colorManaged = true`.

**Offline** — host side, by the design rule: the host decodes (e.g.
`sharp(file).raw().ensureAlpha()` → `Uint8Array`, `ktx-parse` for KTX2) and
passes `PixelImage` objects into the exported Scala function. No `node:fs` in
the library, so nothing can leak into a Vite bundle.

### 4.4 Video, consistently

| Environment | Source                                                                                   | Path                                        |
| ----------- | ---------------------------------------------------------------------------------------- | ------------------------------------------- |
| Browser     | `HTMLVideoElement`, `live = true`                                                        | copy per frame                              |
| Browser     | WebCodecs `VideoFrame`, `panel.set(source = f)`                                          | copy per frame (Firefox: via `ImageBitmap`) |
| Offline     | host decodes (ffmpeg → raw RGBA) into a `PixelFrames`; Scala sets each frame as `source` | `writeTexture` per frame                    |

So video is "a source that changes every frame" — the same panel API as a still.
Offline, the same exchange format runs both ways: the host hands in a decoded
`PixelFrames`, Scala processes it through panels frame by frame and fills an
output `PixelFrames`, the host encodes it — no browser types involved. (Input is
subject to the same all-in-memory limit as output, §3.5; streaming input is part
of the later streaming enhancement, §3.9.)

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
   `PixelImage` of the bake's size, and `p.show(panel)`s it (exact copy, §3.4).
   The host script writes `img.data` as PNG (`rgba8unorm`) or KTX2 / raw
   (`rgba16float`) into the sketch's `assets/`. The same `PixelImage` can also
   go straight back in as a texture `source`, with no file in between. Mip
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

---

## 6. Phasing

Each step lands independently and leaves `examples/` and the sketches working.

| Step      | Content                                                                                                                                                                                                                       | Proof                                                                                                                                                              |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **M1.0**  | `Surface` + `CanvasSurface`, `Painter.create(target, gpu \| device)`, `WebGPU.requestAdapter` / `requestDevice`, `Painter.init(canvas)` rebased on `create` (unchanged behavior), facade additions                            | no behavior change; all examples render                                                                                                                            |
| **M1.0b** | shared `presentInto` (copy / load blit / sampled blit, per-format cache), `COPY_DST` on the canvas context; canvas `show` switched over (§3.11)                                                                               | browser only: all examples render identically; a same-size `show` issues no render pass (checked in a GPU capture)                                                 |
| **M1.1**  | `PixelFrames` / `PixelImage` / `PixelFormat` + `.d.ts`, internal `CaptureSurface`, `p.awaitReadback()`, `show` readback (exact 1:1 copy on size match, internal `read`), `COPY_SRC` on panels, adapter verification + logging | `examples/headless-triangle/` with Node and Deno drivers; the TS driver writes a PNG from a `PixelImage` target (`rgba8unorm`) and a 16-bit output (`rgba16float`) |
| **M1.2**  | motion-sequence example: fixed-`tpf` loop + `awaitReadback()`, host script encodes mp4 / gif via ffmpeg                                                                                                                       | headless example writes an mp4 and a gif                                                                                                                           |
| **M1.2b** | `p.showTo(panel, into)`, `p.atSize(w, h)(body)`, per-format blit pipeline cache (§3.10)                                                                                                                                       | browser example: a "4K screenshot" button downloads a PNG while the canvas keeps rendering; offline example writes a second, smaller preview image                 |
| **M1.3**  | munit GPU integration tests: `scala-cli test` already runs on Node, so with `webgpu` installed a test can render + read back (lavapipe in CI)                                                                                 | pixel assertions on a known render                                                                                                                                 |
| **M2.0**  | `TextureSource`, `source` / `live` / `flipY` / `premultipliedAlpha` on panels, `PixelImage` + external-image upload, mips after upload                                                                                        | example: raw-buffer panel, image panel; close the `independent-todos` entry                                                                                        |
| **M2.1**  | `WebImages.load`, video element `live` panel                                                                                                                                                                                  | example: video panel in browser; offline driver feeds back a PNG it wrote as `PixelImage`                                                                          |
| **M2.2**  | offline video input: host-decoded `PixelFrames` in, processed, `PixelFrames` out                                                                                                                                              | offline: video in → processed → video out                                                                                                                          |
| **M1.4**  | `docs/guide/painter-setup-guide.md` (§6.1), written against the shipped API                                                                                                                                                   | every snippet in it compiles / runs; the examples it cites exist                                                                                                   |
| later     | frame streaming (§3.9); external-texture binding                                                                                                                                                                              | —                                                                                                                                                                  |

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
- **Getting a `GPU` per runtime.** Node (`webgpu` `create([...])`), Deno
  (`navigator.gpu`, `--unstable-webgpu`), Bun, and the browser, with driver
  scripts in TS and the Scala-only variant.
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
- **Process lifecycle.** `process.exit` / `Deno.exit` after rendering, and a
  pointer to the deferred `destroy()` for long-running processes.

Native-window surfaces are not a phase of this plan — see
[non-browser-hosts-plan.md](non-browser-hosts-plan.md).

Examples are browser-served today; headless examples carry their own driver
scripts and are run, not deployed.

---

## 7. To verify at implementation time

- Deno: does `copyExternalImageToTexture` exist, and which formats does
  `createImageBitmap` decode? (Doesn't block anything — the `PixelImage` path is
  universal — but decides whether Deno can use `WebImages`.)
- Deno: current WebGPU flag requirement in the version in use; whether
  `navigator.gpu` needs `--unstable-webgpu` for headless scripts.
- Bun native WebGPU: merge status of the PR stack, backend env var, whether
  `GPUAdapterInfo` is populated.
- `bun-webgpu` / `wgpu-polyfill`: how each exposes the `GPU` object.
- `Float16Array` in Deno (V8 ≥ 13.5, so expected in Deno ≥ 2.3; not installed
  here). Node 24.16 and Bun 1.4.2 are checked.
- `rgba32float` capture and readback on all three backends.
- `rgba16float` readback: `copyTextureToBuffer` of `rgba16float` with 8-byte
  texels and 256-byte row alignment on all three backends.
- A non-native JS trait (`trait PixelFrames extends js.Object`) accepts a plain
  TS object literal at the call boundary without wrapping — confirm
  `frames.push` from Scala is visible to the TS caller (it should be: same array
  object).
- Firefox `importExternalTexture` / `copyExternalImageToTexture(VideoFrame)`
  current status.

## 8. Open decisions

- **Naming.** `Surface` / `CaptureSurface` / `PixelFrames` / `PixelImage` /
  `PixelFormat` / `Painter.create` are proposals. So are `showTo` (vs `showOn`)
  and `atSize` (vs `withSize`).
- **Encoding** — decided: host side; no codec layer in trivalibs for now (§3.6).
  A Scala-side layer remains possible later.
- **Streaming** — decided: later enhancement, as an optional `sink` on the
  container (§3.9).
- **Sketch entry for offline renders.** Separate offline entry file per sketch
  (proposed, keeps the deployed bundle clean) vs. a second export in the
  sketch's `main.js`.

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
