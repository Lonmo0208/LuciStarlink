# Changelog

LuciStarlink 2.0 is a **new line**, not a continuation of 1.x: it is built on the ScalableLux light engine and
replaces the update path, where the 1.x line was built on Lucis and owned a region image. For the 1.x history see
the 1.x branch's own changelog; for what belongs to whom see [NOTICE](NOTICE) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Numbering: `2.0.1` was the first release of the 2.0 line. The development builds were numbered `2.0.0-alpha.N` and
none of them was released; the measurement records in `docs/` refer to those jar names, so the version strings in
them are left as they were measured rather than rewritten.

## 2.0.2 — 2026-09-23

**Fixes the first defect reported from play: a placed light source did not light up, and stayed dark across a
save/reload** — reported as 「光源方块放置后 退出存档 重新进 会不发光了」. Two independent causes, both required for
the symptom; found with a reproduction that runs entirely on a dedicated server, on the trace in
`docs/BUG-EMITTER-BLOCK-LIGHT.md`.

- **An opaque light source was skipped as "a no-op for block light".** The R5 bulk-fill optimisation skipped a
  changed cell when its block light was 0 and its new state was fully opaque (opacity 15) — but glowstone, sea
  lanterns, redstone lamps and frog lights are opacity 15 **and** emit light, so "already dark and now opaque"
  described them exactly and their own cell's light was never computed. The skip now also requires the state to emit
  nothing, which is what the rule was always about (a stone fill); torches were never affected because their opacity
  is 0.
- **A buffered edit had no guaranteed server-thread settle point.** The inline lane buffers an edit instead of
  queueing it and applies it on the server thread; its only settle point was `hasUpdates()`, which the light
  engine's own worker thread also calls (correctly refused by the thread guard) while vanilla only asks from the
  server thread when the engine's *queue* has work — and the lane deliberately creates none. Measured on an idle
  dedicated server: an edit sat buffered for **5 seconds**. A player who placed a light and quit inside that window
  saved the chunk's light exactly as it had been before the edit, i.e. dark. `ServerLevel.tick` now settles the
  buffers at the head of every tick, so an edit is applied at most one tick (50 ms) later and always before a save.

Verified end to end on the reported scenario (place → save → stop → restart → read): the cell reads `block=15` after
the reload. Regression on the four acceptance workloads afterwards, defaults only, CPU 25.8%:
`block_toggle_border` 0.424 ms, `structure_cube` 2.566 ms, `dense_chunk_patch` 1.305 ms, `sky_hole` 0.517 ms, player
axis 48.7–52.5 ms per pass, and the structure fingerprint is still `sky=905931078dfc5ace` (bit-identical to
vanilla/ScalableLux).

**The acceptance gate was widened because of this**, since it could not have caught it: the correctness fingerprint
had only ever been run on `structure_cube`, whose changes are all non-emitters (273 block-light cells out of
599,040), so no gated workload contained a light source. At least one gated workload must now contain emitters and
its **block-light** fingerprint must be compared, plus the place → save → reload check above
(`docs/ARCHITECTURE.md` §4).

## 2.0.1 — 2026-09-23

First release of the 2.0 line, tagged `v2.0.1`. The jar is built locally (`./gradlew build`); release artifacts stay
local for this project by policy, and the GitHub workflow compiles the tree without uploading anything.

### The engine: an update path of our own, on ScalableLux's engine

Measured against ScalableLux and the 1.x line in the same session, interleaved, four workloads
(`docs/NEW-ENGINE-TEARDOWN.md` §32):

| workload | 2.0 | ScalableLux | 1.x |
|---|---|---|---|
| `block_toggle_border` | **0.32 ms** | 4.01 ms | 0.84 ms |
| `structure_cube` | **2.51 ms** | 4.97 ms | 2.69 ms |
| `dense_chunk_patch` | **1.07 ms** | 3.65 ms | 1.96 ms |
| `sky_hole` | **0.69 ms** | 0.88 ms | 0.73 ms |

Player-facing wall time: 49–50 ms per pass on all four (ScalableLux 49–50, 1.x 101/68/73/49). The light stays
bit-identical to vanilla and ScalableLux on the verification box (`sky=905931078dfc5ace`), which the 1.x line's
light is not.

Five mechanisms, **on by default**:

- **Inline edit lane** (`-Dscalablelux.ownEdit`) — propagate and install inside the `setBlockState` call that made
  the change; the engine queue is never touched, so the ~4.3 ms completion-path turnaround is not paid.
- **Per-chunk edit buffering** — one engine call per burst instead of one per block.
- **Consolidated decrease drain** (`-Dscalablelux.batchDecrease`) — seed per chunk, drain the global decrease
  queue once per burst instead of once per touched chunk (220–430 µs per call).
- **Windowed sky settle** (`-Dscalablelux.recomputeSky`) — recompute only the y window an edit can reach (±16;
  exact, since a level is 0..15 and each step costs one), with one light read at the window's top edge deciding the
  sweep. 8900–9700 µs whole-chunk → 213–281 µs windowed, same light.
- **Hoisted guards and the block-light skip** — per chunk rather than per change (apply 704 → 548 ns/change), and a
  provably safe skip for a change that makes an already dark cell fully opaque.

Each switch can be turned off by hand; the acceptance was re-run on the shipped defaults with no flags at all
(0.331 / 2.698 / 1.029 / 0.772 ms, canonical fingerprint).

### Identity, tooling and correctness

- **Identity**: mod id `lucistarlink`, display name LuciStarlink, renamed entrypoint, command and mixin config.
  Internal `ca.spottedleaf.starlight` packages and `scalablelux$` mixin prefixes are deliberately kept so the origin
  of every file stays visible.
- **Telemetry**: `SLTELEM` line every 30 s (queue depth, dirty positions, pooled propagators, prop counters);
  `-Dscalablelux.telemetrySeconds`, `0` disables.
- **Commands**: `/lucistarlink stats | light <pos> | relight [radius]` (permission level 2). `relight` is also the
  repair for a world saved by another light engine that recorded unfinished light as finished.
- **Client**: light publishing no longer re-sends a nibble whose bytes are unchanged; the client does no light
  computation of its own (verified in a real client session — 0 local recomputes over 22,711 sections).
- **Saves**: precise per-chunk "light incorrect" marking instead of blanket marking, so a load does not relight
  everything; the worldgen write path audited.
- **Compatibility**: Sable per-plot engines are deferred to Sable; ScalableLux/Starlight/Lucis are declared
  incompatible in the mod metadata.

### Closed and removed (kept in the record, not in the code)

Six approaches were implemented and measured, and are recorded with their numbers in
`docs/NEW-ENGINE-TEARDOWN.md`: whole-chunk recompute, the flat light mirror (two forms), the material table,
the small-burst dispatch rule, flush batching, and deferring the block-light half. Their code has been deleted —
including the whole-chunk `recomputeChunkSkyLight` and the material table's accessors — so that no closed path can
be reached by a stray flag.

### Known limits, stated up front

- `structure_cube` is dominated by Minecraft's own `Level.setBlockState` (~90% of the reading; 600–775 ns per change
  for every engine including vanilla), so no engine can win that cell by a large margin.
- On `sky_hole` the 1.x line's best round (27 ms) is faster than ours (50 ms).
- The leak classes fixed in 1.x (a byte-unbounded region cache, a coalescing map that grew forever) belong to
  Lucis's region-image design, which this line does not have at all: here light is owned per chunk and freed with
  it, so it is bounded by construction. That audit, and the parts of it that *were* portable (telemetry, tooling),
  are in [docs/PORT-LUCIS-IDEAS.md](docs/PORT-LUCIS-IDEAS.md).
