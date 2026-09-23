# Changelog

LuciStarlink 2.0 is a **new line**, not a continuation of 1.x: it is built on the ScalableLux light engine and
replaces the update path, where the 1.x line was built on Lucis and owned a region image. For the 1.x history see
the 1.x branch's own changelog; for what belongs to whom see [NOTICE](NOTICE) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).


## 2.0.4 — 2026-09-24

**The cause of 「只有一格」: an optimisation that drained the light queues with no engine caches set up, so nothing
propagated at all.** Reported after 2.0.3 as "still only one cell", with a screenshot of a torch lighting itself and
nothing around it.

- **Removed R4-1** (`scalablelux.batchDecrease`, `seedBlockChangesOnly`). It seeded each touched chunk and then called
  `performLightDecrease` **once** for the whole burst — after every cache had already been destroyed by the seeding
  step. A drain with no caches cannot read a neighbour or write a cell: it does nothing. Worse, the seeding step
  published (`updateVisible`) *before* that drain, so the only light that ever reached the visible layer — what the
  client is sent and what a save contains — was the emitter's own cell. That is exactly the reported symptom.
  The batch path now uses the base's own per-chunk routine, `blocksChangedInChunk`: setup caches → seed → drain →
  publish → destroy, in that order. The flag and the method are gone; there is no switch that can restore the
  shortcut.
- The three earlier causes stay fixed (2.0.2: the opaque-emitter skip rule and the per-tick settle point;
  2.0.3: the chunk key of (-1,-1) colliding with the flush's `-1` sentinel).

**Verified on the reporting player's own save, and the lesson is in the method**: reading the emitter's own cell is
not a test — it reads 15 even when nothing propagates. Checking the gradient is:

| cell | expected | before | after | after restart |
|---|---|---|---|---|
| the emitter | 15 | **15** (false pass) | 15 | 15 |
| 1 block west / east / down | 14 | **0** | 14 | 14 |
| 3 blocks west | 12 | **0** | 12 | 12 |


### Regression after 2.0.4, and the correction it forces

Clean window (CPU average 17.8%), defaults only, one round of the four cells:

| workload | 2.0.4 (work actually done) | the table quoted before | ScalableLux |
|---|---|---|---|
| `block_toggle_border` | **5.033 ms** | 0.32 ms | 4.01 ms |
| `structure_cube` | 2.788 ms | 2.51 ms | 4.97 ms |
| `dense_chunk_patch` | 1.073 ms | 1.07 ms | 3.65 ms |
| `sky_hole` | 0.563 ms | 0.69 ms | 0.88 ms |

**`block_toggle_border` regressed tenfold, and that is the point: the 0.32 ms was measured while the block-light half
of the work was not being done at all.** The "12x faster than ScalableLux on that cell" claim was an artefact of the
same defect this release fixes. With the drain actually running, this engine is *slower* than ScalableLux there
(5.03 vs 4.01) and roughly level with the 1.x line (0.84 in its own window — cell-level comparison needs the same
session). `structure_cube`, `dense_chunk_patch` and `sky_hole` remain wins on this reading.

**What this means for anything published:** the four-workload table needs a multi-round, same-session re-measurement
before it is quoted again, and the border cell specifically needs the R4-1 idea revisited *correctly* (a consolidated
drain that runs with proper caches, or a cheaper per-chunk drain) if it is to be won back. Until that exists, the
honest standing claim is: this engine is the correct one, it wins `structure_cube` and `dense_chunk_patch`, it is
competitive on `sky_hole`, and it loses `block_toggle_border`.

Numbering: `2.0.1` was the first release of the 2.0 line. The development builds were numbered `2.0.0-alpha.N` and
none of them was released; the measurement records in `docs/` refer to those jar names, so the version strings in
them are left as they were measured rather than rewritten.

## 2.0.3 — 2026-09-23

**Completes the fix for the first defect reported from play.** 2.0.2 fixed two real causes but not the one that explained the
reported cell, so the symptom survived it. Found by reproducing on a **copy of the reporting player's own save**, at their
own coordinates, with the edit trace on (`docs/BUG-EMITTER-BLOCK-LIGHT.md`).

- **The "keep this chunk" sentinel was `-1`, which is a real chunk key.** A chunk key is
  `(x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32)`, so chunk **(-1,-1)** — the chunk next to the origin, i.e. the one
  players spawn in — has key `-1L`. Every flush that was supposed to apply its buffered edits skipped it as "the chunk
  still being built", so **no edit in that one chunk ever reached the engine**: a light source placed there stayed dark
  live and after a save, while the same placement one chunk away worked. The flush no longer takes a sentinel at all —
  `keepOne=false` is an explicit parameter, and only the chunk a burst is still arriving for is held back.
- The two 2.0.2 changes stay (both are genuine defects that were also fixed): the opaque-emitter skip rule and the
  missing per-tick server-thread settle point.

Verified on a copy of the reporting player's save and then **in their own client**: clearing the cell and placing
glowstone reads `block=15 updBlock=15`, and it reads the same after saving, stopping and restarting.
Regression on the four acceptance workloads afterwards is recorded below.

**Two notes for whoever tests next.** A light source that was already saved *dark* does not repair itself — there is no
change to react to; use `/lucistarlink relight` or replace the block. And a test at (0,150,0) passing does not imply the
player's base passes: this defect only affected one chunk.

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

### Regression after 2.0.3, and one correction to the standing table

Clean window (CPU average 12.3%), defaults only, one round of the four cells: `block_toggle_border` 0.493 ms,
`structure_cube` 3.450 ms, `dense_chunk_patch` 1.103 ms, `sky_hole` 1.045 ms, player axis 48.8-50.2 ms per pass,
structure fingerprint still `sky=905931078dfc5ace`.

**`structure_cube` and `sky_hole` read worse than the 2.0.2 numbers (2.566 and 0.517), and that is the fix, not a
regression.** The measured box covers chunks -3..3, which **includes chunk (-1,-1)** — the chunk whose light updates
never propagated before this release. The earlier table was therefore measured while roughly a ninth of the box's
changes did no propagation work at all: flattering on exactly the two cells that depend on propagated volume. With the
work actually performed, the honest comparison against ScalableLux in the same window band is: border 0.493 vs 4.01
(still ~8x), structure 3.450 vs 4.97 (~1.4x), dense 1.103 vs 3.65 (~3.3x), and `sky_hole` 1.045 vs 0.88 — **behind on
that one cell on this reading**, where the pre-fix 0.69 was measured with a chunk of the box skipped.

**Therefore: the four-cell table needs a multi-round re-measurement before any performance claim is repeated**, and
the standing claim to quote until then is the narrow one — `block_toggle_border` and `dense_chunk_patch` are decisive
wins, `structure_cube` is a win, `sky_hole` is undecided between the two engines. The light itself is unaffected:
bit-identical to vanilla/ScalableLux on the verification box in every run.
