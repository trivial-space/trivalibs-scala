# Painter Setup Guide

Everything about getting a `Painter` beyond `Painter.init(canvas)`: rendering
offscreen without a browser, reading pixels back into plain JS data, picking the
GPU, screenshots at another resolution, and what a render script has to do about
process lifetime. A browser sketch needs none of this — the
[sketch authoring guide](sketch-authoring-guide.md) covers `Painter.init`.

The rule behind the API: **Scala describes, the host handles I/O.** The painter
renders into plain JS objects (`PixelFrames`, `PixelImage`); encoding PNGs,
running ffmpeg and writing files is host code, usually a short TypeScript
script. Neither side imports anything from the other to build or read those
objects.

Runnable references:

| Example                       | Shows                                                            |
| ----------------------------- | ---------------------------------------------------------------- |
| `examples/headless_triangle/` | offscreen still, 8- and 16-bit PNG, adapter flags, list adapters |
| `examples/headless_sequence/` | fixed-`tpf` loop → mp4 + gif via ffmpeg, a smaller preview       |
| `examples/screenshot/`        | 4K PNG download next to a running canvas                         |
| `examples/example_helpers/`   | `png.ts`, `ffmpeg.ts`, `download.js`, `pixels.d.ts`              |

## 1. Use cases

### 1.1 Browser canvas — the default

```scala
Painter.init(canvas): p =>
  ...
```

Reads `navigator.gpu`, requests the default adapter and device, and presents to
the canvas. The only library code that looks a GPU up.

### 1.2 Browser canvas with adapter options

The extended `init` overload takes the `GPU` from the caller and accepts the
options of §2:

```scala
val gpu = WebGPU.getGPU().safe
Painter
  .init(canvas, gpu, powerPreference = PowerPreference.HighPerformance)
  .map: p =>
    ...
```

### 1.3 Offscreen still

The Scala side exports a function that takes the `GPU` and a pixel target,
renders, and resolves once the pixels have landed:

```scala
@JSExportTopLevel("render")
def render(gpu: GPU, out: PixelImage): js.Promise[Unit] =
  Painter
    .init(out, gpu, logAdapter = true)
    .flatMap: p =>
      val panel = p.panel(clearColor = (0.2, 0.4, 0.6, 1.0))
      p.paintAndShow(panel)
      p.awaitReadback()
```

The host creates both and encodes the result (Node or Bun, §3):

```ts
import { writeFileSync } from 'node:fs'
import { create } from 'webgpu'
import { encodePng } from '../example_helpers/png.ts'
import { render } from '../out/my_render.js'

const out = {
  width: 1024,
  height: 1024,
  format: 'rgba8unorm',
  data: new Uint8Array(1024 * 1024 * 4),
}
await render(create([]), out)
writeFileSync('assets/still.png', encodePng(out.width, out.height, out.data, 8))
```

A painter created on a pixel target — an offscreen painter — uses the target's
size as its surface size and its format as the default panel format. `show`
blits into it and reads back.

### 1.4 Offscreen motion sequence

No scheduler, no `animate`: the script owns the loop and steps the animation by
a fixed `tpf`, so the result is frame-exact whatever the GPU speed. Each `show`
appends one frame to a `PixelFrames`:

```scala
@JSExportTopLevel("render")
def render(gpu: GPU, out: PixelFrames, frameCount: Int): js.Promise[Unit] =
  Painter
    .init(out, gpu)
    .flatMap: p =>
      val value = p.binding(0.0)
      val shade = p.layerShade[(value: Float)]: program =>
        program.frag: ctx =>
          ctx.out.color := vec4(ctx.bindings.value, 0.0, 0.0, 1.0)
      val panel = p.panel(layer = p.layer(shade).bind("value" := value))
      val tpf = 1000.0 / 24.0
      var time = 0.0
      for _ <- 0 until frameCount do
        value.set((time * 0.001).sin * 0.5 + 0.5)
        p.paintAndShow(panel)
        time += tpf
      p.awaitReadback()
```

Encode the frames with ffmpeg (§5.6). `examples/headless_sequence/` is the full
version, with a seamless loop.

### 1.5 Bake to an asset

A texture bake is an offscreen still at the texture's size: same size as the
pixel target, so the output is exact (§5.3). Write it into the consuming
sketch's `assets/` and load it there instead of baking at startup. A float bake
uses `rgba16float` (or `rgba32float`, §2.3) and a raw or KTX2 file rather than a
PNG.

### 1.6 Screenshot next to canvas rendering

Any painter, a canvas one included, can write a painted panel into a pixel
target with `showTo`. `atSize` repaints at another size first; inside it, panels
without a fixed size, `p.width` / `p.height` and the `onResize` callbacks all
follow the override:

```scala
val shot = PixelImage(3840, 2160)
val saved = p.atSize(shot.width, shot.height):
  p.paint(panel)
  p.showTo(panel, shot)
saved.map: image =>
  savePng(image)
```

`atSize` returns its body's result, so the `showTo` promise comes out of the
scope. Without `atSize`, `showTo` scales the panel to the target's size. Its
capture texture and staging buffer are cached per size and format for the
painter's lifetime, so repeated captures allocate nothing. Where users pick
arbitrary sizes, pass `cache = false`: both are then allocated for that call
only and freed once the pixels have landed. Or keep the default and call
`p.freeCaptureCache()` when done — e.g. after an export dialog closes; reads
still in flight land regardless. The next regular `paint` reallocates the panels
back to the canvas size; panels that carry history across frames lose it, as on
a window resize. See `examples/screenshot/` — the PNG encoding and download are
host code (`example_helpers/download.js`), handed to Scala as a function.

### 1.7 A painter on a device you already have

```scala
val p = Painter.init(PixelImage(512, 512), device)
```

Synchronous, no lookups. For adapter-level control, do the two steps by hand
(§2.2) and pass the device. The painter then holds no `gpu` / `adapter`, so
**keeping the `GPU` referenced is the caller's job** (§6).

## 2. Init API reference

### 2.1 `Painter.init`

| Entry                          | Target                          | GPU                  | Returns               |
| ------------------------------ | ------------------------------- | -------------------- | --------------------- |
| `Painter.init(canvas)`         | the canvas                      | `navigator.gpu`      | `js.Promise[Painter]` |
| `Painter.init(canvas)(setup)`  | the canvas                      | `navigator.gpu`      | `js.Promise[Unit]`    |
| `Painter.init(target, gpu, …)` | pixel target, canvas, `Surface` | caller's `GPU`       | `js.Promise[Painter]` |
| `Painter.init(target, device)` | pixel target, canvas, `Surface` | caller's `GPUDevice` | `Painter`             |

Only `init(canvas)` looks the GPU up for its adapter and device. One smaller
lookup remains: a raw canvas passed to `init(canvas, device)` reads
`navigator.gpu` for its preferred format (`bgra8unorm` where there is none) —
pass `CanvasSurface(canvas, format = …)` to skip it. `target` is a `PixelFrames`
/ `PixelImage` (offscreen: `show` reads back into it), a canvas, or a `Surface`
— `CanvasSurface(canvas, format = …)` for a canvas format other than the
preferred one. A target with a `frames` or `data` field is taken as a pixel
target; anything else that is not a `Surface` as a canvas, so a non-DOM canvas
(e.g. NativeScript) works too. The `gpu` overload has no `(setup)` form — Scala
allows default arguments on only one overload — so chain on its promise. Options
of `init(target, gpu, …)`:

| Option                 | Default | Effect                                                                      |
| ---------------------- | ------- | --------------------------------------------------------------------------- |
| `powerPreference`      | none    | `PowerPreference.HighPerformance` / `LowPower` — a hint to `requestAdapter` |
| `forceFallbackAdapter` | `false` | ask for the software adapter (none in node-webgpu 0.6.2 on Linux, §4)       |
| `requireAdapter`       | none    | `GPUAdapterInfo => Boolean`; rejects, listing the adapter found, when false |
| `requiredFeatures`     | none    | device features, e.g. `Arr("float32-filterable")`                           |
| `requiredLimits`       | none    | device limits object                                                        |
| `logAdapter`           | `false` | prints `WebGPU adapter: vendor / architecture / device / description` once  |

`init` validates a pixel target right away and throws on an unknown format, a
non-positive size, a `frames` that is not an array, or `data` of the wrong array
type or length. The same check is public as `PixelTarget.validate(target)`, for
targets built or received outside the painter.

### 2.2 `WebGPU` helpers

```scala
WebGPU
  .requestAdapter(gpu, requireAdapter = WebGPU.adapterNamed("nvidia"))
  .flatMap(a => WebGPU.requestDevice(a, requiredFeatures = Arr("float32-filterable")))
  .map(device => Painter.init(PixelFrames(1024, 1024), device))
```

- `WebGPU.requestAdapter(gpu, powerPreference, forceFallbackAdapter, requireAdapter, logAdapter)`
- `WebGPU.requestDevice(adapter, requiredFeatures, requiredLimits)`
- `WebGPU.adapterNamed(name)` — case-insensitive substring match over vendor,
  architecture, device and description.
- `WebGPU.describe(info)` — the one-line description used in logs and errors.

### 2.3 Feature-dependent setups

**`rgba32float` output needs `float32-filterable`.** On an `rgba32float` pixel
target panels default to `rgba32float`, and the `show` blit samples them with a
filtering sampler. Pass `requiredFeatures = Arr("float32-filterable")`; every
desktop GPU offers it. Without it `init` throws right away with that hint. Where
the feature is missing, use `rgba16float`. `showTo` into an `rgba32float` target
needs nothing extra — only an `rgba32float` _panel_ needs the feature.

### 2.4 What the painter holds

| Member            | What                                                               |
| ----------------- | ------------------------------------------------------------------ |
| `device`, `queue` | the device                                                         |
| `surface`         | `CanvasSurface`, or the internal capture surface of a pixel target |
| `gpu`             | the `GPU` it was created from; null after `init(target, device)`   |
| `adapter`         | the adapter it was created from; null after `init(target, device)` |
| `adapterInfo`     | its `GPUAdapterInfo`; null after `init(target, device)`            |
| `preferredFormat` | the surface format: default panel format and `show` target         |
| `width`, `height` | the target size: the surface's, or the `atSize` override           |
| `canvas`          | the canvas; throws on an offscreen painter                         |

Holding `gpu` and `adapter` keeps them alive as long as the painter (§6).

## 3. Getting a `GPU` per runtime

Getting the `GPU` object is the only runtime-specific line, and it is always
user code.

- **Node** — npm `webgpu` (node-webgpu, Dawn): `create([...dawnOptions])`.
  Verified on Node 24.
- **Bun** — the same package: Bun loads node-webgpu's Node-API addon, and the
  same driver script runs unchanged (verified on Bun 1.4.2). If Bun's native
  WebGPU lands, pass `navigator.gpu` instead.
- **Browser** — `navigator.gpu`, read by the caller (`WebGPU.getGPU()`), or
  `Painter.init(canvas)`.

The driver hands the `GPU` straight to the exported Scala function: no
`navigator` shim, and no `Object.assign(globalThis, globals)` — the painter
carries its own WebGPU constants. The examples run from the repo root after
`bun run examples:build`:

```bash
node examples/headless_triangle/render.node.ts --backend vulkan
bun examples/headless_sequence/render.node.ts
```

Doing it all in Scala instead is equally valid — import `create` and build the
pixel target there:

```scala
@js.native
@JSImport("webgpu", "create")
def createGpu(options: js.Array[String]): GPU = js.native

@JSExportTopLevel("main")
def main(): js.Promise[Unit] =
  val out = PixelImage(256, 256)
  Painter
    .init(out, createGpu(Arr()))
    .flatMap: p =>
      p.paintAndShow(p.panel(clearColor = (1.0, 0.5, 0.0, 1.0)))
      p.awaitReadback()
```

Encoding then goes through an npm package via `@JSImport` as well.

## 4. Adapter selection

WebGPU cannot enumerate adapters; it hands out one per request and describes it
in `adapter.info`. Selection therefore splits in two layers.

**Runtime level, before the `GPU` exists** — node-webgpu's Dawn options at
`create()`:

| Goal              | node-webgpu                                                       |
| ----------------- | ----------------------------------------------------------------- |
| list adapters     | `create(['adapter=?'])` throws, listing `backend` and exact names |
| pick a backend    | `create(['backend=vulkan'])` (`opengl`, `opengles`, `null`, …)    |
| pick by name      | `create(['adapter=<exact name from the list>'])`                  |
| software renderer | pick Mesa's `llvmpipe (…)` by name                                |

`examples/headless_triangle/render.node.ts` maps these to flags: `--backend`,
`--adapter`, `--software`, `--require <text>` and `--list-adapters`, which also
prints what the default, `high-performance`, `low-power` and fallback requests
return.

`forceFallbackAdapter: true` returns **no adapter** in node-webgpu 0.6.2 on
Linux: that build ships no SwiftShader. llvmpipe, listed as a Vulkan adapter, is
the software path, and renders identically to a hardware GPU.

**Linux, below WebGPU** — env vars for the Vulkan loader and Mesa, set before
the runtime starts:

- `VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json` (or `radeon_icd…`,
  `lvp_icd…` for lavapipe) restricts the loader to one driver
  (`VK_ICD_FILENAMES` is the older name).
- `MESA_VK_DEVICE_SELECT=list` prints devices; `MESA_VK_DEVICE_SELECT=vid:did`
  picks one.
- GL backends: `__GLX_VENDOR_LIBRARY_NAME=nvidia`,
  `__NV_PRIME_RENDER_OFFLOAD=1`.

**Painter level — verify, don't trust.**
`requireAdapter = WebGPU.adapterNamed("nvidia")` rejects the promise with the
adapter that was found, so a render never silently runs on llvmpipe.
`logAdapter = true` prints it; the headless examples keep it on.

| Symptom                        | Check                                                                       |
| ------------------------------ | --------------------------------------------------------------------------- |
| `Failed to get WebGPU adapter` | the backend exists here? `--list-adapters`; `forceFallbackAdapter` is off   |
| wrong GPU picked               | `--adapter <exact name>`, or `VK_DRIVER_FILES`; guard with `requireAdapter` |
| CI without a GPU               | install Mesa (lavapipe / llvmpipe) and pick it by name                      |
| `requireAdapter` rejects       | the message lists vendor / architecture / device / description              |

## 5. `PixelFrames` and `PixelImage`

### 5.1 The contract

Plain JS objects; TS declares them in `examples/example_helpers/pixels.d.ts`,
Scala builds them with `PixelFrames(w, h, format)` / `PixelImage(w, h, format)`
/ `PixelImage(w, h, data)`.

```ts
type PixelFormat = 'rgba8unorm' | 'rgba16float' | 'rgba32float'
type PixelData = Uint8Array | Float16Array | Float32Array // matches format

interface PixelFrames {
  width: number
  height: number
  format: PixelFormat
  frames: PixelData[]
}
interface PixelImage {
  width: number
  height: number
  format: PixelFormat
  data: PixelData
}
```

- **`PixelFrames`** — every `show` appends one frame, in `show` order.
- **`PixelImage`** — every `show` overwrites `data` **in place**, so the
  reference stays valid and a host buffer (e.g. pngjs's `png.data`) can be the
  target directly. When several `show`s are in flight, the last one wins.

### 5.2 Formats

| `format`      | Array          | Bytes / px | Use                                                        |
| ------------- | -------------- | ---------- | ---------------------------------------------------------- |
| `rgba8unorm`  | `Uint8Array`   | 4          | display frames, 8-bit PNG, video (the default)             |
| `rgba16float` | `Float16Array` | 8          | HDR stills and sequences; values above 1 survive           |
| `rgba32float` | `Float32Array` | 16         | data bakes, numeric processing; needs `float32-filterable` |

`Float16Array` is ES2025: Chrome 135, Firefox 129, Safari 18.2, Node 24, Bun
1.4.

### 5.3 Layout and conversion

Rows tightly packed (no row padding), **top row first**, RGBA, straight alpha,
length `width · height · 4`. A panel the size of its target comes out exact.
Another size is scaled (nearest sampling), another format converted: floats
never clamp, `rgba8unorm` clamps to `[0,1]`. An HDR chain needs float panels
throughout — on a canvas painter panels default to the canvas format, so give
them an explicit float format before an HDR `showTo`.

### 5.4 When the data is there

- **`p.awaitReadback()`** resolves once every pending readback has landed,
  `showTo`'s included. **A pixel target is complete only then**; before, its
  contents are undefined. With nothing pending it resolves immediately.
- **`p.showTo(panel, into)`** resolves with `into` once its own readback has
  landed; it does not wait for others.

### 5.5 Memory

A loop that never yields keeps every frame — and, until the loop ends, a staging
buffer per frame — in memory: about 8 MB per 1080p `rgba8unorm` frame, 16 MB for
`rgba16float`. Fine for stills, short loops and GIFs; minutes of video need
streaming, which is planned but not there yet.

### 5.6 Host-side recipes

- **PNG, Node / Bun** — `encodePng(w, h, data, 8 | 16)` from
  `examples/example_helpers/png.ts` (zero dependencies), or `pngjs` / `sharp`.
- **mp4 / gif** — `encodeVideo(frames, w, h, fps, path)` from
  `examples/example_helpers/ffmpeg.ts`: ffmpeg reads raw `rgba` frames from
  stdin; `.gif` gets a palette pass.
- **Browser** — an `rgba8unorm` frame becomes an `ImageData` without copying:
  `new ImageData(new Uint8ClampedArray(d.buffer, d.byteOffset, d.length), w, h)`.
  `example_helpers/download.js` turns it into a PNG download.

## 6. Process lifecycle

Verified on Node 24.16 and Bun 1.4.2 with node-webgpu 0.6.2:

- **The process exits on its own** once the render promise has resolved — also
  with the `GPU`, the painter and its device still referenced. No `process.exit`
  needed.
- **`process.exit(0)` right after `awaitReadback`** is safe: clean exit, no
  driver errors, across repeated runs.
- **The `GPU` must stay alive while the device is in use.** Dawn's device does
  not keep its instance alive; once the `GPU` object is garbage-collected,
  node-webgpu aborts the process (`futex`, `pthread_mutex_lock` or
  `std::system_error` crashes). A painter from `init(target, gpu, …)` or
  `init(canvas)` holds the `GPU` and its adapter (`p.gpu`, `p.adapter`), so
  `render(create([]), out)` is safe while the painter is in use. After
  `init(target, device)` the caller has to keep the `GPU` referenced.

There is no `destroy()` yet. GPU memory is reclaimed at process end, which is
all render-and-exit scripts need; explicit freeing for long-running hosts is
planned.

## 7. Testing with a GPU

`test/graphics/painter/HeadlessPainter.test.scala` renders through node-webgpu
and asserts on the pixels: exact clears, orientation, HDR, frame order,
`showTo`, `atSize`, validation. It runs with `bun run test` and skips itself
when `webgpu` is not installed or no adapter is found. Pin an adapter with
`TRIVALIBS_WEBGPU_ADAPTER="<exact name>"` (e.g. llvmpipe in CI) or a backend
with `TRIVALIBS_WEBGPU_BACKEND`.
