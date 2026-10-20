# Native-window hosts — Deno windows, NativeScript

**Status: planned, not scheduled.** A separate issue from offline rendering.

**Depends on**
[headless-painter-and-texture-sources-plan.md](headless-painter-and-texture-sources-plan.md),
which brings in the host seam (`Surface`, `CanvasSurface`,
`Painter.create(surface, gpu | device)` with the GPU supplied by user code) and
the headless `CaptureSurface`. This document covers only what is left:
**rendering on screen outside a browser**. The trait has to admit these hosts
with one additive change — frame scheduling, below; §3.8 of that plan lists the
constraints this document puts on it.

## Frame scheduling

Offline renders don't need a scheduler — the script owns the loop with a fixed
`tpf`. On-screen hosts do: the host owns the frame rate, and Deno's raw backend
has no `requestAnimationFrame`. So this plan adds:

- `Surface.requestFrame(cb: Double => Unit)` — `CanvasSurface`:
  `requestAnimationFrame`; `DenoWindowSurface`: `setTimeout` loop; NativeScript:
  its rAF, to verify.
- `Animator` takes the scheduler instead of calling the global
  `requestAnimationFrame`.
- `p.animate(frame)` routed through `surface.requestFrame`. The free `animate`
  stays as the browser convenience; sketches move to `p.animate` only where they
  want to run on a window host. `p.animate` is also what lets
  `Painter.destroy()` stop the loop (see `independent-todos.md`, GPU resources).

## Motivation

Two on-screen hosts are wanted:

1. **Deno windows** — Deno ≥ 2.9 `deno desktop` gives a native window, and
   `Deno.UnsafeWindowSurface` gives WebGPU on it. Desktop, no mobile toolchain:
   the cheap place to prove an on-screen non-browser host.
2. **NativeScript** — `@nativescript/canvas` exposes WebGPU on Android/iOS (the
   `2.0.0-webgpu.*` prerelease line). This is the actual deployment interest.

If NativeScript turns out to differ too much from the browser / Deno shape, it
stays deferred; the Deno window surface does not depend on it.

---

## Surfaces

| Surface                  | `currentTexture`                       | `present`           | `onResize`                               | `requestFrame`          |
| ------------------------ | -------------------------------------- | ------------------- | ---------------------------------------- | ----------------------- |
| `DenoWindowSurface(win)` | `surface.getContext("webgpu")` context | `surface.present()` | window `resize` → `surface.resize(w, h)` | `setTimeout` loop       |
| NativeScript             | `CanvasSurface` or a subclass          | to verify           | canvas layout event, to verify           | rAF polyfill, to verify |

### Deno (verified against the current docs)

- WebGPU needs `"unstable": ["webgpu"]` in `deno.json`; the desktop window needs
  `"desktop": { "backend": "raw" }` (not selectable via `--backend`).
- `win.getNativeWindow()` returns the `Deno.UnsafeWindowSurface`, and **throws
  unless `navigator.gpu.requestAdapter()` has run first**. So the surface must
  build its context in `configure(device)`, not in its constructor —
  `Painter.create` already resolves the device first.
- `surface.getContext("webgpu")` → `configure({ device, format, alphaMode })`.
- `surface.width/height` follow `win.getSize()`, updated on the window's
  `resize` event; `UnsafeWindowSurface.resize(w, h)` exists, so it resizes in
  place (the old SDL2 question about having to recreate it is moot).
- `surface.present()` once per frame, after rendering; without it the window
  stays blank.
- No `requestAnimationFrame` in the raw backend; the docs loop with
  `setTimeout(frame, 16)`.
- Once a surface has been taken, `close()` hides the window instead of
  destroying it; call `Deno.exit()` to end the process.

The older route (SDL2 via `jsr:@divy/sdl2`, `win.windowSurface()`) is superseded
by `deno desktop`.

### NativeScript (unverified)

`@nativescript/canvas` follows the browser shape: `canvas.getContext("webgpu")`,
`configure`, a `requestAnimationFrame` loop. Open:

- whether presenting is implicit (browser-like) or an explicit call;
- whether `requestAnimationFrame` is provided by `@nativescript/canvas`;
- which event signals a canvas resize;
- whether `getPreferredCanvasFormat()` exists (fall back to `bgra8unorm`);
- the prerelease line drifts — pin an exact version.

If the answers are "browser-like", NativeScript is just `CanvasSurface`. A
subclass with an explicit present is still fine. Anything that needs `Surface`
or `Painter` changes is the signal to rethink the trait.

---

### Watched, not planned: Perry / Bloom Engine

[Bloom](https://bloomengine.dev/) (MIT, `@bloomengine/engine` 0.4.x, very
active) is a raylib-shaped game engine: game code in TypeScript, compiled ahead
of time by [Perry](https://github.com/PerryTS/perry) (SWC + LLVM → native
binary; also `--target web/wasm`), rendering in a Rust **wgpu** core.

- **No WebGPU interface for JS.** TypeScript never touches the GPU. It calls
  ~465 flat `bloom_*` functions declared via Perry's
  `perry.nativeLibrary.functions` (C ABI on native, `wasm_bindgen` on web):
  `bloom_begin_drawing`, `bloom_clear_background(r,g,b,a)`, `drawRect`, … The
  only shader hook is `loadShader(wgslSource): number` — no pipelines, buffers,
  bind groups or render targets. The painter cannot run on Bloom, and wrapping
  its API would mean reimplementing the painter on raylib-level calls. Not a
  target.
- **Perry itself is the interesting part**: a TS/JS → native compiler that
  compiles plain `.js`, implements most `node:*` modules, and — per its
  changelog — complete `bun:ffi` / `node:ffi` C-ABI support (pointers, zero-copy
  memory views, callbacks). A Perry host would be: Scala.js bundle → Perry
  binary → a WebGPU JS API over FFI. Perry ships no WebGPU API; the candidate is
  `bun-webgpu` (Dawn via `bun:ffi`), which may run unchanged on Perry's
  `bun:ffi`. Native `.node` addons (so node-webgpu) are **not** supported.
- **Two unknowns, both cheap to test by experiment, neither verified:**
  1. Scala.js output vs Perry's limits — classes compile to fixed layouts;
     assigning to `.prototype` and `Object.setPrototypeOf` are unsupported;
     `Proxy` / `Reflect` are partial. Our `jsMode full` output does exactly the
     unsupported thing: `sketches/base-triangle/main.js` contains
     `….prototype = $p` and `….prototype = new $h_O()` (Scala.js class
     encoding). Likely a blocker unless Perry tolerates it; try `perry compile`
     on that bundle to find out.
  2. Whether `bun-webgpu`'s struct marshalling runs on Perry's `bun:ffi`. It
     also has no window surface, so on-screen output would need a separate
     surface binding.
- **What it would buy:** one self-contained native binary, iOS/Android from the
  same toolchain — overlapping the NativeScript goal. Nothing over Node/Deno/Bun
  for offline rendering.
- **Painter impact:** none. A Perry host is just another `GPU` provider plus a
  `Surface`, which is exactly the seam the headless plan introduces. Revisit if
  Perry ships WebGPU itself, or if the two experiments above pass.

## Decisions left to this plan

- **Entry-point signature.** Sketches are `sketch(canvas: HTMLCanvasElement)`.
  An on-screen host has no `HTMLCanvasElement`, so a sketch meant to run on one
  must take a `Surface` (glue: `sketch(CanvasSurface(canvasEl))`,
  `sketch(DenoWindowSurface(win))`). Recommended for portable sketches only;
  `Painter.init(canvas)` stays, so existing sketches and `examples/` keep
  compiling untouched. The `@JSExportTopLevel("sketch")` refactor already did
  most of the migration.
- **Input.** `Painter.input` needs a DOM canvas. On-screen hosts do have users,
  so this is where an input abstraction becomes worth doing (NativeScript touch,
  Deno window events). Until then, `input` should live on or require
  `CanvasSurface`, so a portable sketch can't silently depend on it.
- **Out of scope, stay browser-only:** `utils/events/*`, `dev/*` (uses
  `sessionStorage` and `window`), `preact/*`.

## Phasing

0. Frame scheduling (above) — browser-only first, no behavior change.

1. `DenoWindowSurface` + a windowed example. Proves explicit present, resize and
   the `setTimeout` loop against the `Surface` trait.
2. NativeScript, as an app under the consuming repo plus at most a
   `CanvasSurface` subclass.

## Related

- [Deno desktop WebGPU](https://docs.deno.com/runtime/desktop/webgpu/),
  [`Deno.UnsafeWindowSurface`](https://docs.deno.com/api/deno/~/Deno.UnsafeWindowSurface)
- [NativeScript canvas overview](https://blog.nativescript.org/nativescript-canvas/),
  [quick start](https://canvas.nativescript.org/canvas/quick-start),
  [installation](https://canvas.nativescript.org/canvas/installation)
- [Bloom Engine](https://bloomengine.dev/) ·
  [repo](https://github.com/Bloom-Engine/engine) (`docs/web-target.md`,
  `docs/design-api.md` "the Perry FFI boundary") ·
  [Perry](https://github.com/PerryTS/perry) ·
  [Perry limitations](https://docs.perryts.com/language/limitations.html)
- Consuming-repo entry-point convention (`@JSExportTopLevel("sketch")`, canvas
  passed in) — the graphics repo's `CLAUDE.md`.
