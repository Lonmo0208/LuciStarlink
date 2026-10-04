# Changelog

LuciStarlink 2.0 is a **new line**, not a continuation of 1.x: it is built on the ScalableLux light engine and
replaces the update path, where the 1.x line was built on Lucis and owned a region image. For the 1.x history see
the 1.x branch's own changelog; for what belongs to whom see [NOTICE](NOTICE) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).


## 2.0.17 — 2026-10-04

**The light-residue family is closed.** Three fixes stack up:

- **The publish-side merge skip** (`SWMRNibbleArray.updateVisible`): the HIDDEN/HIDDEN early return compared states
  only and never the array contents, so a section whose visible layer held stale light never merged. It now skips
  only when the arrays cannot differ; the merge itself zero-fills a null updating array instead of crashing.
- **The sky sweep's entry on de-initialised sections**: the column sweep read the gate cell one above the window;
  when the section holding it carried no stored sky data (HIDDEN/NULL — a state a removed bulk edit leaves behind)
  it read 0 and the whole column kept its shadow until a relight. The entry now falls back to the heightmap: a
  column whose first blocker sits at or below the gate cell is open sky and the run provably continues. Two crash
  guards came with it (a defensive allocation in `set()` and a zero-fill on the merge path).
- **The stray-array hardening (the autopsy's bycatch).** The last two cells the residue gate kept flagging were
  autopsied with the new `/lucistarlink layerdump` diagnostic and shown to be an *instrument artifact*: the section
  was legitimately de-initialised (ScalableLux's canonical representation of fully-open sky — its semantic reader
  derives 15 from the heightmap while the raw updating read is 0), and the user-visible light had been correct all
  along. The autopsy did expose a real latent defect: a stray updating array left on a de-initialised section
  survived the merge skip and would become the visible layer on the next write. `swapUpdatingAndMarkDirty` now
  starts a de-initialised section from empty on both sides, and the NULL/UNINIT merge drops stray arrays instead of
  leaking them. The residue gate reads **RESIDUE-FREE** under its corrected criterion (it now judges only the
  user-visible fields), and the light probe prints the per-layer SWMR state (`bSt=`/`sSt=`) so legitimate
  representation changes can no longer masquerade as residue.

**Performance, measured and adopted** (each with a paired A/B; details in `docs/HANDOVER.md` §10.48–10.57):

- **The flush-when-pending gate** — a change arriving after the tick's own flush settles at the first ask instead
  of waiting for the next tick; against the previous behaviour it won all four cells on the player axis.
- **Small-burst queue routing** (`scalablelux.queueSmallBursts`, default 32) — bursts of at most 32 changes per
  chunk take ScalableLux's own asynchronous path, which overlaps the apply tail: sky_hole −22%, structure −8%.
- **The capture cap flag** — a region whose captured burst crosses the lane cap stops capturing at once instead of
  feeding a buffer the flush will reject: structure's lane tax 12% → 8.5%, dense untouched.
- **The decrease-wave plane fast path** — a neighbour examination in a section with a material plane reads two flat
  bytes and no longer walks the palette (provably identical: a plane exists only where no state uses its shape for
  light occlusion). structure_cube went from +8–13% behind ScalableLux to a tie over six interleaved rounds.

Current standing vs ScalableLux (six interleaved rounds, one session, 2026-10-03): engine metric dense −3.6%,
structure tied, border +4% and sky_hole +8% behind; player axis one win, three ties, no losses.

**Telemetry is off by default** (owner request): `telemetrySeconds` defaults to `0`, so the periodic `SLTELEM`
console line no longer prints on servers that never asked for it. Measurement runs set the value explicitly and are
unaffected; a server that wants the line sets `telemetrySeconds` in `config/lucistarlink.properties` or
`-Dscalablelux.telemetrySeconds`.

Also probed and **rejected by their own paired A/Bs** in this cycle, kept off the default path: the lane
capture-context cache, `imageLaneMaxChanges=8192`, `imageLaneMinChanges=8`, the sky-window 15-sea skip and the
increase-wave plane path (both provably exact but unmeasurable below this machine's noise floor), and the shipped
`imageLaneSky` WindowSource (measured 2–4× *slower* on its own phase — it stays default-off).


## 2.0.16 — 2026-09-28

**A deferred sky recompute now owns its chunk.** A burst is split between two paths - the first position of each
batch goes to the engine queue, the remainder to the own-edit buffer - so on a chunk with a pending deferred
recompute both the base engine's own sky work and that recompute wrote the same cells, and the result depended on
which ran last. Measured on the correctness gate's 4x4x4 patch: the base path writes 14 into a pocket cell whose
canonical value is 13 while the recompute computes 13, so the cell was right in some runs and wrong in others. The
queue dispatch now skips the sky half for chunks with a pending recompute (a static set, marked at deferral and
cleared with the recomputes), leaving exactly one writer. Gate: all 22 probes read identically before and after a
relight, and the recompute still produces the canonical 14/13/13/14 under the patch.

Also measured and withdrawn in the same round: moving the guaranteed settle from the head of the tick to its end
(ServerTickEvent.Post). It is the more honest accounting - our work then falls inside the measured pass instead of
spilling into the next one - and the interleaved A/B reads it 4-13% slower on the engine metric (border 3.79 to 4.27,
structure 2.53 to 2.70, dense 1.04 to 1.08). Its player-axis benefit is unmeasured, so it is reverted until someone
measures that side properly.

## 2.0.15 — 2026-09-28

**One flush per tick: dense_chunk_patch 3.8x faster, structure_cube 15% faster.** The harness asks
`waitForPendingTasks` PER CHUNK - 289 times for its prepare ring - and this engine's `syncFuture` ran the whole
pending-edit flush on every one of those calls: region iteration, external-section drains, the deferred sky recompute.
A flush is idempotent until another change arrives and the tick hook already guarantees one per tick, so the repeats
were pure waste, and they were the bulk of the time HANDOVER 10.26 could not attribute to engine work (structure's
own engine work is 0.7 ms of a 5.62 ms pass). A single `lucis` helper, shared by the tick hook,
`hasUpdates` and `syncFuture`, is the whole change.

Three interleaved rounds with the order rotated: dense 9.54/3.95/5.47 ms before against 1.14/1.43/1.59 after (the
two sets never overlap), structure 5.08/5.99/5.30 against 4.60/3.90/4.50, border 5.52 against 5.08. So dense is now
roughly 1.1-1.6 ms where the 1.x line reads 2.85 - ahead by about 2x - and structure narrows from 1.52x to about
1.34x behind it. Border is unchanged in substance: its cost is the base engine's decrease wave.

## 2.0.14 — 2026-09-28

**The deferred sky recompute is column-scoped: 19x less work, and 27-29% off two cells' readings.** Profiling had
shown that `structure_cube` and `dense_chunk_patch` lose their biggest slice of this project's own cost to that
recompute - 2.11 ms in one call on structure, 32% of the cell's reading, and 0.96 ms on dense, 22% - and that the
expense is not the window itself but the drains after it: clearing every interior cell of the window turns the install
into a diff over the whole window, so the decrease and increase waves then rebuild the light of the entire 5x5 cache.

Two changes in `settleSkyWindow`, the same semantics done at column scope. The clearing now walks only the changed
columns, clearing the 15-run below each column's highest change - exactly the cells a new blocker darkens - instead of
every interior cell. And the seeding that 2.0.9 widened to every lit cell of every run is restricted to the columns that
changed or touch one: that rim is what a shadowed pocket actually needs, because the window BFS has no upward
direction, while in a sunlit world the unrestricted form seeded most cells of the window (9374 seeds and 7 ms of BFS on
the gate's patch).

Acceptance: the gate's 22 probes read identically before and after a relight with the lane on, and the fingerprints
stay canonical. Measured: the recompute's total cost falls from 2.11 ms to 0.112 ms on structure (19x) and from 0.96 ms
to 0.278 ms on dense (3.5x), with 256 seeds instead of 3584 and 81-205 us of window work. A three-round same-window A/B
with the order rotated reads structure 7.48 -> 5.30 ms and dense 5.98 -> 4.35 ms, i.e. 29% and 27% faster, with border
unchanged within noise (it has no recompute to save).

So two of the three cells this engine still loses to the 1.x line on the engine metric are recoverable inside the
current architecture; border - which is the base engine's block-light decrease wave, with this layer contributing
25 us a pass - is not, and needs the storage rewrite.

## 2.0.12 — 2026-09-27

**The region image can carry both light halves exactly**, which is the precondition for the image becoming the
storage rather than a cache that packs back. `-Dscalablelux.mirrorSky=true` adopts each materialised section's sky
half alongside its block half (same nibble array, same canonical `minLightSection` indexing), and
`-Dscalablelux.mirrorVerify=true` compares both halves against the nibbles cell by cell and byte by byte: 8/8
identical on real chunks, and the switch is inert for light - the gate reads identically with it on and off, and
the default configuration is unchanged.

What the measurement bought is a design decision with a number: adoption costs 212-228 µs a section and packing
back 600-646, i.e. ~0.85 ms a section, so a design that keeps the nibbles as the storage and mirrors them costs
~20 ms a chunk before any material work. That variant is out. What remains is the image being the storage itself:
writes land in their final form, there is no pack step, and saves and the client read the image directly.

## 2.0.11 — 2026-09-27

**The material-plane write funnel is free when no plane exists.** It hangs off
`LevelChunkSection.setBlockState`, so it ran for *every block write in the world*, worldgen included, and with the
image lane disabled (the shipped default) no plane is ever created - which meant a concurrent-map lookup per write
for nothing. A `volatile boolean` set only where planes are created or evicted now short-circuits it to a single
read. Measured same-window against pristine ScalableLux with both features off: 213 ns a change in the apply phase
against 147 before (18% slower overall), 448-471 against 517-521 after (15% faster), on two rounds with the order
reversed.

## 2.0.10 — 2026-09-27

**Two fixes, both found and verified in the gate.** They matter because the image lane is the mechanism this engine's
engine-axis work runs on, even though it ships disabled.

**A 16-block misread in the lane.** With `-Dscalablelux.imageLane=true`, a placed 4x4x4 glowstone patch read
`block=0/1` in the world and the cells around it were dragged down by a decrease wave; `/lucistarlink relight`
repaired it, because the relight is the base engine's work and does not use the lane's mapping. The per-chunk
nibble array starts at `minLightSection` (26 entries for 24 build sections), while the lane indexed it with a
build-section index - 16 blocks off - so it adopted the light of the section below (the bottom one is
"uninitialised", which silently skipped the adoption and left a zeroed light plane) and packed its results into
that wrong section. Fixed at three sites with the canonical `sectionY - WorldUtil.getMinLightSection(level)`;
acceptance is that with the lane on, all 22 correctness probes read identically before and after a relight. The
four-cell fingerprints never caught it because those chunks are also processed by the base queue, whose correct
writes overwrote the lane's field.

**Packed material extraction** (`ImageMaterialPlanes`): a section is now read at the packed level - one
`storage.get(cell)` per cell plus one material lookup per *distinct* palette index - whenever no state in it is
shape-occluding, with a fallback to the per-cell path if the palette is wider than 64 or the handles cannot be
resolved, so it can only ever be a lost optimisation. Measured on a played-in save (global palettes): 198-220 us
a section against the per-cell path's 413-448 us (2.1x), with all 4096 opacity+emission bytes identical on the
sections checked.

## 2.0.9 — 2026-09-27

**A correctness defect a player would have seen: a bulk edit left the sky light of the affected cells exactly as it
was before.** `/fill`ing a 4×4×4 glowstone patch into a sky-lit area read `sky=15` for the whole patch — the interior
of a solid block, which must be 0 — and the cell underneath read 15 where the correct value is 13. A full relight
(`/lucistarlink relight`) repaired it, so no save-and-reload check could see it, but in the world it is **sunlight
shining through a roof that was just built**. Reproduced six times. The same shape reaches the engine through `/fill`,
structure generation, world-edit tools and any mod that places many blocks at once.

**Cause.** A bulk edit's sky half is deferred to a windowed recompute (`SkyStarLightEngine.settleSkyWindow`), and that
routine seeded its working field from the *current* stored light and then only ever raised values: the open part of a
column is set to 15, and the BFS writes a neighbour only `if (target > light[nIndex])`. Any cell that had to become
*darker* kept its old value, and `performLightDecrease` had nothing to drain because its queue was never seeded. The
routine was increase-only while claiming to be a canonical recompute.

**Fix (three changes, all in `settleSkyWindow`):**

- the window's interior is cleared before the sweep, so the field is rebuilt from the world's material instead of
  edited on top of the old light. The two slabs at the window's `yLo`/`yHi` are deliberately kept: any path from a
  change to them crosses at least 16 levels, which a field of at most 15 cannot do, so their values are provably
  unaffected by the edit — and the cell above `yHi` is only read through the "== 15" shortcut, so a slab lit from above
  by less than daylight would not be re-derived if it were cleared.
- every lit cell of a column's run is seeded, not only those up to the highest-ending neighbour run. That bound was a
  cost trick, and it also dropped the sources a shadowed pocket needs: this BFS has no upward direction, so a cell open
  at, say, y=-38 can only light its neighbour at the same height from a seed at that height. With the bound in place
  the 4×4 shadow under the test patch read a flat 12 where the canonical field has 14/13/13/14.
- the two preserved slabs seed the BFS at their own level: they are the window's outer boundary and the only light that
  enters it from outside.

**Acceptance.** The correctness gate (`tools/rig/gate-gradient.sh`) now reads **the same values before and after a full
relight** on all 22 probes — interior 0, the shadow pocket 14/13/13/14, the cell below 13, the open columns 15. The
deferred recompute is finally equivalent to the full relight whose name it borrows. All four workload fingerprints are
unchanged (`sky=905931078dfc5ace`; this release fingerprints all four workloads, not only `structure_cube`).

**Cost, measured rather than assumed.** An interleaved same-window A/B of the two builds (two rounds, order reversed)
reads the fixed build 14–15% slower on `structure_cube`, 24–36% on `dense_chunk_patch`, 22–40% on `sky_hole`, and
ambiguous on `block_toggle_border`; the player axis is identical on both sides (49–50 ms, all four). The recompute's
own cost is not the cause — it measures 0.13–0.21 ms a call — the extra time is the *correct* work it now performs
(writing the darkened cells, pushing the neighbour updates), which parts of the base pipeline had been skipping. The
light is right, so the fix stays.

**Also in this release** (work removals on the capture path; gate-green, the four fingerprints unchanged, and below the
resolution of a loaded window on the benchmark metrics — recorded as that rather than claimed): the per-change
`System.nanoTime()` pair now runs only with the profiler on, the capture no longer allocates a `ChunkPos` per change,
and a material pair memo (`ImageMaterialCache.lookupLightPair`) collapses the two per-change material lookups for the
uniform transitions a fill produces — only pairs whose materials are position-independent are memoized, so a hit is
always exact (measured −26% of the capture on `dense_chunk_patch`).

## 2.0.8 — 2026-09-24

**`block_toggle_border` is no longer lost.** It was this engine's worst cell (5.62–6.37 ms against ScalableLux's
5.06–5.76 ms when measured interleaved, and up to 8.4 ms in a loaded window) and the one number nobody could explain.
It is fixed, and the fix is a **routing rule, not an optimisation**: a buffered burst of at most 16 changes in one
chunk now goes back to the engine's own asynchronous path instead of being settled in the inline lane.

**Why that is the right answer.** The inline lane exists to skip the queue turnaround, and skipping it is worth the
price of a synchronous cache setup + drain + publish per chunk only when the burst is large. `block_toggle_border`
edits ~5 blocks per chunk across 19 chunks, so the lane paid that price 18–23 times per pass for bursts that the
engine's own thread settles more cheaply. The bulk workloads never see the rule (their per-chunk bursts are 25 for
`sky_hole`, 2048 for `dense_chunk_patch`, 256+ for `structure_cube`), and a burst that is not one chunk's is never
dispatched. It is also a *deterministic* rule, which matters: under load the tick hook can fire in the middle of an
apply loop and split a burst, and before this change that split decided which path each chunk took.

**Measured, interleaved, same session** (four routes on the border workload, two rounds each):

| route | `minPass` round 1 | round 2 | mean per pass, round 1 / 2 |
|---|---|---|---|
| inline lane (2.0.7 behaviour) | 6.37 ms | 5.62 ms | 8.12 / 7.14 ms |
| inline lane, sky seeded per position | 6.00 | 6.11 | 7.19 / 7.10 |
| **this rule (2.0.8)** | **5.33** | **5.37** | **6.11 / 6.87** |
| stock ScalableLux | 5.06 | 5.76 | 5.94 / 7.29 |

The final two-round interleaved pair against ScalableLux reads 5.30 / 5.96 ms for this engine and 5.10 / 5.64 ms for
ScalableLux — a statistical tie on the cell that used to be a 25–35% loss.

**Why it was not found earlier, recorded so the mistake is not repeated.** The dispatch rule already existed, with its
default off, and its own comment said measurement had closed it. Those measurements were taken in a window where the
block-light half of a large burst was not being computed at all (the null-position-set defect fixed in 2.0.6), so the
"0.43 ms" they compared against was the price of *skipping* the work. A rule that sends work away looks bad exactly
while the work is free. The same mistake had already been made once on this project (`bulkRelight`, `pendingFlushSize`)
and both were re-measured after 2.0.6 for the same reason.

**The honest state of the other three cells after this change** (two interleaved rounds each, this machine, loaded
window — the 2.0.7 table was taken in a lighter one):

| cell | 2.0.8 | ScalableLux | reading |
|---|---|---|---|
| `structure_cube` | 4.71 / 5.85 ms | 5.99 / 6.32 ms | **won in both rounds** (12–21%); light fingerprint bit-identical to vanilla and ScalableLux |
| `dense_chunk_patch` | 5.42 / 4.93 | 4.63 / 4.83 | lost by 2–17% in this window; won by 5% in the 2.0.7 window — **not established either way** |
| `sky_hole` | 1.11 / 1.12 | 0.85 / 1.61 | sign flips between rounds — **not established** |
| `block_toggle_border` | 5.30 / 5.96 | 5.10 / 5.64 | tie (was a 25–35% loss) |

So 2.0.7's "three of four cells" claim is **withdrawn as not reproducible**: on this machine, in a loaded window, only
`structure_cube` is a stable win, and the other three cells sit within noise on either side. The reason is measurable
rather than mysterious: on all four workloads this engine and ScalableLux process the *same number of BFS pops* (101,087
vs 101,133 on the border workload), i.e. the light propagation itself is identical work done by identical code, and the
differences that remain are per-drain overhead and machine load. A decisive `dense`/`sky_hole`/`border` win would need
an algorithm that does *less* work; the two candidates investigated (a block-light window recompute, and holding
scattered bursts until the settle point so the group rule merges three chunks per drain) were both measured and do not
pay on these shapes.

**Also in this release** (measurement plumbing, no behaviour change): the profiler's per-pop `System.nanoTime()` pairs
now have their own switch, `-Dscalablelux.profileTiming=true` (with `-Dscalablelux.profile=true` the counters are exact
and no longer cost ~3 ms a pass on the border workload), and the inline lane's per-group phases are now timed
unconditionally so they can be read in an honest (profiler-off) run.

**Correctness gate for this release**: the emitter gradient across a chunk border reads `15 / 14 / 14 / 14 / 13 / 10`
(the three placements in this test now take the dispatched path, so the gate exercises the new default), and
`structure_cube`'s fingerprint is `sky=905931078dfc5ace block=59e2252f732ce67b` — identical to vanilla and to
ScalableLux.


## 2.0.7 — 2026-09-24

**`dense_chunk_patch` and `sky_hole` are won as well — three of the four cells now beat both predecessors on the
engine metric.** The lever was where the light work runs, not what it does.

**The measurement that found it.** The harness records the apply phase and the wait phase separately, and on
`dense_chunk_patch` they read:

| | apply | wait | per change (apply) |
|---|---|---|---|
| before | **20.8 ms** | 0.01 ms | 3388 ns |
| ScalableLux | 2.2 ms | 9.8 ms | 543 ns |

That is the whole story: this engine computes the light **inside the `setBlock` loop**, because the edit buffer
flushed every 256 changes and every flush settles its share with a full cache setup, a drain and a publish — and a
drain walks the light that is already there. One 4096-change burst therefore repeated that walk sixteen times, in the
apply phase, while ScalableLux handed the same work to the light thread once.

**The change: `LUCIS_PENDING_FLUSH_SIZE` 256 → 8192** (a system-property override stays). One burst now settles once.
Measured on the same harness: apply 20.8 → 2.5 ms, `minPass` 5.91 → **3.56 ms** against ScalableLux's 4.32. The tick
hook still settles every tick, so a larger buffer can cost at most one tick of latency — never correctness.

**The change that was tried and rejected**: removing the flush that happens when the burst walks into a new chunk.
It looked right for `block_toggle_border` (many chunks, few changes each) and won that cell by 0.2 ms — but
`sky_hole` went 0.55 → 1.32 ms and `dense` 3.56 → 4.42 ms in the same two-round interleaved comparison, so it was
reverted. Recorded here because the reasoning is attractive and the measurement says no.

**`block_toggle_border` remains lost (5.58 vs ScalableLux's 4.16), and it is now the only unexplained number in this
project.** On it the two engines produce byte-identical light *and* an identical world-state hash, run propagation code
that is ScalableLux's verbatim (checked both as source and as compiled bytecode, which is the same size), and the
bytecode-instrumentation, the inline lane, the sky strategy, the buffer size, the bulk-relight path, position boxing,
GC/allocation (JFR: 73 vs 67 young collections) and the mixin layer (all mixins semantically identical to upstream)
have each been measured and ruled out. The engine spends ~1.5x more per BFS pop than its own upstream on identical
work, and that is where the next attempt has to start.
## 2.0.7 — 2026-09-24

**`dense_chunk_patch` and `sky_hole` are won as well — three of the four cells now beat both predecessors on the
engine metric.** The lever was where the light work runs, not what it does.

**The measurement that found it.** The harness records the apply phase and the wait phase separately, and on
`dense_chunk_patch` they read:

| | apply | wait | per change (apply) |
|---|---|---|---|
| before | **20.8 ms** | 0.01 ms | 3388 ns |
| ScalableLux | 2.2 ms | 9.8 ms | 543 ns |

That is the whole story: this engine computes the light **inside the `setBlock` loop**, because the edit buffer
flushed every 256 changes and every flush settles its share with a full cache setup, a drain and a publish — and a
drain walks the light that is already there. One 4096-change burst therefore repeated that walk sixteen times, in the
apply phase, while ScalableLux handed the same work to the light thread once.

**The change: `LUCIS_PENDING_FLUSH_SIZE` 256 → 8192** (a system-property override stays). One burst now settles once.
Measured on the same harness: apply 20.8 → 2.5 ms, `minPass` 5.91 → **3.56 ms** against ScalableLux's 4.32. The tick
hook still settles every tick, so a larger buffer can cost at most one tick of latency — never correctness.

**The change that was tried and rejected**: removing the flush that happens when the burst walks into a new chunk.
It looked right for `block_toggle_border` (many chunks, few changes each) and won that cell by 0.2 ms — but
`sky_hole` went 0.55 → 1.32 ms and `dense` 3.56 → 4.42 ms in the same two-round interleaved comparison, so it was
reverted. Recorded here because the reasoning is attractive and the measurement says no.

**`block_toggle_border` remains lost (5.58 vs ScalableLux's 4.16), and it is now the only unexplained number in this
project.** On it the two engines produce byte-identical light *and* an identical world-state hash, run propagation code
that is ScalableLux's verbatim (checked both as source and as compiled bytecode, which is the same size), and the
bytecode-instrumentation, the inline lane, the sky strategy, the buffer size, the bulk-relight path, position boxing,
GC/allocation (JFR: 73 vs 67 young collections) and the mixin layer (all mixins semantically identical to upstream)
have each been measured and ruled out. The engine spends ~1.5x more per BFS pop than its own upstream on identical
work, and that is where the next attempt has to start.

### Acceptance after 2.0.7 (same session, interleaved, 3 rounds each)

| workload | LuciStarlink 2.0.7 | ScalableLux | 1.x (its own window) |
|---|---|---|---|
| `structure_cube` | **3.09 ms** | 4.77 ms | 2.87 ms |
| `dense_chunk_patch` | **3.72 ms** | 3.93 ms | 2.36 ms |
| `sky_hole` | **0.61 ms** | 0.95 ms | 0.81 ms |
| `block_toggle_border` | 5.62 ms | **4.12 ms** | 0.76 ms |

**Against ScalableLux: three cells won, one lost.** Structure's fingerprint is still `sky=905931078dfc5ace`
(bit-identical to vanilla and to ScalableLux) in every run.

**Against the 1.x line: it still leads the engine metric on three cells** — its region-batched engine does small
synchronous work in a way this one does not — while trailing on the player axis by 20–35 ms on three of four
(1.x 67/72/86 ms against 49–51 ms here and for ScalableLux). Its light is identical to neither vanilla nor
ScalableLux; ours is identical to both.


## 2.0.6 — 2026-09-24

**Fixes the defect behind the reported light loss after large edits**: the block-light half of every burst past the
defer threshold was **never computed at all** — a null position set was passed into the block engine, the resulting
`NullPointerException` was caught by a `catch (Throwable)` that only incremented an invisible counter, and the sky half
went on to be settled by the recompute. So a large fill computed its skylight and none of its block light.

Found by following the report 「重新进存档有光残留」 with a two-pass light dump of the player's own save (dump, force a
relight, dump again) and then a controlled fill/clear of a 8192-block volume on a copy of it:

| what the dump showed | before | after |
|---|---|---|
| a 8192-block glowstone fill, cells at their own level 15 | **6 of 208 cells lit at all** | **154 cells at 15**, the rest a correct 14/13 gradient |
| `ownEditBatched` (positions the engine actually processed) | **0** | **32 768** |
| swallowed failures (`ownEditFallback`) | **128** | **0** |

The null was introduced with the deferred-sky path (2.0.1) and survived every release since: `blockPositions` was built
as `deferSky ? null : toBlockPositions(positions)`, and the deferred branch handed that `null` to
`blocksChangedInChunk`. Every burst at or above the defer threshold — 128 changes through 2.0.4, 4 changes in 2.0.5 —
therefore lost its block light, while small bursts (the light-source gate's path, and every test that used one or two
changes) worked, which is exactly why it went unnoticed.

Two more changes so this class of failure cannot hide again:

- **The silent catch now logs.** A burst that fails to settle logs an ERROR with its exception (first occurrence, then
  every 1024th) instead of only bumping a counter that is invisible unless the profiler is on.
- **`relight` clears before it relights.** It marked chunks light-incorrect and called `lightChunk`, which *seeds* the
  chunk's light sources and propagates — it never zeroed what was already stored, so stale block light survived a
  relight untouched (verified: a cell holding level 1 with no source read the same before and after). It now zeroes the
  chunk's block nibbles first, which is what "repair this chunk's block light" has to mean. Verified the other way
  round on a correct area: a torch's full 14/13/12/11/10/9 field was zeroed and came back identical, which also proves
  the relight republishes what it computes.

**Not a defect: the light the report was about.** The area the player pointed at holds a plain `minecraft:torch` at
(11,-60,-22) — emission 14 — plus two redstone wall torches, and the "residue" was that torch's own field (14 at its
cell, decaying 13/12/11/10/9 around it), while the genuinely *dark* ground beside it was the bug above. A coarse dump
grid (every two blocks) had skipped the torch's cell, which is what made it look like stale light for a while.

**Performance consequence, to be measured rather than assumed:** with the block-light half actually running for large
bursts, the two workloads whose bursts deferred their sky work (`structure_cube`, `dense_chunk_patch`) are doing work
they were previously skipping, so their numbers will move. The acceptance table is re-measured with this release and
recorded below.

### Three-round acceptance, and the first table measured with the block half actually running

Same session, interleaved, 3 rounds per side, medians; window load CPU 25.7% average. **This is the first table in
which the block-light half of the heavy workloads is really computed** — every earlier one skipped it (the defect
above), which is why the numbers move so much:

| workload | 2.0.6 | ScalableLux | 1.x | verdict |
|---|---|---|---|---|
| `block_toggle_border` | 5.85 ms | 4.33 ms | **0.76 ms** | lost to both |
| `structure_cube` | **2.90 ms** | 4.62 ms | 2.87 ms | win vs both |
| `dense_chunk_patch` | 6.21 ms | **3.90 ms** | 2.36 ms | lost |
| `sky_hole` | **0.56 ms** | 0.78 ms | 0.81 ms | win vs both |

Player axis: 48–51 ms on all four for this engine and ScalableLux (the one-tick floor), 1.x 20–35 ms behind on three of
four. Structure fingerprint canonical in every run.

**What the loss is, and what it is not.** It is the block-light half being *seeded per changed position* — each change
costs a `checkBlock` (~1.2 µs) and its share of the propagation cascade — for workloads made of thousands of emitter
changes (`dense_chunk_patch` is 2048 glowstone changes a pass). Two candidate levers were measured against this and
both came back null: the bulk-relight path (`scalablelux.bulkRelight`, −9% on `dense`, inside noise) and the flush
size (`scalablelux.pendingFlushSize`, 4096 was *worse* than 256 on all three). Those two were also the levers the
earlier campaign closed — but that campaign measured them while this very half was not running, so their closures were
re-opened, re-measured here, and are now genuinely closed.

**So the standing claim after 2.0.6 is two wins and two losses** (`structure_cube`, `sky_hole` against both
predecessors; `block_toggle_border`, `dense_chunk_patch` lost), with the correct light that none of the three earlier
tables had. Making the block-light seeding cheaper is the open lever, and it is a real one: it is now the single
biggest cost in the two cells we lose.

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

## 2.0.13 — 2026-09-27

**A lane ownership invariant, and the proof that the alarm rings.** `-Dscalablelux.laneInvariant=true` compares every
section the region image claims to own against the world's nibbles, cell by cell, right after each settle's pack; a
mismatch names the section and counts the cells. It is the check the 16-block misread of 2.0.10 would have failed in
seconds - there the image computed the right light and the pack wrote it one section too low while every other reading
looked healthy. On the fixed lane it reports nothing.

Because two checks fooled me earlier the same day (an empty log judged "all identical", and a verification routine with
its own wrong index), this one was validated the other way round: with the pack deliberately sabotaged to write one
section too high it reports immediately (24, 111 and 2510 mismatching cells in the first bad sections), and after
reverting the sabotage it is silent again. A check that cannot fail is not a check.

## 2.0.17 — 2026-09-29

**The deferred sky window keeps its algorithm and takes its data from the image.** `settleSkyWindow` grew a
`WindowSource`: the sweep, the seeding rules, the five-direction BFS, the install and the boundary push stay entirely
the engine's, and the source only answers where the two arrays it works on come from. The image lane answers from the
region's material plane (a 4096-byte array the write funnel already keeps current) and from the same copy of the
nibbles it adopts into its sky plane in that pass, which turns the per-cell palette walk into an array read. Sections
whose material was never extracted are refused rather than approximated, so the fallback is the world-sourced fill
exactly as before; the engine hands the installed window back so the image holds the result instead of a stale copy.
Off by default (`-Dscalablelux.imageLaneSky=true`); with it off, nothing changes.

Acceptance, all three legs: the correctness gate reads identically on all 22 probes before and after a relight (and
the source counters prove a real window was sourced, not a silent fallback); `structure_cube`'s canonical fingerprint
`sky=905931078dfc5ace` is identical on both sides in all three rounds; and the interleaved same-jar A/B over three
rounds reads 0.929 (border), 1.063 (structure) and 0.930 (dense) - signs flip between rounds, so **on the metric this
is not established** (dense and structure need six rounds, per 10.28's rule), while no cell is clearly worse and the
light is byte-identical.

Also corrected in this round: the slice-1.5 claim of an equivalent region-granular recompute (HANDOVER 10.36) is
withdrawn. An honest probe - comparing the two routes on the same window in the same pass instead of comparing the
plane with itself - showed the region form is a *different algorithm* (288 of 9216 cells on the gate's patch: it
seeds every run instead of the changed rim, walks six directions instead of five, and crosses chunk edges the
production routine never crosses), and that the per-column clearing used the wrong columns entirely (region-local
0..15 where the changed chunk is 16..31), which is why it could not lower a single cell. The region form stays as
gated diagnostic code; the ship path is the source substitution.
