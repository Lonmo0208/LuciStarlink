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
* Measured against ScalableLux in same-session **interleaved** runs (round-by-round alternation, ≥5 reps each,
  median of per-pass minima; `sky_hole` and `dense_chunk_patch` were then replicated in a second independent
  interleaved group):

  | workload | LuciStarlink | ScalableLux | verdict |
  |---|---|---|---|
  | `block_toggle_border` | **0.636** | 2.285 | win, p = 0.008 (all 5 runs faster) |
  | `structure_cube` | **1.757** | 3.540 | win, p = 0.008 (all 5 runs faster) |
  | `dense_chunk_patch` | 1.990 / 2.342 | 2.210 / 2.217 | **no difference**: the two groups disagree in direction, p = 0.095 and 0.841 |
  | `sky_hole` | 0.767 / 0.666 | 0.572 / 0.535 | behind 25–35%, replicated (p = 0.056 each, Fisher ≈ 0.02) |

  So two workloads win decisively, one is a tie, and `sky_hole` lags for a structural reason: ScalableLux does
  not hand sections to the light engine at all, which is the V2 storage mode's territory, not a tuning knob.
  `dense_chunk_patch`'s **wall** time is 2.5–3.1× worse in both groups (a rare ~30 ms pass) — recorded as the
  open performance item.
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
