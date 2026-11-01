# Binding resolution and bind-group caching

**Status: done (2026-11-01). Both steps implemented, headless-tested and checked
in the browser (examples and sketches). Archived.** Where the implementation
differs from the design below, the [dev log](#dev-log) says so. Internal
refactor of `Painter` in two steps, each shippable and checkable on its own. No
API change.

- **Step 1 — resolver.** One code path that resolves each slot while building
  its bind group. Behavior changes: the slot-0 rule, the auto-pong rule (incl.
  clearing the pong pass), and a missing binding throws.
- **Step 2 — cache.** Both bind groups cached per owner, for shapes and layers
  alike. Behavior change: stale cached layer bind groups are fixed.

Came up in the review of the headless painter (PR #3): the class-layout
convention moved `workBindings` / `workPanelBindings` up with the other fields,
which made them visible enough to question.

## Today

Every draw merges its bindings from three sources. Per slot, the more specific
source takes precedence:

1. the instance (`Instance.bindings` / `.panelBindings`), over
2. the shape or layer (`.bindings` / `.panelBindings`), over
3. the panel's runtime bindings (`Panel.runtimeBindings`, keyed by name).

This precedence is the contract; the merge below is only how it is implemented
today.

The merge works through two reused painter fields, `workBindings` (group 0) and
`workPanelBindings` (group 1):

- `copyToWork` truncates both to the shape's or layer's length and copies its
  slots in;
- `applyPanelRuntimeBindings` walks the panel's runtime keys and fills the empty
  slots, found through `shade.uniformIndices` / `panelIndices`;
- `applyInstanceBindings` overrides the slots the instance sets;
- `setValueBindGroup` / `setPanelBindGroup` then build each group's `entries`
  array from the merged slots and create the bind group.

`renderShapeOnPass` and `renderLayerOnPass` repeat this sequence in four
branches: with or without instances, times with or without runtime bindings.

Only static layers (no instances, no runtime bindings) cache their bind groups,
in `LayerBindCache`, keyed by the target panel's `(panelId, bindEpoch)`.
Everything else builds both groups on every draw.

Problems:

- **Hidden ordering.** That no stale slot survives from the previous draw relies
  on every branch calling `copyToWork` first. Nothing enforces it. _(step 1)_
- **Repetition.** The same three steps, in four places. _(step 1)_
- **Slot 0 of a layer is decided two ways.** For ping-pong and mip-chain layers
  `renderLayerOnPass` injects `srcView`, the panel's previous output, as panel
  slot 0. On the runtime-binding and instance branches an explicitly bound slot
  0 suppresses `srcView`. On the cached static branch, `buildPanelBindGroup`
  pushes `srcView` and skips slot 0, so `srcView` wins there. _(step 1)_
- **A layer pongs although no draw reads `srcView`.** `autoPongsSlot0`
  (`layer.scala`) looks only at the layer's own slot 0. A layer whose instances
  each bind slot 0 (deferred-style blending, one additive draw per instance)
  still ping-pongs: the pass loads the pong scratch, which holds an old result,
  the instances blend over that stale image, and the swap moves the live result
  into the scratch, losing it. The same happens when panel runtime bindings fill
  slot 0. _(step 1)_
- **A pong pass composites over stale pixels.** It opens the scratch with
  `loadOp = "load"`. A single full-screen draw without blending overwrites it,
  but blending instances or partially covering draws composite over the old
  content. _(step 1)_
- **Missing bindings fail late.** An unset slot is skipped, and WebGPU reports a
  bind group / layout mismatch that names no uniform. _(step 1)_
- **Group 0 is rebuilt although it never changes.** It holds only buffers and
  samplers. `BufferBinding.gpuBuffer` is a `val`, and `.bind` with a raw value
  writes into the slot's existing buffer (`storeUniformValue` in
  `derive.scala`). Yet a static scene of shapes with only uniforms allocates a
  group-0 `GPUBindGroup` per shape per frame. _(step 2)_
- **Group 1 is rebuilt although its textures rarely change.** Most bound panels
  — texture panels, composites, mip stacks — keep their views indefinitely, yet
  every shape draw and every non-static layer draw rebuilds group 1. _(step 2)_
- **The layer cache misses changes to sampled panels (suspected bug, not yet
  reproduced).** The cache key only guards `srcView`. A static layer that binds
  another panel B keeps its cached group when B changes: after B's `swapPair` it
  samples B's old slot 0 (now the pong scratch), and after B's `ensureSize`
  realloc it references destroyed textures. _(step 2)_

## Step 1 — resolver

Resolve each slot from its sources directly while building `entries`. The work
arrays and the merge step go away.

- **`Shade` gets `uniformNames` / `panelNames`** (`Arr[String]`, slot index →
  name), built next to `uniformIndices` / `panelIndices` at shade creation. A
  slot's runtime binding is then one `runtimeBindings.at(names(i))` lookup,
  instead of walking the panel's keys and pushing into a work array.
- **One resolver per group.** `buildValueBindGroup` / `buildPanelBindGroup` take
  all sources:

  ```scala
  private def buildValueBindGroup(
      shade: Shade[?, ?],
      bindings: BindingSlots,
      inst: Opt[Instance[?, ?]],
      runtime: Opt[Dict[PanelBindingValue]],
  ): Opt[GPUBindGroup]
  ```

  They loop over the shade's declared slots (`names.length`), not over the
  length of a source array, and resolve each slot by the precedence above:
  instance, then shape/layer, then runtime. A runtime `Panel` value resolves
  straight to its view, without a per-draw `PanelBinding(panel)` wrapper. In the
  panel group, an unset slot 0 falls back to `srcView`.

- **One call per draw.**
  `setBindGroups(pass, shade, bindings, panelBindings, inst, runtime, srcView)`
  sets both groups. Both pass functions reduce to:

  ```scala
  val runtime =
    if hasPanelRuntimeBindings(panel) then panel.runtimeBindings else null
  if instanceCount == 0 then
    setBindGroups(pass, shade, x.bindings, x.panelBindings, null, runtime, srcView)
    draw()
  else
    var i = 0
    while i < instanceCount do
      setBindGroups(pass, shade, x.bindings, x.panelBindings, instances(i), runtime, srcView)
      draw()
      i += 1
  ```

- **`LayerBindCache` stays for now.** The static layer branch keeps its cache,
  but builds through the same resolver (`inst = null`, `runtime = null`), so
  static layers do not lose their cache between the two steps. The stale-layer
  bug stays until step 2 — decided: the library has few independent users yet,
  so no stopgap.
- **Removed:** `workBindings`, `workPanelBindings`, `copyToWork`,
  `applyPanelRuntimeBindings`, `applyInstanceBindings` and the two duplicated
  `effectiveSrcView` checks.

### Slot 0 rule (behavior change)

One resolver means one rule: **an explicitly bound slot 0 always wins over
`srcView`**, on every path (decided). Today `srcView` wins only on the cached
static branch of a **mip-target layer** that binds slot 0 itself: a pong layer
never has its own slot 0 bound (see the auto-pong rule), but the mip path passes
`srcView` regardless. Check the mip examples render unchanged.

### Auto-pong rule (behavior change)

**A layer ping-pongs on a panel only if some draw of it would resolve slot 0 to
`srcView`.** The decision is made in the panel's layer loop in `paint`, where
the panel is known, so it can check every source: the layer's own slot 0, each
instance's slot 0, and this panel's runtime binding for `panelNames(0)`. A few
null tests per layer per frame. `autoPongsSlot0` takes the panel and implements
this.

Ping-pong happens between layers, never between the instances of one layer: a
pong layer renders all its instances in one pass into the pong scratch, every
instance's `srcView` is the same live slot 0, and `swapPair` runs once after the
pass. So:

- **Pong if at least one draw leaves slot 0 empty.** With no instances, that is
  the layer itself (after runtime). With instances, any one instance is enough.
- **Mixed instances are allowed.** In a pong pass, an instance that fills slot 0
  reads its own texture, the others read `srcView`; all of them render into the
  same scratch.
- **If every draw fills slot 0, no pong.** The layer renders onto the live
  target like any blend layer — the deferred-lighting case.

**The pong pass clears its target** (`loadOp = "clear"`, transparent black)
instead of loading it. The scratch holds an old result (the slot-0 content from
before the last swap), so loading it makes any blending or partially covering
draw composite over stale pixels, which then become the live result. With the
clear, a pong layer's output is exactly the composite of its draws over black,
each reading the same `srcView`. A single full-screen draw without blending —
the normal pong layer — renders the same as today. Clearing costs no more than
loading (less on tile-based GPUs).

That gives layers one consistent pair of semantics: **a layer that reads
`srcView` replaces the result; a layer that does not composites onto it.**

Accepted trade-off (decided): a single instance that leaves slot 0 empty flips
the whole layer from compositing to replacing. That needs slot 0 unset on the
instance, the layer and the panel's runtime bindings at once — a setup that
reads as an intent to pong, not an accident.

Check before implementing: the layers with a blend state in the consuming repo
(`rooms/canvases`, `experiments/strokes/study1`, `paintings/tile-strokes`,
`paintings/one-line`) are presumably not pong layers; verify none relies on the
loaded scratch.

### Missing bindings throw (behavior change)

A declared slot that no source sets is an error. The resolver throws
`jsError(s"…")` naming the shade's slot, instead of skipping it and leaving
WebGPU to report a layout mismatch. Only panel slot 0 of a layer is exempt; it
falls back to `srcView`.

A slot cannot be filled with a placeholder instead: the painter has no way to
know which level (instance, shape or layer, panel) the value belongs to. The
check is a null test the resolver makes anyway, so it is kept (decided). After
step 2 it runs only when a group is built.

### Step 1 proof

- All examples render unchanged (shapes, layers, instances, panel runtime
  bindings, ping-pong, mip chains). _Done: browser check by hand._
- Existing painter tests pass. _Done: all 39 suites._
- Precedence order (instance > shape/layer > panel runtime). _Done:
  `HeadlessPainterTest`._
- A shape with an unbound uniform throws, naming the slot. _Done: tested on an
  unbound layer uniform._
- Auto-pong, headless: a blend layer whose instances all bind slot 0 composites
  onto the live result (no swap); an additive pong layer with mixed instances
  outputs exactly the sum of its draws over black, each reading the same
  `srcView`. _Done; both tests fail against the old pong decision and against
  `loadOp = "load"`._
- `jsMode full` bundle of an example: no growth. _Measured: +154 B minified (≈
  +45 B gzipped) per example, from the missing-binding message,
  `Shade.slotNames` and `Layer.pongsOn`._

## Step 2 — cache

Both groups are cached on the draw's **owner**: the instance for an instanced
draw, otherwise the shape or layer. The cache sits inside `setBindGroups`, which
gains an `owner` parameter; on a miss it calls the step-1 resolver. Draws whose
target panel supplies runtime bindings bypass the cache: `runtimeBindings` is a
plain mutable `Dict` with no change signal, so such draws build per draw, as
today. Decided: accept the bypass. A change signal (a private `runtimeBindings`
behind a setter that bumps a version) is an API change, worth it only once a
sketch with heavy runtime bindings shows the cost.

Two counters decide whether a cached group is still valid:

- **`Bindable.bindVersion: Int`** — a slot of this owner now refers to a
  different object. Covers both groups.
- **`Panel.bindEpoch: Int`** — a view of this panel changed. Covers group 1.

### `bindVersion`

Bumped by `.bind` only when a slot's reference changes: a slot bound for the
first time (`null` → object), or a different `BufferBinding` / `GPUSampler` /
`Panel` / `PanelBinding` passed explicitly. A raw value written into the
existing buffer keeps the reference and does not bump it, so per-frame
`.bind("time" := t)` keeps the groups. It replaces the `onBindingsChanged` hook,
which fires on every `.bind`.

A late first bind (after the first draw) is not special: it is a `null` → object
change and rebuilds once. Because missing bindings throw, a cached group always
had every slot filled, so a late bind can only hit a slot another level already
filled.

### `bindEpoch` contract

**`bindEpoch` must be bumped whenever any view that a bind group could hold for
this panel is replaced or destroyed** — color views of any slot or mip level,
the depth view, the resolved depth view. A missed bump leaves cached bind groups
sampling a stale or destroyed texture, with no error at the bump site. The
stale-layer bug is exactly that mistake: the key ignored panels whose views
changed.

Today it is documented as "slot 0 changed" and only the target panel's epoch is
read. Bump sites after step 2 (verified: these are the only places that replace
a panel's views):

- `swapPair`, on every ping-pong pass;
- `reallocate` (called by `ensureSize`), on realloc — this also covers uploads
  through `Panel.set` that reach it;
- `allocDepth`, **new**: the lazy depth upgrade in `depthSamplingView` destroys
  and recreates the depth texture without a bump today. It is safe only because
  the first group sampling depth triggers the upgrade during its own build; it
  must bump regardless.
- `adoptSlot0AsSource`, **new** (found while implementing): it shifts slot 0's
  texture and view out into the source. A `reallocate` always follows today, but
  the shift itself changes the views.

The cache also checks the `srcView` it was built with by identity, instead of
the target panel's epoch: one reference compare that covers a swap, a realloc, a
changed `mipSource` and the same layer drawn into another panel.

To keep the contract visible to future panel work, step 2 delivers:

- **One bump helper,** `Panel.viewsChanged()`, called at every site above. A
  grep for `views(`, `textures(` and `createView` assignments in `panel.scala`
  should show each of them next to a `viewsChanged()` call.
- **A doc comment on `bindEpoch`** stating the contract above and listing the
  bump sites.
- **A rule in `trivalibs/CLAUDE.md`** under the painter section: any change to
  `Panel` that replaces, swaps or destroys a texture or view must call
  `viewsChanged()`.
- **A test per bump site** in the headless painter: swap, resize and the depth
  upgrade each change `bindEpoch`, and a cached group sampling that panel is
  rebuilt on the next draw.

### Group 0

Holds buffers and samplers only, so only `bindVersion` matters.

- **The cache** stores the group and the `bindVersion` it was built from — for
  an instance, also its parent's. A draw compares the versions; a mismatch
  rebuilds.
- **In practice it is built once.** Sketches bind every slot during setup,
  before the first draw, so the first draw is the only build.
- **Independent of the target panel,** so an owner drawn into several panels
  shares it.

### Group 1

Holds panel views. Most are fixed from draw to draw; they change only when a
panel swaps or reallocates.

- **The cache** stores, next to the group and the `bindVersion`s, the panel
  behind each slot and its `bindEpoch` at build time, in two small arrays the
  resolver fills in place. For a layer with `srcView`, the target panel is one
  of them.
- **A hit** is the version check plus `panels(i).bindEpoch == epochs(i)` per
  slot: a few integer compares, with no slot resolution and no allocation. Any
  mismatch rebuilds through the resolver and refills the arrays.
- **Shapes share one entry across panels.** They never get `srcView`, so their
  group 1 does not depend on the target. A layer with `srcView` drawn into
  several panels alternates and rebuilds on each switch — correct, as today.
- **Pong still rebuilds.** A group that samples a pong panel, or a pong layer's
  own `srcView`, misses every pass. Everything else hits indefinitely.
- **Rejected: diffing the resolved views.** Comparing each slot's
  `GPUTextureView` with the stored one needs no epochs, but resolves every slot
  on every draw. The epoch check reads only the stored panels.

### Removed in step 2

`LayerBindCache` and its static layer branch, `Panel.panelId` (only the layer
cache key reads it) and the `onBindingsChanged` hook (replaced by
`bindVersion`). `Panel.bindEpoch` stays, with the broader contract above.

### Step 2 proof

- Reproduce the stale-layer bug before the change (a static layer sampling a
  pong panel, or a panel that is resized), and keep it as a test if the headless
  painter allows. _Done: "a static layer follows a sampled pong panel across
  swaps" failed before step 2 (it showed the source before the invert, the stale
  slot-0 texture) and passes after._
- The per-bump-site tests above. _Done for `swapPair`, `reallocate` and
  `allocDepth` (epoch changes; the depth reader's group is built once, after the
  upgrade). Not covered: `adoptSlot0AsSource`._
- Count `createBindGroup` calls in `HeadlessPainterTest` by wrapping the
  device's `createBindGroup` (a plain JS method on node-webgpu's `GPUDevice`,
  same API as the browser's; only the cost differs, not the count): after the
  first frame, per-frame `.bind` of raw values creates no group-0 groups;
  rebinding a different `BufferBinding` creates exactly one; group-1 groups are
  created only for groups that sample a pong panel, and once more after a
  resize. _Done._
- The cache tests fail against three mutations: no bump in `allocDepth`,
  `bindVersion` bumped on every bind, the epoch check disabled. _Done._
- All examples render unchanged. _Done: browser check by hand, sketches
  included._
- `jsMode full` bundle of an example: no growth. _Measured against HEAD (before
  step 1): +926 to +1,183 B minified, +288 to +350 B gzipped per example
  (`panel_triangle`, `deferred`, `instances`); step 1 accounts for 154 B of it.
  The rest is the cache itself: `BindCache`, the cached branch of
  `setBindGroups`, `panelViewsCurrent`, the slot setters._

## Dev log

### Step 1 — resolver (2026-11-01)

- **Pre-check passed:** of the four blend-state layers in the sketches, the
  three `canvasShade` layers bind slot 0 (`src`) themselves and `shadowShade`
  has no panel slots, so none pongs or relies on the loaded scratch.
- **`autoPongsSlot0` split in two** instead of taking the panel: `mayPong`
  (static, conservative) still drives pong allocation (`Panel.needsPong`) and
  the config-time MRT invariant; `pongsOn(panel)` is the per-frame dispatch
  decision. `pongsOn` implies `mayPong`, so the scratch is always allocated when
  needed. `pongsOn` checks the layer's own slot 0 (via `mayPong`) and the
  panel's runtime binding before it iterates instances.
- **`uniformNames` / `panelNames`** are built in the `Shade` body by inverting
  the index dicts, not passed in, so none of the 8 constructor calls changed.
- **Test that passed by coincidence:** the all-instances composite test first
  painted twice; on the second paint the stale pong scratch equals the clear
  color, so it passed against the old pong decision too. One paint tells them
  apart. Both pong tests were then checked to fail against the old decision and
  against `loadOp = "load"`.
- **Docs:** `docs/guide/gotchas.md` explains the slot-0 rule with instances and
  panel bindings, and "reads the previous output → replaces, otherwise
  composites".
- **Verified:** clean `bun run check`; `bun run test` 39 suites (27 headless);
  bundle +154 B minified per example; examples and sketches checked in the
  browser.

### Step 2 — cache (2026-11-01)

- **Bug reproduced first:** the new test "a static layer follows a sampled pong
  panel across swaps" failed on the step-1 code, showing the source before the
  invert (the stale slot-0 texture), and passes after.
- **`srcView` checked by identity, not through the target's epoch.** `BindCache`
  stores the `srcView` slot 0 fell back to and compares it on each hit. That one
  reference compare covers a swap, a realloc, a changed `mipSource` and the same
  layer drawn into another panel, without recording the target panel among the
  sampled panels.
- **`setBindGroups(pass, x, inst, runtime, srcView)`** takes the shape or layer
  instead of its slot arrays; the owner is `inst` or `x`. One `BindCache` per
  owner, allocated on the first cached draw.
- **Panel slots compare by content.** `.bind("src" := panel)` allocates a new
  `PanelBinding` on every call, so `setPanelSlot` compares panel, index, mip
  level and depth flag; rebinding the same panel per frame does not bump
  `bindVersion`. Value slots compare by reference (`js.special.strictEquals`;
  `!=` linked `BoxesRunTime.equals`).
- **Fourth bump site:** `adoptSlot0AsSource` shifts slot 0's texture and view
  into the source. A `reallocate` always follows today; it bumps on its own
  anyway.
- **Rules recorded where future work meets them:** the `bindEpoch` doc comment,
  and two rules in `trivalibs/CLAUDE.md` (painter source layout): call
  `viewsChanged()` for any panel texture or view change; write slots only
  through `setValueSlot` / `setPanelSlot`. The open GPU-freeing todo in
  `independent-todos.md` notes that `Panel.destroy()` must call
  `viewsChanged()`.
- **Tests:** the stale-layer repro; `createBindGroup` counts (raw uniform
  updates build nothing, a different `BufferBinding` builds one, only the two
  pong-sampling groups rebuild per frame, a resize rebuilds once); epoch bumps
  for `swapPair`, `reallocate` and `allocDepth`. All four fail against three
  mutations (no bump in `allocDepth`, `bindVersion` bumped on every bind, the
  epoch check disabled). Not covered: `adoptSlot0AsSource`.
- **Bundle:** examples grow, sketches with many binds shrink. Against the code
  before step 1, examples gain +926 to +1,183 B minified (+288 to +350 B
  gzipped). `bind` / `processEntry` are `inline`, so each bind pair used to
  expand the whole slot write at its call site; it is now one setter call. That
  saves bytes per bind pair against a fixed cost of about +500 B: committed
  sketch bundles range from +370 to +510 B (one bind) to −172 B
  (`rooms/canvases`), −340 to −390 B (room templates) and −891 B
  (`experiments/grid-ceiling`).
- **Verified:** clean `bun run check`; `bun run test` 39 suites (31 headless);
  examples and sketches checked in the browser.

### Found along the way

- **Sampled depth reads 0 in the headless painter**, before either step too. Not
  investigated; moved to `independent-todos.md` (Painter).
