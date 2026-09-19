# Changelog

## 1.1.0 — the per-change guard stops paying for the benchmark, and the publish source stops being global

### Performance: the block-change path (which is where `dense_chunk_patch` was losing)

Applying the same 2048 block changes through the same harness cost us 1440 us against ScalableLux's 888 us, while the
time we spent *waiting* for the engine was shorter than theirs (593 vs 620 us) - the dense deficit was never light
computation, it was our own interception. Every block change evaluates `shouldHandleBlockChange` (config, worldgen
suppression, backpressure, queue capacity, Sable), two benchmark-only injections evaluated it a second and a third
time, and the guard itself read the clock and a ThreadLocal. Now:

* one `checkBlock` injection instead of three, so the guard is answered once per change;
* `runtimeBackpressureActive` reads the clock only while backpressure is actually on (`tickRuntime` clears an expired
  deadline) - which also removes a latent case where a zero deadline counted as "backpressure active" on a platform
  whose `nanoTime()` is negative;
* the worldgen write scope moved into `WorldgenWriteScope`: same per-thread semantics, but a scope counter answers the
  common case without touching the ThreadLocal, and an unbalanced exit clamps at zero so a later scope cannot end up
  unsuppressed while it is still open;
* `LuxCompat.isSablePlotChunk` checks whether Sable is installed before resolving the level.

Measured in one interleaved group of 5 (per-pass minimum, median, `ab-compare.ps1`): the dense deficit went from
1.83x (an interleaved run earlier the same day: 2.500 vs 1.368 ms) to **1.25x** (2.595 vs 2.069 ms). The same group
reads `block_toggle_border` 1.308 vs 2.323 ms - **1.78x ahead**, p=0.008; `structure_cube` 2.753 vs 3.749 ms -
1.36x ahead, p=0.69 because two of our five runs in that block were environment-stalled (9.9 / 7.3 ms against
2.2-2.8 ms); `sky_hole` 0.912 vs 0.576 ms - **1.58x behind**, p=0.008. Two workloads ahead, two behind: that is the
honest summary, not "three of four".

### Correctness: the publish engine's light source is per-thread

`LuxPublishEngine`'s "did this section actually change?" source was a single volatile field. Publishing happens on the
job's own thread, but several jobs run at once and on a multi-dimension server they belong to different levels: a
second thread could read the source another thread - or another dimension - had just installed, so the identity check
compared bytes from the wrong world, and an accidental match **skipped a publication that was required, losing
light**. It is now per-thread and cleared after the collect, so a worker thread also no longer pins the light engine
of an unloaded dimension through a lambda.

### Tests

`WorldgenWriteScopeTest` (5 cases): suppression inside the scope, nesting, an unbalanced exit, another thread's scope
not suppressing this thread, and one thread leaving not ending another thread's scope - the last two are exactly what
a naive `volatile boolean` fast path would get wrong. 29 tests pass, 0 failures.

## 1.0.3 — authorship and thanks list

The artifact credited nobody: `mod_authors` was a sentence about the lineage (and described Lucis's author wrongly),
and the mods-UI `credits` field was empty. Changes, all metadata and no code:

* **LuciStarlink is credited to Lonmo** — Copyright (c) 2026 Lonmo — in the jar metadata, the mods-UI credits line,
  NOTICE and the README.
* The upstream works are named with the repositories they actually come from:
  **Lucis 2.0 by [Team Argentum](https://github.com/Team-Argentum/Lucis)** (branch `dev/2.0/1.21.1`), the line this
  fork is derived from, and the original **Lucis 1.x by
  [DenisMasterHerobrine](https://github.com/DenisMasterHerobrine/Lucis)**;
  **Starlight / ScalableLux by [Spottedleaf, ishland and RelativityMC](https://github.com/RelativityMC/ScalableLux)**,
  including the NeoForge 1.21.1 backport on branch `backports/neoforge/1.21.1` that this work is measured against,
  and [Starlight](https://github.com/PaperMC/Starlight). NOTICE now opens with a thanks list.

## 1.0.2 — the light-dump commands no longer return a plausible-looking empty reading

A dev-client session ran `/lucistarlink dumplight` twice with the arguments shifted by one (the label was a
coordinate, the box was empty). An empty box still produced a well-formed fingerprint - both hashes read the FNV
offset basis, `cbf29ce484222325` - which is indistinguishable from a real measurement unless you know that
constant, so two runs were lost and a reader could have taken it for data.

`dumplight` now logs a warning when its box sampled no cells, printing the syntax, the box it actually used and the
world's build-height range. No behaviour change for a correct call.

## 1.0.1 — configuration regression fix

Found by running the dev client against an existing installation (the configuration file it had written earlier):
NeoForge reported `Configuration file … is not correct. Correcting`, and the corrected file carried
**`haloChunks = 0`**.

1.0.0 made `haloChunks` live (before that the key did nothing and the world-generation path hardcoded 1), so every
configuration written by an older build — which stores the old default 0 — would have read it literally and switched
the world-generation halo **off**: a chunk generated beside an already-loaded neighbour would stop propagating at the
chunk edge and leave that neighbour's border light stale, i.e. the exact seam the halo exists to prevent.

The world-generation path now treats 0 as 1 (the key's useful range there is 1–2), and the config comment, the
README row and this note say why. No other key changed meaning: `regionChunks` defaulted to 1 before and after.
Verified: clean build, 21 tests with 1 inherited skip, metadata declares `starlight`/`scalablelux`/`lucis`
incompatible, and a dev-client launch loads 1.0.1 with the boot log and flags line as expected.

## 1.0.0 — first release

The engine described under 0.1.0 below, plus what a full source review and a long measurement campaign changed.
Nothing about the default behaviour is new here: every switch added or re-tested along the way defaults to the
behaviour 0.1.0 shipped.

### Correctness fixes (found by review, not by a failing test)
* **World-generation halo publication could be skipped.** `haloTouchedScratch` is a thread local written by the
  runtime path and read by the world-generation path to decide whether to publish the halo and mark neighbours
  stale. A world-generation relight that ran on the server thread (the worker pool runs past its cap there)
  inherited the last runtime job's answer, so a neighbouring chunk could keep stale border light — the permanent
  seam those two mechanisms exist to prevent. The world-generation path now sets that state itself.
* **`RuntimeUpdateQueue` could lose queued changes.** Draining removed a region's entry and then drained the
  object it had resolved; an `enqueue` that had already resolved that object *before* the removal added its
  records to an entry that was no longer in the map, so nothing ever drained them (and the reservation count
  stayed high). Drain now uses `compute`, which takes the same lock as `computeIfAbsent`, so an enqueue either
  lands before the drain or creates a fresh entry.
* **Full relights never published their halo.** A full relight carries no change records, and
  `changesNearBorder` on an empty list is always false, so both the halo publication and the neighbour marking
  were skipped for every bulk-write-triggered relight. The full-relight path now states that its halo is touched.
* **A throwing runtime job lost its batch.** The failure path released the region without requeueing, so that
  region kept the light it had until something else touched it. It now requeues (a retry, not duplicate light).
* `publishedThisTick` is volatile (written by a worker, read by the tick thread) and the publish batch's
  "first queued" timestamp is reset per batch so stale timestamps cannot pollute the queue-latency metric.

### Configuration
* `regionChunks` and `haloChunks` were documented knobs that **nothing read** (the live paths hardcoded 1, or
  read a hidden system property). Both are now wired: `regionChunks` sizes the runtime regions and the
  world-generation image, `haloChunks` the world-generation image's halo (default 1, matching the value the
  code previously hardcoded, so behaviour is unchanged for anyone who never touched them).

### Removed
* Dead members verified unreferenced across the whole source tree (`LuxChunkSnapshot`, the superseded
  `flushMarker`/`markDrainEnd` helpers, a duplicate import, an unused `fullDepthRepair` field, a missing
  `queuedCurrent` assignment in the sky seeding loop). The rejected boundary-delta prototype
  (`experimentalBoundaryDeltas` + `BorderDeltaSupport`) stays, default off, and is listed for removal in 1.1 —
  it is inert but it is the one remaining piece of code no configuration enables.

### Measurements (see docs/TASK-PERF-SKY.md for the full record)
* Against **vanilla** and **ScalableLux**, settled-world protocol, same-session interleaved runs, ≥5 reps,
  exact two-sided Mann-Whitney p, median of per-pass minima. **Two independent interleaved sessions are shown as
  ranges**: every direction was replicated with perfect separation, magnitudes vary up to ~40% between sessions:

  | workload | vanilla | LuciStarlink | ScalableLux | vs vanilla | vs ScalableLux |
  |---|---|---|---|---|---|
  | `block_toggle_border` | 3.44 | **0.70–0.81** | 1.64–1.75 | **4.3–4.9× faster** | **2.05–2.49× faster** (p=0.008) |
  | `structure_cube` | 3.69–4.33 | **1.74–2.43** | 3.04–3.19 | **1.8–2.1× faster** | **1.31–1.77× faster** (p=0.008) |
  | `dense_chunk_patch` | 3.02–3.16 | **1.80–2.45** | 1.42–1.49 | **1.23–1.76× faster** | 1.21–1.73× slower (p≤0.095) |
  | `sky_hole` | 0.76–0.82 | 0.71–0.82 | **0.36–0.44** | parity (p=0.69) | 1.87–2.0× slower (p≤0.016) |

  The 1.0.0 correctness fixes were verified performance-neutral in a same-session interleaved A/B of the two jars
  (`structure_cube` 2.315 vs 2.384 ms, p=1.00; `sky_hole` p=0.70), so the ranges above are session drift, not a
  cost of the fixes.

* What the `sky_hole` gap is: fourteen candidate levers were tested under a pass metric that measures when our
  light actually reaches the engine, and every one was null or worse (coalescing window, notification fan-out,
  publish lane, inline threshold, direct section install at n=25, piggyback publish, prompt dispatch, halo and
  compute size, warm worlds, a synchronous-path combination). The gap is the asynchronous delivery chain itself
  (~0.4 ms) against ScalableLux's synchronous completion, not a missing optimisation. Our computed volume is not
  on that path at all: turning the halo off cuts runtime compute by 2.5–4.5× and changes the measured pass by
  nothing.

### Verification
* 21 differential tests (1 inherited skip), all green.
* Adjacent-pair light probes: on the same world snapshot and the same runtime border edit, our fingerprints are
  bit-identical to vanilla's over the fluid-free air band (`edit`, `edit_border`). The full-column block
  fingerprints differ in both directions and are not an oracle — vanilla differs from itself between sessions.
* Save/reload, client sync (light placed on a chunk border reaches a connected client within 2 s on both sides,
  no reconnect), and the memory budget were verified as recorded in docs/HANDOVER.md.

### Known limitations
* `sky_hole` is ~2× behind ScalableLux for the structural reason above; closing it needs the storage to be ours
  (the design in docs/ARCH-V2-GLOBAL-STORAGE.md), which is not in 1.0.0.
* Client-side lighting is not taken over, intra-job parallelism is not implemented, and interoperability with
  concurrent world-generation mods (C2ME, Generator Accelerator) has not been measured.
* World-generation border ordering still relies on the conservative `forceLightIncorrectOnSave=true` for the case
  of a neighbour that was not loaded when a chunk was generated.

## 0.1.0 — three-in-one baseline (M0 → M2b)

LuciStarlink starts as a hardened fork of Lucis 2.0 whose cross-region light is correct, whose memory is
bounded, and whose saves are safe. Every entry below is verified by the inherited differential suite plus the
in-repo benchmark harness; the numbers quoted are from this repository's harness on a live NeoForge 21.1.235
server.

### Cross-region light (the headline fix)
* Halo chunks' dirty sections are published too (`haloPublish`), so light computed across a region border
  reaches the neighbouring chunk immediately instead of leaving a dark seam until that region is touched.
  Measured: `halo sections published 6468` for a border-edit scenario.
* Every other region whose image overlaps a published chunk re-reads exactly those sections before its next
  job (`external sections marked 1783 / refreshed 616`), keeping the engine the single source of truth.
* A job whose baseline moved while it computed re-queues instead of publishing stale light
  (`baseline re-runs 40`).
* Fixed an inherited bug: adoption-backed region init discarded the batch's own changes, so a light source
  placed in a region with no prior runtime job never lit up (`adopted-batch changes 93`).
* `runtimeHaloChunks` (default 1) gives runtime jobs a 15-block halo, i.e. vanilla-equivalent borders;
  `0` remains the fast truncated mode and logs a warning.

### Save-side light safety (M2b)
* `ChunkSerializerMixin` writes chunks as light-not-correct when their region still has queued or in-flight
  work, so the game relights them on load instead of trusting incomplete light (verified with a same-tick
  `fill` + `save-all flush`: `save forced light-incorrect: pending 4 global 0`).
* `forceLightIncorrectOnSave` (default off) forces it for every chunk, covering chunks whose neighbour was
  generated after they were saved, at the cost of relighting every chunk on load.

### Resource safety (M0)
* The region cache is bounded by **bytes** (`maxCachedRegionMegabytes`, default 256 MiB), not just by entry
  count — a single region is megabytes, so an entry cap never bounded the heap. Verified by driving a
  contradictory configuration: `region cache 42/4096 regions (63/64 MiB)`.
* Full-relight requests respect the queue's record budget; leaked bulk-write scopes are reaped after 30 s idle
  instead of silently disabling runtime lighting on that thread; shutdown is guarded (`closed` flag, idempotent,
  thread locals cleared); a shut-down controller is no longer left installed in its static holder.

### Observability
* `/lucistarlink status` and `/lucistarlink flags` report engine state in game.
* `verboseLogging` prints the same status line every 30 s; the boot log reports the cache budget and effective
  flags.

### Performance (lazy materialisation, and what was rejected)
* Halo light and halo materials are now materialised per section on demand: region init 435.3 → 0.87 ms,
  material extraction 56.8 → 5.6–13.9 ms.
* Measured against **vanilla** and **ScalableLux** in same-session **interleaved** runs (the three engines
  alternate round by round, ≥5 reps each, median of per-pass minima, exact two-sided Mann-Whitney p), all under
  the settled-world protocol `prepareRing=3` + `quiesceSettleMs=1000`:

  | workload | vanilla | LuciStarlink | ScalableLux | vs vanilla | vs ScalableLux |
  |---|---|---|---|---|---|
  | `block_toggle_border` | 3.440 | **0.807** | 1.642 | **4.3× faster** (p=0.008) | **2.05× faster** (p=0.008) |
  | `structure_cube` | 3.689 | **1.737** | 3.039 | **2.1× faster** (p=0.008) | **1.77× faster** (p=0.008) |
  | `dense_chunk_patch` | 3.157 | **1.797** | 1.490 | **1.76× faster** (p=0.008) | 1.21× slower (p=0.095) |
  | `sky_hole` | 0.757 | 0.718 | **0.355** | parity (p=0.31) | 2.0× slower (p<0.0001) |

  Wall time agrees: 3.7× / 2.4× / 1.5× faster than vanilla; 1.9× / 1.85× faster than ScalableLux; 16% behind
  on `dense_chunk_patch`, 2.2× behind on `sky_hole` (where we sit at vanilla level).

  **Two earlier claims had to be corrected when the protocol defect was found** (the measured window was
  letting world-generation light work in: generating a chunk that borders a measured chunk queues light work
  *for that measured chunk*, which inflated both engines but ScalableLux more): the "ahead by 10%" on
  `dense_chunk_patch` is gone — under the clean protocol we are 16–21% behind there — and the old
  "25–35% behind on `sky_hole`" understated the gap, which is ~2.0×. The rare ~30 ms pass that made
  `dense_chunk_patch`'s wall 2.5–3.1× worse also turned out to be the same protocol artifact; it is 16% now.
  Our cost structure on `sky_hole`: apply ~0.12 ms + publish coalescing window ~0.25 ms + drain ~0.4 ms
  (publish, engine re-absorption, client notifications), against ScalableLux's ~0.3 ms synchronous update with
  no queue and no hand-over. `directSectionInstall` (skips the re-absorption) measures −37% at n=6, p=0.18 —
  promising but not established. See docs/TASK-PERF-SKY.md §7.8–7.9.
* Four reschedulings were measured and **rejected** (`promptRuntimePublish` +44%, 2 ms coalescing window,
  `syncRuntimeDrain` neutral, worldgen core-only publish neutral), and `directSectionInstall`'s sequential
  −31% did not reproduce interleaved (no workload significant, p = 0.22–0.84), so no publish-path switch is
  enabled.

### Verification
* Client-sync case: a light source placed on a chunk border reaches a connected client within 2 s, on both
  sides of the border, with no reconnect or chunk reload — verified on full-resolution in-game screenshots
  (dark/lit/dark + difference image), evidence in `mc-smoketest/clientcase-evidence/`.
* Measurement methodology fixed for good: absolute numbers drift up to ~40% between groups of one session, so
  only same-run interleaved comparisons count, every benchmark row carries its label and switches in
  `lucistarlink-light-benchmark.jsonl`, and no table is quoted without U/p.
* Two earlier claims were withdrawn: the "7/6 divergent" block-light tally and "in-place overwrite fixed the
  divergence". The divergence investigation closed clean (7 controlled runs, 0 divergence, cell-identical
  plane diff); in-place overwrite is a hard requirement from the thread model, not a proven fix.

### Known limitations (documented in docs/roadmap-and-provenance.md)
* Worldgen border ordering: a chunk generated at the edge of loaded terrain publishes only itself; the
  conservative `forceLightIncorrectOnSave=true` covers the residual case.
* `sky_hole`-class workloads lag ScalableLux by 25–35% for the structural reason above, and `dense_chunk_patch`
  carries a rare ~30 ms pass that makes its wall time 2.5–3.1× slower; heightmap-driven sky was evaluated and
  rejected as a non-lever.
* Client-side lighting is not taken over (the mod is server-side; the integrated server runs the engine).
* Interop with concurrent worldgen optimizers (C2ME, Generator Accelerator) has not been measured.
* `experimentalBoundaryDeltas` / `BorderDeltaSupport` are dead code awaiting deletion.
