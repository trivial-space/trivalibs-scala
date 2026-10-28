# Headless painter and texture sources — follow-ups

**Status: collected, not scheduled.** Everything deferred from
[done/headless-painter-and-texture-sources-plan.md](done/headless-painter-and-texture-sources-plan.md)
(the core plan, M1 and M2, landed and archived). Each item is independent,
designed far enough to add to the shipped API without changing it, and waits for
a sketch or a host that needs it. Section references like "core §4.2" point into
the core plan.

## 1. Animated backgrounds

A source is a static background: uploaded on `set`, redrawn unchanged on every
paint. The same slot in the contributor stack (core §4.2) — drawn first, under
the shapes, without touching depth — is just as useful for backgrounds that
change every frame: an animated pattern, a sky that turns with the camera, the
output of another panel. Two independent additions get there.

### A. Another panel as `source`

`source` also accepts a `Panel` or a `PanelBinding` (an MRT target, or a mip
level as a cheap blurred background). The source draw stays as it is; only where
its texture comes from changes.

- **No upload.** `set` reads the size and pixel format from the other panel.
  `flipY` / `premultipliedAlpha` don't apply, and neither does the path
  switching (core §4.2, "Paths"): there is no own source texture to adopt or
  restore.
- **Always painted.** Never the direct path and never source-only, since the
  content changes whenever the other panel paints. Same ordering rule as any
  panel binding: paint the source panel first.
- **Size.** Without `width` / `height`, the panel follows the other panel's
  current size, resolved in `ensureSize` on each paint — like `followSurface`,
  tracking a panel instead of the surface.
- **Bind group cache.** `sourceGroup` is built once today. Another panel's slot
  0 view changes on reallocation and on every pong swap (`swapPair` bumps
  `bindEpoch`), so the cache is keyed on `(panelId, bindEpoch)`, the pattern the
  layer bind cache already uses.
- **Checks.** A panel can't be its own source (WebGPU forbids sampling the
  render target). The `rgba32float` / `float32-filterable` check carries over.

Estimate: 80–120 lines, plus tests, docs and an example.

### B. Fullscreen shapes in the shape pass

The TS painter's model: a layer (then "effect") was a specialization of a shape.
In the layer stack it ran as a post-processing pass after the shapes; in the
shape stack it was one more draw — a fullscreen quad — in order with the other
shapes. Carried over:

- **A depth mode on shapes.** The pipeline key already has one (`n` / `a` / `l`)
  for the source draw's `depthAlways`; expose it as a shape option. "Always, no
  writes" is what a background needs — today a user's fullscreen shape at the
  far plane is discarded by `less` against the cleared 1.0.
- **A layer shade as a shape.** A fullscreen draw (`draw(3)`, no vertex buffer,
  `uv` in) of a layer shade, listed in `shapes`. It resolves bindings the way
  shapes do (panel-level bindings fill unbound slots) and gets the shape pass's
  MSAA / depth / blend pipeline variants. The auto-injected `src` slot has no
  meaning there — it would read the target being written — so it must be bound
  explicitly, or the shape is rejected.

A sky is then a layer shade that turns `uv` and the inverse view-projection into
a direction and samples an environment map, listed first in `shapes` with depth
"always". Another panel as background is the same with a blit shade bound to
that panel — B covers A's use cases, minus the automatic sizing and the
zero-shader convenience.

**Order.** B first: it is the general one, and it reuses the existing ordering
model (the shape list) instead of adding a new contributor. A can follow as
sugar if `source = panel` turns out to be wanted often.

**Considered, not chosen:** a layer shade as `source`. It would make `source`
hold both uploaded and generated content, while B places generated backgrounds
where generated content already lives, among the shapes.

## 2. Fit modes for sources

When panel and source sizes disagree, the source is stretched. A follow-up adds
a choice modeled on a subset of CSS `background-size` / `background-position`:

- `stretch`, the default;
- `cover`, which fills and crops;
- `contain`, which fits whole and leaves the rest showing the clear color or the
  previous content;
- `none`, at the source's own pixels;
- an alignment for the last three.

All of them are a UV scale and offset in the background draw, plus discarding
fragments outside `[0,1]` for `contain` and `none`. The draw path already
carries everything this needs, so adding the feature changes nothing else.

## 3. `WebVideo.autoUpload`

Until the first video sketch, a video works by calling
`panel.set(source = video)` by hand. The helper, for then:

```scala
// trivalibs.graphics.painter.WebVideo
/** Re-`set`s `video` as `panel`'s source on every newly presented video
  * frame. Returns a stop function. */
def autoUpload(video: dom.HTMLVideoElement, panel: Panel): () => Unit
```

It is event-driven, not tied to `paint`:

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

**Needs:** `requestVideoFrameCallback` / `cancelVideoFrameCallback` facades on
`HTMLVideoElement` in `webgpu.scala` (or next to `WebImages`).

**To verify first:** Baseline status of `requestVideoFrameCallback` in all
target browsers, that it fires after a seek while paused, and how it orders
against `requestAnimationFrame` (it should run before the frame's rAF; otherwise
a frame of upload latency is acceptable).

## 4. Zero-copy external textures

Video goes through copy-per-frame today (core §4.4), one GPU copy per frame —
negligible below 4K. Zero-copy external textures need a new binding kind
(`texture_external` + a DSL marker beside `FragmentPanel`), per-frame bind group
rebuilds, no mips, and are missing on Firefox, node-webgpu and Bun's PR. Add
`p.videoTexture(video)` as an opt-in binding only if a profile asks for it; the
panel path stays.

**Needs:** `GPUDevice.importExternalTexture` in the facade.

**To verify first:** Firefox `importExternalTexture` and
`copyExternalImageToTexture(VideoFrame)` current status.

## 5. Frame streaming

All frames of a sequence end up in the container before the host sees any, and
every frame holds a staging buffer until a synchronous loop ends (core §3.7):
fine for stills, short loops and GIFs, impractical for minutes of video. For
long sequences the container gains an optional field instead of a new API — so
core §3.5 stays valid unchanged:

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
stream, so `awaitReadback()` also resolves only once the writer is ready again
(`writer.ready`, the stream's backpressure). Awaiting it per frame bounds
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

Streaming **input** (a decoded video handed in as `PixelFrames`, core §4.4) has
the same all-in-memory limit and belongs to the same enhancement.

## 6. Smaller API follow-ups

- **A public `read`**, e.g. for MRT slots. `show` / `showTo` is the only public
  readback (core §3.4); add `read` only if really needed.
- **A second texture set per panel for repeated `atSize` captures.** Each
  `atSize` scope reallocates every surface-following panel twice (core §3.9).
  Capturing every frame at a second resolution next to the canvas (recording 4K
  while previewing) would thrash. The fix: a second texture set per panel, keyed
  by the override size and swapped instead of reallocated. Doubles memory, but
  each size keeps its own history.
- **Cheaper reallocation in the cheap cases.** Every allocation change
  reallocates all textures (core §4.2, "Allocation"). A newly needed pong slot
  could only append slot 1, and `depthTest` could only run `allocDepth()`.
- **A Scala-side codec layer**: a small PNG encoder over `CompressionStream`,
  which every runtime has. Encoding is host side by design (core §1.4, §3.8);
  possible, not planned.

## 7. Open verifications

- `rgba32float` capture and readback on node-webgpu's backends (Vulkan, and the
  software fallback).
- Bun native WebGPU: merge status of the PR stack, backend env var, whether
  `GPUAdapterInfo` is populated. Only relevant once it lands.
- The video items under §3 and §4.

## 8. Consumer side, not trivalibs

- **Sketch entry for offline renders.** Deferred, user land in the consuming
  repo, and may change with real usage. The starting intent is strict
  separation: an offline render is its own sketch folder, with its own code
  modules, a script with no `index.html`.
- **The bake-or-load switch** (core §4.5), proposed separately in `sketchlib`.

## Related, tracked elsewhere

- [independent-todos.md](independent-todos.md): MRT with one generative layer,
  and explicit GPU-resource freeing (`Painter.destroy()`).
- [non-browser-hosts-plan.md](non-browser-hosts-plan.md): native-window
  surfaces, frame scheduling (`Surface.requestFrame`, `p.animate`), whether
  browser sketches take a `Surface`, and moving `Painter.input` onto
  `CanvasSurface`.
- [opt-maybe-extension-shadowing-plan.md](opt-maybe-extension-shadowing-plan.md):
  the library trap found in M1.1.
