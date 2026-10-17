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
   once its WebGPU lands). `show()` renders into a CPU-readable frame buffer
   instead of a canvas; frames accumulate into stills, image sequences, GIFs
   and videos. Panels can be read back directly — the path for offline-baked
   static assets that replace today's init-time bakes
   (`src/utils/bake/Bake.scala` in the consuming repo).
2. **Texture sources on panels.** A panel can hold uploaded pixels instead of
   rendered content: raw buffers, `ImageData`, `HTMLImageElement`,
   `ImageBitmap`, canvases, and — through the same interface — video
   (`HTMLVideoElement`, `VideoFrame`) in the browser and decoded frames
   headless.

The design rule running through both: **one pixel currency, `Pixels`, that both
the output side produces and the input side consumes**, plus the browser's
native image types as an input-only fast path where they exist. A headless
pipeline is "Pixels in, Pixels out"; a browser one can additionally hand the
GPU an image element directly.

---

## 1. Research

### 1.1 Runtimes

| Runtime         | How you get a `GPU`                                                | Backend      | Canvas / surface                                   | Image APIs                                                                  | Status                                                                     |
| --------------- | ------------------------------------------------------------------ | ------------ | -------------------------------------------------- | --------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| **Node**        | `import { create } from 'webgpu'` → `create([...options])`         | Dawn         | none                                               | none (no `ImageBitmap`, no `copyExternalImageToTexture` sources)            | npm `webgpu` 0.6.x, Node ≥ 18                                              |
| **Deno**        | `navigator.gpu` (with `--unstable-webgpu` / `"unstable": ["webgpu"]`) | wgpu         | `Deno.UnsafeWindowSurface` (`getContext("webgpu")`, explicit `present()`, `resize()`); desktop windows from Deno 2.9 `BrowserWindow` | `createImageBitmap(Blob \| ImageData \| ImageBitmap)`; formats + `copyExternalImageToTexture` support **to verify** | unstable flag                                                              |
| **Bun (future)** | `navigator.gpu` + `GPU*` globals, no flag                         | wgpu-core 30 | **none** ("there is no canvas")                    | `GPUExternalTexture` / `copyExternalImageToTexture` explicitly out of scope | PR [oven-sh/bun#42768](https://github.com/oven-sh/bun/pull/42768) open, being split into a stack |
| **Bun (today)** | `bun-webgpu` (Dawn via FFI) or `wgpu-polyfill` (wgpu-native)       | Dawn / wgpu  | none                                               | `Bun.Image` decodes/encodes, but takes **encoded** input only (raw RGBA in/out is an open PR) | third-party                                                                |
| **NativeScript** | `navigator.gpu`                                                   | Metal / Vulkan | `canvas.getContext("webgpu")` — browser-shaped   | asset images, to verify                                                     | `@nativescript/canvas` 2.0 webgpu prerelease                               |

Takeaways:

- **The common denominator is exactly: a `GPU` object, no canvas, no image
  types.** Node and future-Bun don't have a canvas at all; Deno has one only
  through a native window. A headless design that needs nothing else covers all
  three, and needs no change when Bun's PR lands — `navigator.gpu` just starts
  existing.
- **Getting the `GPU` is the only runtime-specific step**, and it is one line.
  It belongs in a tiny per-runtime driver script, not in the library.
- **node-webgpu's own example is the reference workflow**
  ([example.js](https://github.com/dawn-gpu/node-webgpu/blob/main/example/example.js)):
  render to an `rgba8unorm` texture with `RENDER_ATTACHMENT | COPY_SRC`,
  `copyTextureToBuffer` into a `MAP_READ` buffer with `bytesPerRow` aligned to
  256, `mapAsync(READ)`, strip the row padding, encode PNG (`pngjs`). Deno's
  `@std/webgpu` `createCapture` + `getRowPadding` is the same pattern. The
  painter's capture surface is this, packaged.
- node-webgpu keeps the process alive while the `GPU` object is referenced
  (`delete navigator.gpu` to exit). The painter needs a `destroy()` that calls
  `device.destroy()`, and drivers drop their reference at the end.
- **Deno windows and NativeScript are on-screen hosts** — a real surface with
  `getContext("webgpu")`, closer to the browser than to headless. Out of scope
  here; tracked in [non-browser-hosts-plan.md](non-browser-hosts-plan.md). The
  rows above are kept so the `Surface` shape is checked against them (§3.8).

### 1.2 Adapter selection

WebGPU cannot enumerate adapters. What it offers is a request with hints and a
description of what you got:

- **Spec, portable:** `requestAdapter({ powerPreference: "high-performance" |
  "low-power", forceFallbackAdapter, featureLevel })`, then
  `adapter.info` (`GPUAdapterInfo`: `vendor`, `architecture`, `device`,
  `description`). `forceFallbackAdapter: true` asks for the software adapter
  (SwiftShader in Dawn).
- **Dawn / node-webgpu, at `create()` time** (README, "dawn node specific
  options"): `backend=vulkan|opengl|opengles|null|…` and `adapter=<name>`.
  A non-existent name fails with `no suitable backends found` and prints
  `Available adapters: * backend: 'vulkan', name: '…'` — the de facto way to
  enumerate; that name is then passed back verbatim. Same for an invalid
  `backend`. The README also points at plain `requestAdapter({
  powerPreference })` as the normal, portable way.
- **Deno (wgpu), process env:** `DENO_WEBGPU_BACKEND=vulkan|gl|…` (comma list,
  read in `ext/webgpu/lib.rs` `get_or_init_instance`). No adapter-name option;
  combine with the Vulkan-level selectors below.
- **Bun:** wgpu-core underneath; whether it exposes a backend env var is **to
  verify** once the PR lands (`WGPU_BACKEND` is wgpu's own convention).
- **Linux, below WebGPU — works for every runtime on the Vulkan backend:**
  - `VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json` (or
    `radeon_icd…`, `lvp_icd…` for lavapipe) restricts the Vulkan loader to one
    ICD. (`VK_ICD_FILENAMES` is the older name.)
  - `MESA_VK_DEVICE_SELECT=list` prints devices; `MESA_VK_DEVICE_SELECT=vid:did`
    picks one (Mesa device-select layer).
  - GL backends: `__GLX_VENDOR_LIBRARY_NAME=nvidia`,
    `__NV_PRIME_RENDER_OFFLOAD=1` for PRIME offload.

All runtime-level selection happens **before** the `GPU` object exists, so it
cannot be a painter parameter. The painter's part is: accept an explicit `GPU`
or `GPUAdapter`, pass the spec hints, **verify** what it got against a
requirement, and log `adapter.info` so a render never silently runs on
lavapipe when NVIDIA was intended. See §3.3.

### 1.3 Images and video as WebGPU textures (browser)

Three upload paths exist:

| Path                                       | Sources                                                                                                   | Result                                     | Notes                                                                                                                                                                         |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `queue.writeTexture(dst, data, layout, size)` | any `ArrayBuffer` / typed array                                                                           | regular `GPUTexture`                       | universal, every runtime. `bytesPerRow` has no 256 alignment requirement here (only buffer copies do).                                                                       |
| `queue.copyExternalImageToTexture(src, dst, size)` | `ImageBitmap`, `ImageData`, `HTMLImageElement`, `HTMLCanvasElement`, `OffscreenCanvas`, `HTMLVideoElement`, `VideoFrame` | regular `GPUTexture`                       | dst usage must include `COPY_DST \| RENDER_ATTACHMENT`; dst format from a fixed list (`rgba8unorm[-srgb]`, `bgra8unorm[-srgb]`, `rgba16float`, `rgba32float`, `r8/rg8unorm`, `r/rg 16/32float`, `rgb10a2unorm`). Options `flipY`, `premultipliedAlpha`, `colorSpace`. Firefox: `VideoFrame` reportedly unsupported here — wrap in `ImageBitmap`. |
| `device.importExternalTexture({ source })`  | `HTMLVideoElement`, `VideoFrame`                                                                          | `GPUExternalTexture` (`texture_external` in WGSL) | zero-copy (YUV sampled in hardware). Expires: from a video element at the end of the task that used it; from a `VideoFrame` when the frame is closed → bind groups rebuilt every frame. Sample with `textureSampleBaseClampToEdge` / `textureLoad` only, no mips. Not Baseline: listed missing in Firefox 141; Safari status unclear. Not in Bun's PR, not in node-webgpu. |

Video on the web today is therefore done one of two ways: **copy per frame**
(`copyExternalImageToTexture` from the video element or a WebCodecs
`VideoFrame` into an ordinary texture — portable, mips possible, sampled like
any texture) or **external texture** (zero-copy, Chromium-best, a separate
binding type with per-frame bind groups). New-frame pacing is
`requestVideoFrameCallback` on `HTMLVideoElement`, or the decoder output
callback for WebCodecs.

Decoding encoded bytes (PNG/JPEG) is never WebGPU's job: browser and Deno use
`createImageBitmap(blob, { colorSpaceConversion: "none", premultiplyAlpha:
"none" })` — the two options matter for **data** textures (baked noise, LUTs),
which must not be color-managed or premultiplied. Node/Bun decode to raw RGBA
in JS (see §4.3).

### 1.4 Encoding output

- **PNG** needs zlib deflate + CRC32. `CompressionStream("deflate")` produces
  exactly the zlib stream PNG `IDAT` wants, and exists in browsers, Node ≥ 18,
  Deno and Bun — so a ~100-line PNG encoder/decoder in trivalibs is fully
  runtime-agnostic, no `pngjs`/`sharp` needed. 16-bit PNG covers `rgba16float`
  bakes that fit `[0,1]`.
- **Float / mip-chain assets:** **KTX2** (uncompressed) is the standard GPU
  texture container — any `GPUTextureFormat`, mip levels, trivially written and
  parsed. Better than raw `.bin` + sidecar JSON.
- **Video / GIF:** `ffmpeg` as a subprocess, raw frames on stdin
  (`-f rawvideo -pix_fmt rgba -s WxH -r FPS -i -`). `node:child_process` spawn
  works in Node, Bun and Deno (node compat), so one implementation covers all
  three. GIF via `split[a][b];[a]palettegen[p];[b][p]paletteuse` in one pass.
- `sharp` (Node/Bun/Deno via npm) and `Bun.Image` are optional extras for
  JPEG/WebP/AVIF; `Bun.Image` currently only accepts encoded input, so it can't
  take readback pixels directly yet.
- File writes: `node:fs` — available in all three non-browser runtimes.

---

## 2. What is browser-coupled today

Verified against the current sources:

| Where                                 | Assumption                                                       |
| ------------------------------------- | ---------------------------------------------------------------- |
| `painter/webgpu.scala` `getGPU`       | `dom.window.navigator.gpu` — `window` is undefined off-browser   |
| `painter/webgpu.scala` `getContext`   | `canvas.getContext("webgpu")`                                    |
| `Painter.init`                        | `gpu.getPreferredCanvasFormat()`, `clientWidth/Height`, `ResizeObserver` |
| `Painter` fields                      | `canvas`, `context` as constructor vals; `width/height` read `canvas` |
| `paintPanel`                          | `canvas.width/height` as the panel size fallback                 |
| `show`, `draw`                        | `context.getCurrentTexture()`, no explicit present               |
| `Painter.input`                       | DOM canvas (method, DCE'd when unused — fine)                    |
| `utils/animate.scala` `Animator`      | global `requestAnimationFrame`                                   |

No external code reads `p.canvas` / `p.context` (grep over sketches, `src/`,
examples). The painter already defines its own `GPUTextureUsage` /
`GPUBufferUsage` / `GPUShaderStage` constants, so it does not depend on the
WebGPU globals that node-webgpu only installs via `Object.assign(globalThis,
globals)` — **keep that invariant** (add `GPUMapMode` the same way).

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
  /** Schedule the next `p.animate` frame; `tpf` in ms. */
  def requestFrame(cb: Double => Unit): Unit
```

`present` takes the frame's encoder so a capture surface can append its
`copyTextureToBuffer` to the same submission as the blit.

| Surface                    | `currentTexture`                     | `present`                                | `onResize`             | `requestFrame`                              | Phase |
| -------------------------- | ------------------------------------ | ---------------------------------------- | ---------------------- | ------------------------------------------- | ----- |
| `CanvasSurface(canvas)`    | `context.getCurrentTexture()`        | no-op                                    | `ResizeObserver`       | `requestAnimationFrame`                     | M1.0  |
| `CaptureSurface(w, h, …)`  | owned texture, `RENDER_ATTACHMENT \| COPY_SRC` | copy → staging buffer → `mapAsync` → frame | never                  | fixed-step offline driver (§3.5)            | M1.1  |

`CanvasSurface` is today's `Painter.init` body moved verbatim: browser
behavior does not change. It is the only place that calls
`gpu.getPreferredCanvasFormat()`, `ResizeObserver` and `requestAnimationFrame`.
Typed as `HTMLCanvasElement | OffscreenCanvas`, duck-typed at runtime
(`getContext("webgpu")`).

**No auto-detection** (reasoning carried over from the host-seam design in
[non-browser-hosts-plan.md](non-browser-hosts-plan.md)): a
detecting `init(anything)` makes every surface reachable from every bundle and
defeats Scala.js DCE. Surfaces are constructed explicitly by the entry point;
a browser bundle links `CanvasSurface` only, an offline bundle links
`CaptureSurface` only. `Painter` references the trait.

### 3.2 Painter init API

Device acquisition gets its own function — the one place with defaulted
parameters, so it doesn't fight the existing `init` overloads (Scala allows
defaults on only one alternative of an overloaded name):

```scala
object WebGPU:
  /** `globalThis.navigator?.gpu` — browsers, Deno, NativeScript, Bun-native. */
  def getGPU(): js.UndefOr[GPU]

  /** Resolve a device. Each step can be supplied instead of derived:
    * `adapter` skips `gpu.requestAdapter`, `gpu` skips `getGPU()`. */
  def requestDevice(
      gpu: Maybe[GPU] = Maybe.Not,
      adapter: Maybe[GPUAdapter] = Maybe.Not,
      powerPreference: Maybe[PowerPreference] = Maybe.Not,
      forceFallbackAdapter: Boolean = false,
      requireAdapter: Maybe[js.Function1[GPUAdapterInfo, Boolean]] = Maybe.Not,
      requiredFeatures: Maybe[Arr[String]] = Maybe.Not,
      requiredLimits: Maybe[js.Object] = Maybe.Not,
      logAdapter: Boolean = false,
  ): js.Promise[GPUDevice]
```

```scala
object Painter:
  // unchanged — browser sugar over create(CanvasSurface(canvas))
  def init(canvas: HTMLCanvasElement): js.Promise[Painter]
  def init(canvas: HTMLCanvasElement)(setup: Painter => Unit): js.Promise[Unit]

  // general: any surface, full control over GPU / adapter / device
  def create(
      surface: Surface,
      gpu: Maybe[GPU] = Maybe.Not,
      adapter: Maybe[GPUAdapter] = Maybe.Not,
      device: Maybe[GPUDevice] = Maybe.Not,
      powerPreference: Maybe[PowerPreference] = Maybe.Not,
      forceFallbackAdapter: Boolean = false,
      requireAdapter: Maybe[js.Function1[GPUAdapterInfo, Boolean]] = Maybe.Not,
      requiredFeatures: Maybe[Arr[String]] = Maybe.Not,
  ): js.Promise[Painter]
```

`create` forwards to `WebGPU.requestDevice` unless `device` is given. The
browser gains the options too: `Painter.create(CanvasSurface(canvas),
powerPreference = PowerPreference.HighPerformance)`. `Painter` gains
`adapterInfo: GPUAdapterInfo` (when it resolved the adapter itself),
`surface: Surface`, and `destroy()` (`device.destroy()`). `width` / `height` /
`onResize` delegate to the surface; `canvas` / `context` leave the constructor.

### 3.3 Adapter control, offline

Two layers, because selection splits across the `GPU` boundary (§1.2):

**Runtime level — in the driver, before `GPU` exists.** The driver script maps
two generic flags to the runtime's mechanism, so one command line means the
same thing everywhere:

| Flag                | node-webgpu                    | Deno                               | Bun (when native)  |
| ------------------- | ------------------------------ | ---------------------------------- | ------------------ |
| `--backend vulkan`  | `create(["backend=vulkan"])`   | `DENO_WEBGPU_BACKEND=vulkan`       | to verify          |
| `--adapter <name>`  | `create(["adapter=<name>"])`   | via `requireAdapter` (below) + Vulkan env | same as Deno |
| `--software`        | `forceFallbackAdapter = true`  | `forceFallbackAdapter = true`      | same               |
| `--vk-driver nvidia` | `VK_DRIVER_FILES=…/nvidia_icd.json` for all runtimes (set before spawn) | | |

Env vars must be set before the runtime initializes its GPU instance, so the
driver either documents them or re-spawns itself with them set.

**Painter level — verify, don't trust.** `requireAdapter` receives
`adapter.info` and rejects the promise with a message listing what was found
(`vendor / architecture / device / description`). A convenience
`WebGPU.adapterNamed("nvidia")` builds a case-insensitive substring predicate
over those fields. `logAdapter = true` prints the info once — default on in
the offline drivers, off in the browser.

A `list-adapters` driver mode: on Node it calls `create(["adapter=?"])` and
relays Dawn's `Available adapters` list (backend + exact name to pass to
`--adapter`); on every runtime it also prints `info` for default,
`high-performance`, `low-power` and fallback requests. That is as close to
enumeration as the spec allows. On Node `--adapter` is exact (Dawn picks by
name); on Deno/Bun it is a `requireAdapter` check after the fact, so steering
there is `--backend` + the Vulkan env vars.

### 3.4 `CaptureSurface` and `Pixels`

```scala
/** Tightly packed pixel rows (no padding), top row first. */
final class Pixels(
    val width: Int,
    val height: Int,
    val data: Uint8Array,                 // view over any typed array's bytes
    val format: TextureFormat = TextureFormat.Rgba8Unorm,
)
object Pixels:
  def apply(width: Int, height: Int, data: ArrayBufferView, format: TextureFormat): Pixels
  def fromImageData(img: ImageData): Pixels   // browser
extension (p: Pixels)
  def bytesPerPixel: Int
  def asFloat32: Float32Array                 // rgba32float data
  def toImageData: ImageData                  // browser, rgba8unorm only
```

```scala
class CaptureSurface(
    val width: Int,
    val height: Int,
    val format: TextureFormat = TextureFormat.Rgba8Unorm,
    keepFrames: Boolean = true,
    onFrame: Maybe[js.Function2[Pixels, Int, js.Promise[Unit] | Unit]] = Maybe.Not,
    fps: Double = 30.0,
    frameCount: Int = 1,
) extends Surface:
  def frames: Arr[Pixels]                    // completed, in order (if keepFrames)
  def flush(): js.Promise[Arr[Pixels]]       // all pending readbacks settled + sinks done
  def capture(): Unit                        // capture the current texture without show()
```

- `show(panel)` = blit to `currentTexture()` + `present` = **one captured
  frame**. Several `show` calls accumulate several frames; nothing else
  triggers a capture. `draw()`-only code calls `surface.capture()`.
- Readback: per frame a staging buffer from a small pool, `bytesPerRow =
  align(width * bpp, 256)`, `mapAsync(READ)`, unpad into a fresh `Pixels`,
  `unmap`, return the buffer to the pool. `bgra8unorm` is swizzled to RGBA
  while unpadding so files are always RGBA. Map completions settle in
  submission order; sinks are chained so `onFrame` sees frames in order.
- Memory: `keepFrames = false` + `onFrame` streams (video); `keepFrames =
  true` is for stills and short sequences. A manual `show` loop without the
  driver (§3.5) can outrun readback — `flush()` before exit; documented.
- `format = Rgba8Unorm` by default, not `bgra8unorm`: no swizzle and matches
  PNG. The display path writes raw (non-sRGB) values exactly as the browser
  canvas does, so a capture looks like the browser frame.

**Direct panel readback**, independent of `show` and of the surface type:

```scala
// on Painter
def read(panel: Panel, index: Int = 0, mipLevel: Int = 0): js.Promise[Pixels]
```

Exact format and size of the panel texture — the path for baked assets, where
`show`'s blit (scaling, format conversion to the surface) is wrong. Needs
`COPY_SRC` on panel color textures: add it unconditionally in `ensureSize`
(no measurable cost). Works in the browser too (e.g. a dev "download bake"
button). Depth is not readable (`depth24plus` is not copyable) — out of scope.

### 3.5 Frame loop: `p.animate`

`Animator` takes its scheduler instead of reaching for the global
`requestAnimationFrame`. `p.animate(frame)` routes through
`surface.requestFrame`; the free `animate` stays as the browser convenience.

`CaptureSurface.requestFrame` is a **fixed-step offline driver**: `tpf =
1000 / fps` exactly, next frame scheduled only after the previous frame's
readback and sink settled (backpressure, bounded memory), stops after
`frameCount` frames and resolves `flush()`. Same sketch code, deterministic
output:

```scala
p.animate: tpf =>
  time += tpf
  timeBinding := time
  p.paint(scene)
  p.show(scene)
```

In the browser that's a live loop; with `CaptureSurface(fps = 60, frameCount
= 600)` it is a 10 s video, frame-exact regardless of GPU speed. Sketches
migrate from `animate:` to `p.animate:` only where they want to be renderable.

### 3.6 Writing files — `trivalibs.offline`

Separate package, only linked by offline entry points. Pure Scala.js, the only
runtime imports are `node:fs` / `node:child_process` (all three runtimes).

```scala
object Png:
  def encode(px: Pixels): js.Promise[Uint8Array]          // 8-bit; 16-bit for rgba16float
  def decode(bytes: Uint8Array): js.Promise[Pixels]       // CompressionStream-based, also browser
  def write(path: String, px: Pixels): js.Promise[Unit]
  def writeSequence(pattern: String): FrameSink           // "out/frame_%04d.png"

object Ktx2:                                              // M2.3
  def encode(levels: Arr[Pixels]): Uint8Array
  def decode(bytes: Uint8Array): Arr[Pixels]
  def write(path: String, levels: Arr[Pixels]): js.Promise[Unit]

trait FrameSink:
  def write(px: Pixels, index: Int): js.Promise[Unit]
  def close(): js.Promise[Unit]

object Ffmpeg:
  def video(path: String, width: Int, height: Int, fps: Double,
            codec: String = "libx264", crf: Int = 18,
            pixFmt: String = "yuv420p", extraArgs: Arr[String] = Arr()): FrameSink
  def gif(path: String, width: Int, height: Int, fps: Double, loop: Int = 0): FrameSink
  def frames(path: String, width: Maybe[Int] = Maybe.Not,
             height: Maybe[Int] = Maybe.Not): FrameSource // decode → Pixels, §4.3
```

`FrameSink.write` honours stdin backpressure (`write()` false → await
`drain`). `CaptureSurface(onFrame = sink.write)` streams; `flush()` then
`sink.close()`.

### 3.7 End-to-end shape

Scala side, an offline entry point next to (not inside) the sketch's browser
entry, so the deployed `main.js` never carries `trivalibs.offline`:

```scala
@JSExportTopLevel("render")
def renderWallBakes(gpu: GPU): js.Promise[Unit] =
  val out = CaptureSurface(width = 1024, height = 1024)
  Painter
    .create(out, gpu = gpu, requireAdapter = WebGPU.adapterNamed("nvidia"))
    .flatMap: p =>
      val wall = TextureBaker.bake(p, wallForm, 1024, 1024)(wallShade)
      p.read(wall).flatMap(px => Png.write("assets/wall.png", px))
```

```scala
@JSExportTopLevel("render")
def renderLoop(gpu: GPU): js.Promise[Unit] =
  val video = Ffmpeg.video("out/loop.mp4", 1920, 1080, fps = 60)
  val out = CaptureSurface(1920, 1080, keepFrames = false,
                           onFrame = video.write, fps = 60, frameCount = 600)
  Painter.create(out, gpu = gpu).flatMap: p =>
    sketchBody(p)                          // the same body the browser runs
    out.flush().flatMap(_ => video.close())
```

Per-runtime drivers — the only runtime-specific code, a few lines each:

```ts
// node:  node render.node.ts --backend vulkan
import { create } from 'webgpu'
import { render } from './render.js'
const gpu = create(['backend=vulkan'])
await render(gpu)
```

```ts
// deno:  DENO_WEBGPU_BACKEND=vulkan deno run --unstable-webgpu -A render.deno.ts
import { render } from './render.js'
await render(navigator.gpu)
```

```ts
// bun, once native WebGPU ships: identical to the Deno driver.
// today: bun-webgpu / wgpu-polyfill provide the GPU object (API to verify).
```

The consuming repo grows a `scripts/render.ts <path> [--runtime node|deno|bun]
[--backend …] [--adapter …]` that builds the offline entry with scala-cli and
spawns the chosen runtime — a consumer-side plan, not trivalibs.

**Entry-point signature.** Browser sketches keep `sketch(canvas:
HTMLCanvasElement)`; offline entries take `gpu: GPU` and construct their own
`CaptureSurface`, since its size, fps and frame count belong to the render, not
to the host. Whether browser sketches should later take a `Surface` instead (so
one body runs on every host) is the native-window plan's decision; nothing here
forces it.

**`Painter.input`** needs a DOM canvas and stays browser-only. It must not be
reached from code meant to run offline: a portable body takes inputs as
parameters (or none — an offline render has no user). Moving it onto
`CanvasSurface` is the native-window plan's call.

### 3.8 Kept compatible: native-window surfaces (separate issue)

Not implemented here, but the `Surface` trait must admit them without change —
if a later window surface needs edits to `Surface` or `Painter`, this design
was wrong:

- **Explicit present** (Deno `UnsafeWindowSurface.present()`) — covered by
  `present(encoder)`, called once per `show`.
- **Resize driven by the host** (Deno window `resize` event →
  `surface.resize(w, h)`; NativeScript layout events) — covered by `onResize`;
  panels already re-size from `surface.width/height` in `paintPanel`.
- **No `requestAnimationFrame`** (Deno raw backend loops with `setTimeout`) —
  covered by `requestFrame`; nothing in `Painter` or `Animator` touches the
  global.
- **Host-chosen format** (`getPreferredCanvasFormat` may be missing; fall back
  to `bgra8unorm`) — covered by `Surface.format`.
- **Device before surface** (Deno: `getNativeWindow()` throws before
  `requestAdapter()`) — `Painter.create` resolves the device first, then calls
  `surface.configure(device)`, so a surface may construct its context lazily
  in `configure`.

---

## 4. Milestone 2 — texture sources on panels

### 4.1 One source type

```scala
/** Browser-native images; types only, never `instanceof`-checked at runtime. */
type ExternalImage =
  dom.ImageBitmap | dom.ImageData | dom.HTMLImageElement | dom.HTMLCanvasElement |
  OffscreenCanvas | dom.HTMLVideoElement | VideoFrame

type TextureSource = Pixels | ExternalImage
```

Raw buffers enter as `Pixels(width, height, data, format)` — the Scala
equivalent of Rust's `with_static_texture_data(bytes)`, with size and format
explicit because bytes carry neither. `ImageData` can go either way: direct
(`copyExternalImageToTexture`) or `Pixels.fromImageData`.

Runtime dispatch is **`isInstanceOf[Pixels]` (our class) → `writeTexture`,
anything else → `copyExternalImageToTexture`**. Never `instanceof
HTMLImageElement` — referencing a DOM global throws in Node. Size of external
sources is duck-typed in this order: `videoWidth` (video) → `naturalWidth`
(img) → `displayWidth` (VideoFrame) → `width` (bitmap, ImageData, canvas). The
order matters: `<video>` and `<img>` also have a `width` that is the layout
size, not the pixel size.

### 4.2 Panel API

```scala
p.panel(source = bitmap)                              // size from source
p.panel(source = Pixels(256, 256, noiseBytes, TextureFormat.Rgba32Float))
p.panel(source = img, format = TextureFormat.Rgba8UnormSrgb, mips = true)
p.panel(source = video, live = true)                  // re-upload on every paint
panel.set(source = nextFrame)                         // explicit re-upload
```

New `Panel.set` / `Painter.panel` params: `source: Maybe[TextureSource]`,
`live: Maybe[Boolean]`, `flipY: Maybe[Boolean]`, `premultipliedAlpha:
Maybe[Boolean]`.

Semantics:

- A source panel's size comes from the source (sets `specWidth/specHeight`), so
  it never tracks the surface. Format: `Pixels.format`, else the panel's
  `format`, else **`Rgba8Unorm`** (not `preferredFormat`, which is `bgra8unorm`
  on many browsers and would make the asset path format-dependent).
- Texture usage adds `COPY_DST | RENDER_ATTACHMENT` (the latter is required by
  `copyExternalImageToTexture` and by mip generation).
- **Upload is eager on `set(source = …)`** — static panels are typically only
  sampled, never painted. Same size and format → reuse the texture; otherwise
  reallocate through the existing `ensureSize` path (bumps `bindEpoch`, so
  cached layer bind groups rebuild).
- `mips = true` → the existing `generateMipmaps` runs after each upload.
- `live = true` → `paint(panel)` re-uploads from the held source. Video
  elements are skipped while `readyState < 2` and when `currentTime` hasn't
  changed since the last upload. `VideoFrame`s are caller-owned: the caller
  `set`s each new frame and closes the old one.
- Shapes or layers on a source panel throw in `set` (the existing validation
  choke point). Post-processing goes in a second panel that samples it.
  Rationale: a pong layer on a static texture would compound on every paint;
  that semantic is not worth defining until a sketch needs it.
- Everything downstream is unchanged: a source panel is sampled exactly like a
  rendered one (`"tex" := panel`, `panel.binding(mipLevel = …)`), because it
  reuses `_textures` / `views` slot 0.

### 4.3 Loaders — per environment, no detection

```scala
// browser: trivalibs.graphics.painter.WebImages
def load(url: String): js.Promise[dom.ImageBitmap]
// fetch → blob → createImageBitmap(blob, colorSpaceConversion = "none",
//                                       premultiplyAlpha = "none")

// offline: trivalibs.offline.Images
def load(path: String): js.Promise[Pixels]   // .png → Png.decode, .ktx2 → Ktx2.decode
```

`colorSpaceConversion: "none"` and `premultiplyAlpha: "none"` are the defaults
on purpose: baked data textures must arrive bit-exact. A photo that should be
color-managed passes `colorManaged = true`.

The two loaders are separate objects rather than one detecting function: the
offline one transitively imports `node:fs`, which must never reach a Vite
bundle.

### 4.4 Video, consistently

| Environment | Source                                           | Path                                 |
| ----------- | ------------------------------------------------ | ------------------------------------ |
| Browser     | `HTMLVideoElement`, `live = true`                | copy per frame                       |
| Browser     | WebCodecs `VideoFrame`, `panel.set(source = f)`  | copy per frame (Firefox: via `ImageBitmap`) |
| Offline     | `Ffmpeg.frames(path)` → `Pixels` per frame       | `writeTexture` per frame             |

So video is "a source that changes every frame" — the same panel API as a
still. The output side mirrors it: `Ffmpeg.video` consumes the `Pixels` that
`CaptureSurface` produces, so an offline pipeline can decode a video, process
it through panels, and encode the result, with no browser types involved.

**Zero-copy external textures are deliberately not the first step.** They need
a new binding kind (`texture_external` + a DSL marker beside
`FragmentPanel`), per-frame bind group rebuilds, no mips, and are missing on
Firefox, node-webgpu and Bun's PR. Copy-per-frame costs one GPU copy per frame
— negligible below 4K. Add `p.videoTexture(video)` as an opt-in binding later,
only if a profile asks for it; the panel path stays.

### 4.5 Offline bake → static asset → sketch

The workflow this milestone enables, replacing init-time bakes where startup
time matters:

1. Offline entry runs the same `TextureBaker` code, `p.read(panel)` →
   `Png.write` (8-bit) or `Ktx2.write` (float formats, prebuilt mips) into the
   sketch's `assets/`.
2. The sketch loads it: `WebImages.load("assets/wall.png").map(img =>
   p.panel(source = img, mips = true))`; KTX2 via `fetch` + `Ktx2.decode` +
   `Pixels` sources per mip.
3. A bake-or-load switch is consumer-side code (`sketchlib`), proposed
   separately — not extracted as part of this work.

---

## 5. WebGPU facade additions

`webgpu.scala` gains, all as plain `@js.native` members:

- `GPUAdapter.info: GPUAdapterInfo` (`vendor`, `architecture`, `device`,
  `description`), `features`, `limits`
- `GPUDevice.destroy()`, `lost: js.Promise[js.Dynamic]`,
  `importExternalTexture` (later)
- `GPUQueue.writeTexture`, `copyExternalImageToTexture`,
  `onSubmittedWorkDone`
- `GPUCommandEncoder.copyTextureToBuffer`, `copyTextureToTexture`
- `GPUBuffer.mapAsync`, `getMappedRange`, `unmap`, `size`
- `GPUTexture.width/height/format`
- `object GPUMapMode { val READ = 1; val WRITE = 2 }`
- `PowerPreference` opaque type (`"high-performance"`, `"low-power"`)
- `OffscreenCanvas`, `VideoFrame` facades if scalajs-dom 2.8 lacks them
  (types only)

---

## 6. Phasing

Each step lands independently and leaves `examples/` and the sketches working.

| Step     | Content                                                                                                                                       | Proof                                                                         |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **M1.0** | `Surface` + `CanvasSurface`, `getGPU` via `globalThis`, `WebGPU.requestDevice`, `Painter.create`, `p.animate`, scheduler-taking `Animator`, `destroy`, facade additions | no behavior change; all examples render                                       |
| **M1.1** | `Pixels`, `CaptureSurface` (still frames), `p.read(panel)`, `COPY_SRC` on panels, `Png.encode/decode/write`, adapter verification + logging | `examples/headless-triangle/` with Node and Deno drivers writing a PNG        |
| **M1.2** | fixed-step offline driver, `FrameSink`, `Png.writeSequence`, `Ffmpeg.video` / `gif`                                                            | headless example writes an mp4 and a gif                                      |
| **M1.3** | munit GPU integration tests: `scala-cli test` already runs on Node, so with `webgpu` installed a test can render + read back (lavapipe in CI) | pixel assertions on a known render                                            |
| **M2.0** | `TextureSource`, `source` / `live` / `flipY` / `premultipliedAlpha` on panels, `Pixels` + external-image upload, mips after upload           | example: raw-buffer panel, image panel; close the `independent-todos` entry  |
| **M2.1** | `WebImages.load`, `Images.load` (offline), video element `live` panel                                                                          | example: video panel in browser; offline loads a PNG it wrote                 |
| **M2.2** | `Ffmpeg.frames` → offline video input                                                                                                          | offline: video in → processed → video out                                     |
| **M2.3** | `Ktx2` encode/decode, float + mip assets                                                                                                       | float bake round-trips bit-exact                                              |
| later    | external-texture binding                                                                                                                       | —                                                                             |

Native-window surfaces are not a phase of this plan — see
[non-browser-hosts-plan.md](non-browser-hosts-plan.md).

Examples are browser-served today; headless examples carry their own driver
scripts and are run, not deployed.

---

## 7. To verify at implementation time

- Deno: does `copyExternalImageToTexture` exist, and which formats does
  `createImageBitmap` decode? (Doesn't block anything — the `Pixels` path is
  universal — but decides whether Deno can use `WebImages`.)
- Deno: current WebGPU flag requirement in the version in use; whether
  `navigator.gpu` needs `--unstable-webgpu` for headless scripts.
- Bun native WebGPU: merge status of the PR stack, backend env var, whether
  `GPUAdapterInfo` is populated.
- `bun-webgpu` / `wgpu-polyfill`: how each exposes the `GPU` object.
- Scala.js emits a `@JSImport` only when reachable, so `trivalibs.offline`'s
  `node:` imports never appear in browser bundles — confirm in a `jsMode full`
  link of a browser example.
- `CompressionStream("deflate")` output is accepted by browsers' PNG decoders
  and by `DecompressionStream` in all three runtimes (zlib wrapper, RFC 1950).
- Firefox `importExternalTexture` / `copyExternalImageToTexture(VideoFrame)`
  current status.

## 8. Open decisions

- **Naming.** `Surface` / `CaptureSurface` / `Pixels` / `Painter.create` /
  `trivalibs.offline` are proposals.
- **Where encoding lives.** Proposed in trivalibs (`trivalibs.offline`) since
  PNG/KTX2 decode is also needed by the M2 loaders. Alternative: consumer
  `src/`.
- **Sketch entry for offline renders.** Separate offline entry file per sketch
  (proposed, keeps the deployed bundle clean) vs. a second export in the
  sketch's `main.js`.

## Sources

- node-webgpu: [repo](https://github.com/dawn-gpu/node-webgpu),
  [example.js](https://github.com/dawn-gpu/node-webgpu/blob/main/example/example.js)
- Deno: [`createImageBitmap`](https://docs.deno.com/api/web/~/createImageBitmap),
  [`@std/webgpu` createCapture](https://jsr.io/@std/webgpu/doc/create-capture/~/createCapture),
  `DENO_WEBGPU_BACKEND` in
  [ext/webgpu/lib.rs](https://github.com/denoland/deno/blob/main/ext/webgpu/lib.rs)
- Bun: [WebGPU PR #42768](https://github.com/oven-sh/bun/pull/42768),
  [`Bun.Image`](https://bun.com/docs/runtime/image),
  [`Bun.Image.raw()` PR #43623](https://github.com/oven-sh/bun/pull/43623),
  [bun-webgpu](https://github.com/kommander/bun-webgpu),
  [wgpu-polyfill](https://github.com/wiedymi/wgpu-polyfill)
- Video/images: [MDN `importExternalTexture`](https://developer.mozilla.org/en-US/docs/Web/API/GPUDevice/importExternalTexture),
  [MDN `copyExternalImageToTexture`](https://developer.mozilla.org/docs/Web/API/GPUQueue/copyExternalImageToTexture),
  [Chrome WebGPU 116 (WebCodecs)](https://developer.chrome.com/blog/new-in-webgpu-116),
  [Image loading on the web](https://www.ludicon.com/castano/blog/2026/05/image-loading-on-the-web/),
  [Firefox 141 WebGPU](https://linuxiac.com/webgpu-lands-in-firefox-141-on-windows-eyes-linux-and-macos-next/)
- Native-window sources (Deno desktop, NativeScript) are in
  [non-browser-hosts-plan.md](non-browser-hosts-plan.md).
