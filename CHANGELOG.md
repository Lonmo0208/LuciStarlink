# Changelog

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
