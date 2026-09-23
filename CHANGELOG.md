# Changelog

LuciStarlink 2.0 is a **new line**, not a continuation of 1.x: it is built on the ScalableLux light engine and
replaces the update path, where the 1.x line was built on Lucis and owned a region image. For the 1.x history see
the 1.x branch's own changelog; for what belongs to whom see [NOTICE](NOTICE) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).


## 2.0.5 — 2026-09-24

**Wins `block_toggle_border` back — the cell 2.0.4 had to concede — without giving up the correctness 2.0.4
restored.** Two changes, and the second is the one that pays:

1. **Chunks that share one cache window are settled together.** The base settles one chunk at a time (setup caches →
   seed → drain → publish → destroy), so a burst along a chunk border — many small changes over many chunks — paid a
   full setup and publish for each of them. The seeding half of that routine is now a separate entry point
   (`seedChanges`), and the flush groups the chunks of one burst by cache window: every chunk within ±1 chunk of the
   group's centre, which is exactly the bound that keeps the group's propagation inside the caches (a change moves
   light at most one chunk, the caches cover ±2). The drain and the publish happen once per group, in that order,
   **with the caches still up** — the 2.0.3/2.0.4 defect was both of those things done the other way round.

2. **The sky recompute threshold dropped from 128 changes to 4**, because the old value rested on a wrong assumption:
   that the windowed recompute only pays for *bulk* bursts. It pays for *small* ones too. The BFS is not priced per
   change but per cascade — a few block toggles on a border measured ~950 queue pops per change at ~65 ns each — while
   the recompute's cost is fixed by the y window the burst touches. Measured on the same harness and window
   (`block_toggle_border`, per-chunk bursts of ~5 changes):

   | threshold | `block_toggle_border` | `sky_hole` |
   |---|---|---|
   | 128 (before) | 4.876 ms | 0.614 ms |
   | 16 | 5.071 ms | 0.537 ms |
   | **4 (now)** | **1.471 ms** | **0.588 ms** |
   | 1 | 1.837 ms | 1.416 ms (outlier run) |

   16 sits above the border bursts' size, so they fall back to the BFS and keep the old cost; 4 keeps them on the
   recompute. A burst below 4 changes stays on the BFS — that is the path the light-source gate exercises, and it is
   unchanged.

**Correctness was re-verified on the new paths before any timing was believed**, because 2.0.4's lesson is that the
emitter's own cell reads 15 whether or not anything propagates:

- six light sources placed in **one burst spanning a chunk border** (so the recompute path and the grouping both
  apply): the gradient reads exactly 12 / 15 / 15 / 15 / 12 and 14 / 14 one block off-axis;
- the single-source gate (`tools/emitter-gate/`, BFS path) still passes, neighbours 14, three blocks out 12;
- the structure fingerprint stays `sky=905931078dfc5ace`, bit-identical to vanilla and ScalableLux.

Full three-round, three-engine table after this release is in the "Regression" section below.

### Three-round, three-engine acceptance after 2.0.5

Same session, interleaved, 3 rounds per side, medians. Window load: CPU average 35.9%, peak 68.1% (a loaded window —
quote the ratios, which the interleaving makes valid).

**Engine metric — `minPassNanos`, ms:**

| workload | LuciStarlink 2.0.5 | ScalableLux | 1.x | verdict |
|---|---|---|---|---|
| `block_toggle_border` | **1.51** | 4.54 | 0.81 | **won back from ScalableLux (3x)**; 1.x still ahead |
| `structure_cube` | **2.99** | 4.78 | 3.22 | win vs both |
| `dense_chunk_patch` | **1.11** | 3.77 | 2.39 | win vs both |
| `sky_hole` | 1.06 | 0.98 | 0.81 | within noise of both; last in this window |

**Player metric — `bench.pass_wall_actual`, ms (median / best):**

| workload | LuciStarlink 2.0.5 | ScalableLux | 1.x |
|---|---|---|---|
| border | 50 / 48 | 49 / 47 | 80 / 72 |
| structure | 50 / 48 | 50 / 50 | 60 / 53 |
| dense | 50 / 48 | 50 / 48 | 68 / 48 |
| sky_hole | **49 / 48** | 50 / 50 | 71 / 63 |

Fingerprint on every one of our runs: `sky=905931078dfc5ace` — bit-identical to vanilla and ScalableLux. 1.x's is
`641356fc41163add`, identical to neither.

**Where this leaves the three engines, honestly:**

- `block_toggle_border`: **this engine is now 3× faster than ScalableLux** (1.51 vs 4.54) after being 22% slower than it
  in the 2.0.4 table, and 3.3× faster than its own 2.0.4 self (5.00). The 1.x line remains ahead on this cell (0.81)
  — that is its region-batched architecture doing small synchronous work it was built for, and closing that last gap
  would mean a different engine, not a different schedule.
- `structure_cube` and `dense_chunk_patch`: won against both predecessors.
- `sky_hole`: all three engines are within 0.81–1.06 ms of each other and the ordering moves with machine load; this
  cell has never been stably decided at this round count, and it is not claimed either way.


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


### Regression after 2.0.4, three rounds, three engines, and the correction it forces

Same session, interleaved (the three engines alternate round by round), 3 rounds per side, per-round medians.
Window load: CPU average 30.3%, peak 48.7% — a loaded window, so quote the **ratios between sides** (which the
interleaving makes valid) and treat the absolute values as the player-facing dataset rather than a protocol-valid
absolute.

**Engine metric — `minPassNanos`, ms (3-round median):**

| workload | LuciStarlink 2.0.4 | ScalableLux | 1.x | verdict |
|---|---|---|---|---|
| `block_toggle_border` | 5.00 | 4.18 | **1.22** | **lost** — behind both predecessors |
| `structure_cube` | **2.78** | 4.62 | 2.97 | **win** vs both |
| `dense_chunk_patch` | **1.07** | 4.07 | 2.35 | **win** vs both |
| `sky_hole` | **0.69** | 0.83 | 1.06 | **win** vs both |

**Player metric — `bench.pass_wall_actual`, ms (median / best round):**

| workload | LuciStarlink 2.0.4 | ScalableLux | 1.x |
|---|---|---|---|
| border | 49 / 48 | 50 / 49 | 103 / 72 |
| structure | 48 / 46 | 49 / 48 | 61 / 49 |
| dense | 50 / 49 | 50 / 49 | 70 / 51 |
| sky_hole | 50 / 49 | 49 / 49 | 67 / 62 |

Structure fingerprint on every one of our runs: `sky=905931078dfc5ace` (bit-identical to vanilla and ScalableLux);
1.x's is `641356fc41163add`, i.e. identical to neither.

**The correction this forces, stated plainly:** the table quoted before 2.0.4 — border 0.32 ms, "12× faster than
ScalableLux" — was an artefact of the defect this release fixes; that workload's block-light propagation was not being
done. With the work done, **this engine loses `block_toggle_border` to both ScalableLux (4.18) and the 1.x line
(1.22)**, and wins the other three against both. On the player axis it sits on the one-tick floor (48–50 ms) together
with ScalableLux, ahead of 1.x on three of four.

**What that leaves, honestly:** the engine is correct now (its light is bit-identical to vanilla, and propagation is
verified by gradient rather than by the emitter's own cell), it is the fastest of the three on `structure_cube`,
`dense_chunk_patch` and `sky_hole`, and it is the slowest of the three on `block_toggle_border`. Winning that cell
back means re-designing the consolidated-drain idea so the drain runs with proper caches — not restoring the code
that was removed.

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
