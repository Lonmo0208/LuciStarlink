# Lux light engine architecture

Status: validated prototype stage (see "Selected architecture" and "Validation" below).
Companion documents: `light-engine-profiling-and-results.md`, `light-engine-correctness-contract.md`.

## 1. Baseline: what Lux was before this redesign

Lux already differed structurally from vanilla before this work:

* Block opacity, emission and skylight-relevant flags are extracted from Minecraft
  block-state storage into `RegionLightData` — compact byte-per-cell arrays owned per
  region (default one chunk column, 16x16x384).
* Light is computed in those arrays with bucketed propagation queues
  (`IntBucketQueue`/`IntRingQueue`), dirty sections are tracked per region, and results
  are packed into vanilla `DataLayer`s only for sections that actually changed.
* Runtime block changes are coalesced per region in `RuntimeUpdateQueue`, executed on
  worker threads (`LuxScheduler`), and published back through the vanilla light
  engine's mailbox infrastructure (`ThreadedLevelLightEngineMixin`).

### Costs that profiling identified in that design (strict mode)

Measured with the built-in harness (`LuxServerBenchmark`, `lucistarlink.debug=true`), fresh
JVMs, on 1.21.1 + NeoForge 21.1.234 (full tables in the profiling document):

1. **Region bootstrap**: first touch of a region extracted all ~98k cells and ran a full
   skylight + blocklight compute, then published every core section, even when the
   triggering edit was a single block.
2. **Full-height material extraction** scanned every cell (`getBlockState` + material
   lookup per cell) although most sections of a loaded chunk are homogeneous air.
3. **Skylight seeding** scanned every cell of the region and checked 6 neighbours per
   cell (`seedFrontiers`) although nearly all of them are uniform direct-sky sources.
4. **Dense batches degraded into full relights**: at 2048 coalesced records the queue
   discarded the records and re-ran the whole bootstrap.
5. **Commit latency**: worker results were parked in `commitQueue` and only flushed on
   the *next* server tick (`tickRuntime` -> `flushCommits`), adding one to two tick
   boundaries per pass compared with vanilla's continuously-draining light thread.
6. **Inexact sky repairs**: the runtime skylight repair cleared/relit a heuristic box
   (radius 2 XZ / 4 Y around the change, or a "repair mask" of cut columns). The new
   differential suite proved this leaves *stale bright* cells after cuts and *misses
   re-light* beyond the box (both directions of divergence, documented in
   `light-engine-correctness-contract.md`).
7. **Cross-region gap**: with `haloChunks=0` an incremental job's propagation stopped at
   the region border, so light that vanilla would carry into the neighbouring chunk was
   lost. This gap predates the redesign; the prototype fix is flag-gated (below).

## 2. Selected architecture: homogeneity-certified working sets + exact incremental repair

The redesign keeps Lux's region/ownership model and replaces the four dominant
computational costs with certified, proportional work:

### 2.1 Homogeneity certificates for material extraction

`LuxRegionExtractor.populateHomogeneousSection` asks each `LevelChunkSection` for two
cheap certificates before touching cells:

* `hasOnlyAir()` — the section certifies opacity 0 / emission 0 for all 4096 cells; the
  extractor bulk-fills rows with `Arrays.fill`.
* otherwise `maybeHas(state -> !(getLightBlock(EmptyBlockGetter, ZERO) == 15 &&
  getLightEmission() == 0))` — if no palette entry violates, every cell of the section
  is vanilla-cached-full-opacity and zero-emission; the extractor bulk-fills 15s.

The certificate is exact because it reads the same cached `getLightBlock` value the
per-cell material path (`LightMaterialCache.lookupLight`) reads; `LevelChunkSection`
already pre-computes that value per palette entry (`maybeHas` walks the palette, not the
cells). Effect: extraction cost becomes proportional to *heterogeneous* volume.

### 2.2 Uniform-source certificates for skylight seeding

`LuxSkyLightEngine.compute` maintains a transient per-region-section bitmask while
filling direct-sky columns: a section whose cells are all level-15 direct-sky sources is
uniform. `seedFrontiersSectionSkipped` skips such sections entirely — a uniform-15
section can improve only darker neighbours, and every darker cell lives in a
non-uniform section whose own scan seeds that pair (the frontier check tests both
directions of each pair). Columns whose direct-sky walk ended clear the bit for every
section below the break, so no dark section can be misclassified. Effect: the seeding
scan becomes proportional to *non-sky* volume (measured: 224k of 224k candidate seeds
skipped on a prepared world region).

### 2.3 Exact incremental skylight repair (replaces box/mask heuristics)

`LuxSkyLightEngine.applyIncremental` is the vanilla two-queue algorithm applied to the
changed columns, with a certified early exit:

1. **Vertical walk** re-derives each changed column top-down (15-preserving direct-sky
   rule), capturing every value delta; the walk may break only once the vertical source
   is dead *and* the pre-state was consistent (an invariant the differential suite
   maintains and verifies).
2. **Decrease pass** — vanilla-style unqueue over the whole region: a neighbour holding
   less than a cleared level is stale and is cleared transitively; neighbours holding
   more become re-light sources. No bounding box, so stale light cannot survive outside
   any heuristic radius.
3. **Increase pass** — bucket-queue refill from surviving sources, from raised cells,
   and from the changed cells' neighbours (`enqueueNeighborAdds`), which is what lets a
   new opening receive up-flowing lateral light (this exact case found the original
   bug).

The same two-queue machinery is what the block engine already used; skylight now uses it
too, and the differential suite proves exact equivalence to a full-recompute reference
over randomized material grids, roof cycles and rapid toggles.

### 2.4 Change-class routing: dense batches stay incremental

`RuntimeUpdateQueue` no longer destroys records at a change-count threshold when
`LuxFlags.denseIncremental` is set (default): only the explicit bulk-write path
(structure placement with suppressed per-block records) promotes to a full region
relight, because it is the only class that *has no records to apply*. Everything else —
including 2048-change dense patches — flows through the incremental repair, whose cost
is proportional to actual light change instead of region volume.

### 2.5 Adoption-backed region initialization

`LuxRelighter.adoptEngineLight` removes the bootstrap compute entirely: for a fully
loaded, light-correct chunk the region image is initialized by *adopting* the engine's
authoritative stored light (`LayerLightEventListener.getDataLayerData` per section,
bulk-unpacked by `RegionLightData.adoptSectionData`). The adopted image *is* the
observable state, so:

* no bootstrap sky/block compute runs;
* no bootstrap publication runs (nothing changed);
* the incremental machinery starts from the same state the differential suite validates.

Materials are still extracted (now with 2.1's certificates). Adoption falls back to the
legacy full compute whenever the chunk is not light-correct or the storage read fails.

### 2.6 Direct worker publication

With `LuxFlags.inlineRuntime` (default) worker jobs publish their own results
(`lucistarlink$publish` through the sorter mailbox, which is thread-safe) instead of parking
them for the next server tick's `flushCommits`. This removes the +1..2 tick commit
latency on loaded servers; on the benchmark's idle server it is latency-neutral, which
is exactly what was measured.

## 3. How this differs from vanilla, Starlight and ScalableLux

* **Vanilla**: vanilla relights per update in sparse long-word section storage on the
  light thread, with per-update mailbox storms and heightmap-driven skylight columns.
  Lux extracts an owned material/light image per region, coalesces updates across
  ticks into batched jobs, executes them on workers, and publishes only dirty sections
  in one batched drain. After this redesign Lux additionally proves (rather than
  heuristically bounds) its incremental sky repairs and starts from adopted rather than
  recomputed state.
* **Starlight-like engines** (studied only to differentiate): Starlight keeps the
  vanilla *global* per-section storage model and swaps the propagation algorithm
  (SWAR-accelerated queues, direct swap-in-place), single-threaded, no ownership, no
  batching. Lux's defining structures — owned region images with extracted materials,
  region-level coalescing, worker execution, batched publication, and now
  per-section homogeneity certificates driving bulk extraction/seeding — do not exist
  there.
* **ScalableLux-like engines**: ScalableLux parallelizes/schedules vanilla-equivalent
  relight tasks. Lux is not a scheduler over vanilla tasks; it replaces the
  computation itself and the publication model.
* **The novel part** of this redesign: *per-section homogeneity certificates that turn
  material extraction and skylight seeding into bulk operations proportional to
  heterogeneous/non-sky volume*, plus *adoption-backed initialization* (bootstrap work
  proportional to zero cells) and *exactness-proofed change-class routing* (dense
  record-backed batches repaired incrementally instead of triggering full relights).

## 4. Candidate architectures considered

| | A. Homogeneity certificates + exact incremental repair + adoption (selected) | B. Dependency-certified directional invalidation | C. Transfer functions per section | D. Stable snapshot + delta overlay publication |
|---|---|---|---|---|
| Expected speedup | high on bootstrap/seed/extraction-heavy workloads (measured 4-12x on heavy sets) | medium (removal passes are rarely dominant after 2.3) | potentially very high for static topology, extremely hard with Minecraft shape/opacity rules | low-moderate; publication is already dirty-section-only and measured at <0.2 ms/job |
| Memory overhead | one uniformity bitmask during compute; adopted section reads | per-cell contributor metadata (>=1 byte/cell) | large per-section boundary mappings | overlay buffers per pending job |
| Complexity | moderate | high | very high | moderate |
| Correctness risk | low (proved by differential suite) | high (metadata drift) | high (exact attenuation + shape composition) | low |
| Mod compatibility risk | low (reads vanilla-cached opacity; publishes unchanged model) | high (engine-internal metadata breaks with external light writes) | high | low |
| Distinct from Starlight/ScalableLux | yes (see section 3) | partially (graph ideas exist in academic engines) | yes | no (publication detail only) |

D was implemented in spirit already (dirty-section publication); B and C remain future
research directions and are intentionally not pursued while A meets the target.

## 5. Known limitations

1. **Cross-region boundary continuation is a disabled prototype.** With
   `haloChunks=0`, a job's propagation stops at its region border. Light crossing chunk
   borders is therefore under-propagated until some event touches the neighbour region.
   This gap predates the redesign (the old compute-bootstrap overwrote border state with
   border-blind values). The prototype fix — boundary light deltas emitted from border
   shells and applied by neighbour jobs (`BorderDeltaSupport`, flag
   `lucistarlink.experimentalBoundaryDeltas`, default **off**) — is differential-tested but
   currently *oscillates* for roof-crossing batches (stale interior values are re-raised
   across the border; `CrossRegionDifferentialTest` is kept disabled with the evidence).
   Next steps: emit deltas from *every* changed cell within light radius of the border
   (not only border cells), and route removal deltas by dependency (the unqueue
   `else`-branch currently treats stale values as surviving sources).
2. **sky_hole-class workloads are parity, not faster.** Cutting a small opening re-lights
   a volume proportional to the shaft and lateral cone; vanilla and Lux both pay
   ~identical cell counts there, so end-to-end is ~1.0-1.1x. The remaining lever would be
   heightmap-driven shaft relighting (future work).
3. **Adoption trusts engine storage.** If a third-party mod writes wrong light into the
   engine storage, Lux adopts it (vanilla would also read it — Lux is no worse).
4. **Material model** is opacity/emission per cell (vanilla `getLightBlock` /
   `getLightEmission` semantics, cached per state). Shape-sensitive states are captured
   through the same calls vanilla makes; there is no directional shape model beyond that
   (same as before the redesign).

## 6. Validation summary

* Differential suite (`EngineDifferentialTest`): randomized material grids (3 seeds x
  initial + 24 edit batches + roof construction/destruction + rapid toggling), Lux
  incremental results compared cell-by-cell against a full-recompute vanilla-semantics
  reference. All pass; the suite *found and drove out* two real divergence classes in
  the previous heuristic repairs plus one seed-gap bug in the first exact rewrite.
* End-to-end benchmarks (fresh JVM, medians/mins over repeats, strict drain barrier,
  pending work verified zero at the boundary): see
  `light-engine-profiling-and-results.md`. Heavy workloads 4.3-12.3x vs vanilla;
  geometric mean over the six-workload set ~3.9-4.5x; small-update class 1.6-2.4x;
  small-opening skylight parity.
