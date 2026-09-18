# LuciStarlink — provenance, design decisions and roadmap

## 1. Why this base

Three engines were considered as the skeleton for a three-in-one mod:

| Candidate | Verdict |
|---|---|
| Lucis 2.0 | **chosen**. Single-module NeoForge 1.21.1 project; already carries the strongest engine design (region-owned images, certified extraction/seeding, adoption init, exact incremental repair) *and* the two assets that make any further porting safe: a differential test suite with a vanilla-semantics reference engine, and an in-server benchmark harness whose mode strings already include a ScalableLux A/B branch |
| ScalableLux (patched tree) | not chosen as the base. It is a matured global-engine replacement (SWMR nibble storage, heightmap/column sky, parallel light tasks, client support, save/load hooks), but its value here is its *ideas*, and its build chain is an unusual nested-backport stack (see the workspace's BUILD-NOTES) |
| Lucis 1.x | superseded by 2.0 |

The engine designs are **not additive**: both replace the same propagation work and the same vanilla classes
(`ThreadedLevelLightEngine`, `ChunkMap`, `ChunkAccess`, `SerializableChunkData`, `LevelChunkSection`), so the
merge is a port of ideas into one engine, never two engines side by side. That is also why the mod metadata
declares Starlight/ScalableLux/Lucis incompatible.

## 2. Advantage inventory (source -> destination)

| # | Advantage | Origin | Where it lands |
|---|---|---|---|
| 1 | Owned region light image + coalescing + worker jobs + dirty-section publication | Lucis | already in the base |
| 2 | Homogeneity certificates for bulk material extraction | Lucis 2.0 | already in the base |
| 3 | Uniform-source sky-section seed skipping | Lucis 2.0 | already in the base |
| 4 | Adoption-backed region init (no bootstrap compute) | Lucis 2.0 | already in the base |
| 5 | Exact two-queue incremental skylight repair | Lucis 2.0 | already in the base |
| 6 | Change-class routing (dense batches stay incremental) | Lucis 2.0 | already in the base |
| 7 | Differential suite + benchmark harness | Lucis 2.0 | already in the base |
| 8 | Byte-budgeted accounting of cached light data | ScalableLux (idea) | **M0, done** |
| 9 | Leak- and lifecycle-hardening of engine state | ScalableLux (idea) | **M0, done** |
| 10 | Heightmap/column-driven skylight propagation | Starlight/ScalableLux | **M1** |
| 11 | Intra-job (task-level) parallelism for large relights | ScalableLux/FlowSched | M3 |
| 12 | Save/load light handling and worldgen-mod interop | ScalableLux | M2 |
| 13 | Client-side lighting | ScalableLux | M3 (optional, new feature rather than an optimisation) |

## 3. Milestones and acceptance criteria

"Better than any single parent" is the bar; the criteria are falsifiable, and every one is measured with the
suite/harness that ships in this repo.

### M0 — hardened baseline (this milestone)
* `./gradlew test` green (differential + engine tests).
* Boots on NeoForge 21.1.235: fresh world generation, a bulk block workload, graceful stop, reload of the
  saved light — no `ERROR`/`Exception`.
* Region cache is bounded by bytes: with a deliberately contradictory configuration
  (`maxCachedRegions=4096` + `maxCachedRegionMegabytes=64` + large regions) the telemetry must show the byte
  budget binding, not the entry cap.
* The full-relight queue stays inside `maxPendingRecords`.
* A leaked bulk-write scope is reaped and logged after the idle timeout.
* Benchmark: the six-workload set within noise of the Lucis 2.0 baseline, **and** the halo cost measured, not
  guessed (see §5).

### M1 - heightmap/column sky: CANCELLED after review (the lever was not where it looked)
The plan was to keep per-column direct-sky heights and use them to cut the incremental sky walk. Reading the
implementation shows the walk is already bounded: `recomputeChangedColumns` starts at the topmost *changed* cell
of the column (`columns.maxY[column]`) and breaks as soon as the column goes dark, and the initial `compute`
already bulk-fills fully transparent columns with `Arrays.fill`. The remaining `sky_hole` parity is therefore
not walk overhead - it is the propagation volume (shaft plus lateral cone), which is identical for vanilla and
for this engine, exactly as the profiling document concluded. A heightmap would shave a scan that is not the
bottleneck, at the cost of a second piece of state to keep exact.

**The real remaining worldgen lever (M1', recommended next):** the worldgen/relight path hardcodes a 3x3 halo
image per generated chunk (`relightChunk(..., 1, 1)`) and extracts plus computes it from scratch
(`extractChunkData` + `relightPreparedChunk`), while the runtime path keeps exactly such images in
`OwnedRegionCache`. Sharing the cached image between the two paths - refreshing instead of re-extracting -
would remove most of the worldgen light cost per chunk and is the largest remaining win. It is a medium-risk
change (two paths sharing state, ownership rules), so it needs its own milestone with the differential suite
plus a worldgen benchmark before/after.

**M1b - read-only halo: NOT pursued.** The halo's materials *and* its light are both needed: materials for the
propagation, light as the repair baseline and as what gets published for the neighbouring chunk. Dropping
either makes border light wrong (too dark) or makes halo publication publish wrong values, so the measured init
cost (`adopt` 7.5 -> 47.7 ms, `extract` 0.85 -> 6.7 ms per region at halo=1) is the price of the cross-region
correctness that M2 added. What remains worth doing there is memory, and that is already bounded by
`maxCachedRegionMegabytes`.


### M2 — correctness and interop
**Cross-region light: done (runtime path).** The design that shipped instead of the failed delta prototype:

1. Runtime jobs compute on a halo image (`runtimeHaloChunks=1`, exactly the 15-block light travel distance).
2. Jobs publish the halo chunks' dirty sections too (`haloPublish`), so the neighbouring chunk receives the
   light immediately instead of waiting for its own region job.
3. Every other region whose image overlaps a published chunk is marked, and re-reads exactly those sections
   from the engine before its next job uses the image as a baseline. This is what keeps the single source of
   truth - the vanilla engine - authoritative, which is why it cannot oscillate the way the delta prototype did.
4. A job that notices its baseline moved while it computed re-queues instead of publishing stale values.

Verified on a live server with border-aligned edits: `halo sections published 6468`,
`external sections marked 1783 / refreshed 616`, `baseline re-runs 40`, zero errors, and the unit test
`HaloPublicationTest` pins the light gradient across the border plus the dirty-section selection.

Also fixed on the way (inherited bug): adoption-backed region init discarded the batch's own changes, so a
light source placed in a region with no prior runtime job never lit up (`adopted-batch changes 93` once fixed).

**Save-side light safety: done (M2b).** `ChunkSerializerMixin` marks a chunk as light-not-correct when it is
written while its region still has queued or in-flight work, so the game relights it on load; nothing relights
a chunk that loads as light-correct, so otherwise a torch placed just before quitting would come back with its
light permanently missing. Verified on a live server with a same-tick `fill` + `save-all flush`:
`save forced light-incorrect: pending 4 global 0`. `forceLightIncorrectOnSave` (default off) forces it for every
chunk, which is what ScalableLux ships and additionally covers a neighbour generated after the chunk was saved,
at the cost of relighting every chunk on load.

Still open in M2:
* **Worldgen ordering.** The worldgen path publishes only the chunk it relit; a chunk generated at the edge of
  loaded terrain can leave an already-light-correct neighbour stale until its own relit runs. The precise fix
  needs a border-diff check at generate time; until then `forceLightIncorrectOnSave=true` is the conservative
  setting that covers it.
* **Interop:** run the worldgen-stress scenario with C2ME / Generator Accelerator installed and require no
  divergence.

### M3 — optional reach
* Intra-job parallelism for very large relights (region jobs are currently the only parallelism level).
* Client-side lighting (Starlight-class feature; a feature gap rather than a speed gap).

## 4. Inherited known limitations (from Lucis 2.0, still true here)

1. **Cross-region continuation (runtime path): fixed.** `runtimeHaloChunks=1` plus halo publication and the
   per-region refresh make runtime light correct across chunk borders. `runtimeHaloChunks=0` is still available
   as the fast truncated mode and logs a warning. The `BorderDeltaSupport` delta prototype stays off and is no
   longer needed; it is kept only for reference.
2. **`sky_hole` parity.** Cutting a small opening re-lights a volume both engines pay identically; M1 is the
   plan for that.
3. **Worldgen border ordering.** A newly generated chunk at the edge of loaded terrain publishes only itself; a
   neighbouring already-light-correct chunk can stay stale until its own relit runs (M2b).
4. **Adoption trusts engine storage.** If a third-party mod writes wrong light into engine storage it is
   adopted (vanilla would also read it).
5. **Material model** is still opacity/emission per cell with vanilla-cached per-state values; there is no
   directional shape model beyond what vanilla calls provide.

## 5. M0 measurement results

Measured on this machine (NeoForge 21.1.235 server, mod as a production jar, the mod's own harness via
`-Dlucistarlink.benchmark=true -Dlucistarlink.debug=true`, 4 measured passes + 2 warmup, fresh world per run):

**End-to-end (`nsPerChange`-style wall time) is not usable at this sample size.** `dense_chunk_patch` wall time
varied 47-62 ms (halo=1) vs 49-60 ms (halo=0); `structure_cube` 9.6-53.5 ms (halo=1) vs 22.6-46.6 ms (halo=0)
across repeats - the idle-server tick quantization the profiling document warns about dominates. More reps and
the `runBenchmarkServer` Gradle path (fresh JVM per cell) are needed before quoting end-to-end numbers.

**Stage metrics do give a clean answer**, and they are what matters for the default decision: the halo cost
lands almost entirely on *region initialization*, not on the steady-state incremental path.

| stage | halo=0 | halo=1 | why |
|---|---:|---:|---|
| `stage.runtime.init.adopt` | 7.5 ms/call | 47.7 ms/call | adoption bulk-unpacks engine light over the whole region image, and the image is (1+2*halo)^2 = 9x larger |
| `stage.runtime.init.extract` | 0.85 ms/call | 6.7 ms/call | extraction is certificate-proportional, but the halo is still 9x the core volume (counters confirm: thousands of air sections bulk-filled, e.g. 2372/3993 air vs 38 opaque) |
| `stage.runtime.incremental.*` | 0.4-3.8 ms/job | 1.4-5.0 ms/job | unchanged in nature: incremental repair is proportional to the *changed columns*, not to the region volume |
| published sections | dirty only | dirty only | halo is never published, only used as boundary conditions |

Cost is per *region touched*, so it scales with how much new terrain a workload touches, not with how many
changes are applied. Conclusion for M0: `runtimeHaloChunks=1` stays the default - it is the configuration with
vanilla-equivalent chunk borders, the incremental path it protects is unaffected, and the added cost is a
per-region init constant that the region cache (byte-budgeted, ~75 regions at 3.4 MiB in the observed run)
keeps bounded.

This also produced the next performance item, ahead of heightmap sky work for roaming-player servers:
**the halo should be read-only.** `haloChunks` is documented as a read-only halo, yet adoption/extraction
treats the whole 3x3 image as owned data. Reading neighbour light lazily as boundary conditions (or sharing
neighbouring core images) would remove ~8/9 of the init cost that the safe default currently pays.

## 6. Non-goals


* Two jars side by side (impossible: both own the light engine).
* Summed speedups (A+B). Speed is a ratio against vanilla and both engines compress the same critical path, so
  the realistic expectation is `max(parents) × (1 + margin)`, with M1 being the one place where a large
  additional gain is plausible.
* Rewriting Minecraft's light *storage* (a global SWMR section store à la Starlight). It would make boundary
  semantics trivial, but it is a rewrite of the engine's core assumption and stays out of scope until the
  measurement in §5 says the halo approach cannot be kept cheap.

## 7. Decisive measurement: where our performance gap comes from (2026-09-18)

Same rig, same harness, `dense_chunk_patch` (8192 changes), production jars:

| engine / configuration | wall_ms |
|---|---|
| vanilla | 64.9 / 67.2 (2 reps) |
| ScalableLux 0.3.0-alpha.0.8 (1.21.1 NeoForge) | **12.4 / 15.3** |
| LuciStarlink, `runtimeHaloChunks=0` + `haloPublish=false` (inherited behaviour) | **10.97** |
| LuciStarlink, default (`runtimeHaloChunks=1` + `haloPublish=true`) | 62.10 |
| Lucis 2.0 | did not load in the comparison rig (needs diagnosis) |

**Reading:** the inherited engine is already at least as fast as ScalableLux on this workload (10.97 vs 12.4/15.3);
the entire 4-6x regression is the price of the cross-region correctness mechanisms, dominated by the halo making
each job's working image 9x larger (measured earlier: `init.adopt` 7.5 -> 47.7 ms, `init.extract` 0.85 -> 6.7 ms
per region). So "beat both parents" is an engineering problem with a bounded scope: make correctness cheap
instead of removing it. Note the harness's drain barrier can only observe LuciStarlink's own pending work, so
the ScalableLux numbers may exclude work that spilled past the measurement window - the bias favours them.

### Priority list to close the gap (each with before/after on the same harness)
1. **Lazy, per-section materialisation of halo light.** Keep halo *materials* fully extracted (certificate
   bulk fill, ~6.7 ms, and required for propagation). Materialise halo *light* only for the section set a
   job can physically touch: changed cells +/- 15 blocks (the light travel distance) computed at section
   granularity - typically ~50 of 432 sections. Sections never materialised never become dirty, so they are
   never published (self-consistent), and a dedicated test must pin "every section inside the propagation
   radius is materialised".
2. **Publish only halo sections whose values differ** from the engine's current data, skipping no-op
   publishes and the neighbour re-reads they would cause.
3. **Inline tiny jobs** on the tick thread instead of handing them to workers, to remove the fixed
   submission/drain floor the profiling document identified for small-edit workloads.

Acceptance: default configuration back to <= 13 ms on `dense_chunk_patch` while halo publication evidence
(`halo sections published` > 0, `external sections refreshed` > 0), the differential suite and the border
scenario all stay green.

## 8. Per-stage bisection of the halo cost (2026-09-18, second pass)

Per-pass stage data (same run, trustworthy; cross-session wall times are not - the same halo=0 configuration
measured 11 ms in one session and 50.6 ms in another, so only within-run stage numbers and repeated wall runs
count):

| stage | halo=0 | halo=1 before | halo=1 after `lazyHaloLight` |
|---|---:|---:|---:|
| `init.adopt` (read engine light) | 61.9 ms | 435.3 ms | **0.87 ms** |
| `init.extract` (read materials) | 3.8 ms | 37.4 ms | 29.6 ms |
| `job.runtime` (per-job total) | 142.8 ms | 493.6 ms | 330.9 ms |

So the lazy halo light materialisation (now the default) removed the entire adoption blow-up: 435 -> 0.9 ms.
What remains, in order of size:

1. **Per-job work is still ~5x with halo=1** (`job.runtime` 330.9 vs 64.0 ms). Not measured yet in fine grain:
   split the job into `RegionBounds` construction, `captureExpectedChunks` (9 chunk lookups per job),
   `OwnedRegionCache.getOrCreate` + LRU trim, the repair BFS itself, and `clearDirty`, then attack whichever
   dominates (candidates: reuse region objects and bounds, capture only core chunks, bound the BFS scan to the
   change radius instead of the image).
2. **`init.extract` 29.6 ms** - materials are still extracted for the whole 3x3 image. Lazy per-section
   material extraction, using the same reach helper, is the analogue of the light fix and should land like it
   did for adoption.

Acceptance unchanged: halo=1 job and init stages back to halo=0 levels, wall <= 13 ms on `dense_chunk_patch`
with repeated runs, while halo publication, external refresh and save safety all stay intact.
