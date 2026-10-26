# Binding resolution — resolve slots while building bind groups

**Status: planned, not scheduled.** Internal refactor of `Painter`. No API
change, and no behavior change except the slot-0 rule below.

Came up in the review of the headless painter (PR #3): the class-layout
convention moved `workBindings` / `workPanelBindings` up with the other fields,
which made them visible enough to question.

## Today

Every draw merges its bindings from three sources. The first source that sets a
slot wins:

1. the instance (`Instance.bindings` / `.panelBindings`),
2. the shape or layer (`.bindings` / `.panelBindings`),
3. the panel's runtime bindings (`Panel.runtimeBindings`, keyed by name).

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

Problems:

- **Hidden ordering.** That no stale slot survives from the previous draw relies
  on every branch calling `copyToWork` first. Nothing enforces it.
- **Repetition.** The same three steps, in four places.
- **Little to gain from reuse.** The fields only save two small arrays per draw.
  Each draw already allocates an `entries` array, a literal per entry and a
  `GPUBindGroup`.
- **Slot 0 of a layer is decided two ways.** For ping-pong and mip-chain layers
  `renderLayerOnPass` injects `srcView`, the panel's previous output, as panel
  slot 0. On the runtime-binding and instance branches an explicitly bound slot
  0 suppresses `srcView`. On the cached static branch, `buildPanelBindGroup`
  pushes `srcView` and skips slot 0, so `srcView` wins there.

## Design

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
  length of a source array, and take the first set source: instance, then
  shape/layer, then runtime. Runtime values for a panel slot that are a `Panel`
  are wrapped in `PanelBinding(panel)`, as today. In the panel group, an unset
  slot 0 falls back to `srcView`.

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

- **The layer bind-group cache stays.** It still covers the case without
  instances and without runtime bindings, keyed by `(panelId, bindEpoch)`, and
  calls the same build functions with `inst = null`, `runtime = null`.
- **Removed:** `workBindings`, `workPanelBindings`, `copyToWork`,
  `applyPanelRuntimeBindings`, `applyInstanceBindings`, and the two duplicated
  `effectiveSrcView` checks.

## Slot 0 rule (behavior change)

One resolver means one rule: **an explicit slot-0 binding wins over `srcView`**,
on every path. This changes the cached static branch, where `srcView` wins
today. Confirm before implementing, and check the pong / mip examples render
unchanged.

## Not in scope

- **Missing bindings.** An unset slot is skipped, and WebGPU reports the bind
  group / layout mismatch as a validation error. That is loud enough: the bind
  code and the shader's uniform interface sit close together. No extra
  painter-side check.

## Proof

- All examples render unchanged (shapes, layers, instances, panel runtime
  bindings, ping-pong, mip chains).
- Existing painter tests pass; add a test for the precedence order (instance >
  shape/layer > panel runtime) if the headless painter makes it cheap.
- `jsMode full` bundle of an example: no growth.
