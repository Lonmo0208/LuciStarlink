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

### Known limitations (documented in docs/roadmap-and-provenance.md)
* Worldgen border ordering: a chunk generated at the edge of loaded terrain publishes only itself; the
  conservative `forceLightIncorrectOnSave=true` covers the residual case.
* `sky_hole`-class workloads are at parity with vanilla (the repair volume is physically identical) and
  heightmap-driven sky was evaluated and rejected as a non-lever.
* Client-side lighting is not taken over (the mod is server-side; the integrated server runs the engine).
