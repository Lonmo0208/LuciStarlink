# Changelog

## 1.2.8 — the halo-emission fix from PR #1's branch, and the cross-region decrease defect written up as open

**Merged**: `lbw14514` pushed a second commit to the same branch after PR #1 (`2ae33d0`) and upstream master was
not updated, so it came in from the fork. It clears the halo's *emission* during a lazy extraction, keeping its
opacity - clearing that would declare the neighbour to be air and let light cross the border, the opposite
error. A stale emission is a light source, and a full relight then leaves a ring of light along the region
border.

**Verified**: 40 tests, 0 failures. The world-generation half that PR #1 was about stays bit-identical to
vanilla in the nether ceiling band (see 1.2.7). The defect this commit was also expected to fix - residual
light after looping a large `/fill` between glowstone and air and stopping on the air step - is **still open**.
With the now-reproducible probe (`mc-smoketest/ls-refill-probe.sh`; a `prep` phase generates the world first, so
an air state has a canonical fingerprint) the air step reads `45b3fe947d9ab5cd` against the all-dark canonical
`b93a0c83ce3b6325`, where the untouched build reads `3206f1df4c73167c`: the fix changes the residue's shape and
does not remove it. Targeted block assertions prove the cells are air while the engine still holds a single
source point in the neighbouring chunk. Full evidence, the two measured repair attempts that were reverted
rather than shipped, and the remaining mechanism (a neighbouring region with no changes of its own republishes
the stale copy it took from us) are in `docs/BUG-CROSSREGION-LIGHT-DECREASE.md`; `docs/HANDOVER.md` carries it
as Unresolved 6, with `/lucistarlink relight 256` as the player-facing workaround.

**CI**: the workflow no longer uploads the jar as a build artifact - release artifacts stay local (`dist/`, md5
recorded in `docs/verify-baseline.txt`) and off GitHub. It still compiles and runs the test suite on every push
and pull request.

## 1.2.7 — naturally generated light sources get vanilla's light (community PR #1)

**The defect** (reported in play): the light around a *naturally generated* light source - a village torch, a
lava lake, nether glowstone - was wrong until the player touched a block nearby, which is the "touch it and
it repairs itself" report. Root cause, in two halves. Halo *materials* are materialised on demand, and only
the runtime path has the machinery that fills them in, so a world-generation job propagated with its
neighbours read as air (light through walls) and then published that wrong halo light into those neighbours,
overwriting light they had already computed correctly. Separately, a structure or feature writing into an
already-loaded, ticking neighbour had its `checkBlock` cancelled *and* its enqueue suppressed by the
world-generation write scope, so that light stayed at its old value forever.

**The fix**: `extract`/`populate` take an explicit `lazyHaloMaterials` flag, and the two full-recompute paths
(world generation, `/lucistarlink relight`) pass false while the runtime path keeps the lazy behaviour it has
the machinery for. Extraction moved into the world-generation worker, so the ~5.6 ms per chunk stays off the
chunk-generation thread. Enqueue no longer honours the world-generation suppression - the caller has already
established the target chunk is `BLOCK_TICKING`, i.e. that the change is not covered by the generating
chunk's own full compute - and work dropped by a full queue now opens a backpressure window instead of
vanishing. Five smaller concurrency/accounting fixes come with it (`compute`-scoped enqueueing, an
`AtomicReference` swap for the external-section set, materialisation marks invalidated with the materials,
rolled-back job preparation, one Sable presence answer), plus the removal of the rejected cross-region
boundary-delta prototype.

**Verified** (this repository's own check, same machine and session): in a fresh nether with
`randomTickSpeed 0` and 16 force-loaded chunks, the ceiling band y=100..125 - the band full of naturally
generated glowstone - now matches vanilla **bit for bit**: `543a8d1f6d6e6847` over 102,400 cells and
`3bc44605ff1713c6` over the 32x32 inner box, identical to the same jar with
`-Dlucistarlink.enableWorldgen=false`, where the pre-fix build differs from vanilla in both boxes
(`5dbe73c282d5567e` / `55dc0ebb67c817b3`). The cell-level diff shows the pre-fix leak as a `123456789` ramp
crossing the chunk border through solid rock, and a `3 -> 2` error on the far side of the same source.
38 tests, 0 failures; a dev server and client ran a real session on the build with nothing in the log.
The "an already-lit area survives the generation of the chunk next door" probe passes on *both* builds - said
plainly in `docs/BUG-WORLDGEN-NATURAL-LIGHT.md`, which also lists what is still open: the runtime
full-relight path keeps lazy halo materials, the world-generation in-flight cap doubled, and one
reservation-counting window that predates this change.

## 1.2.6 — the completion marker stops waiting, and two default-off V3 experiments

**The measurable win of this release**: the benchmark's completion marker no longer waits for the light
publish queue's 250 us coalescing window. The per-field breakdown showed the whole `sky_hole` deficit sat in
`wait` (528-1503 us against ScalableLux's 3-314) tracking `queueLat`, while every stage that did work was a
rounding error and the workload published zero sections. `wait` fell to **235-265 us** (ScalableLux:
226-265) and the five-rep interleaved median from **1.583x to 1.233x** - the best this workload has ever
measured. The flush path also drains unconditionally now (sky_hole publishes nothing, and the old
`published` guard skipped the synchronous drain for exactly that shape); that measured neutral and stays for
correctness.

**Also here**: the client-side optimisation (light data a client receives per two minutes fell from 11,729
sections to 5,783 - about 24 MB to 11.8 MB - by notifying only the neighbours whose shared face changed;
26 tests, 3/3 adjacent-pair probes, four-workload interleave with no regression) and the restart-truncation
fix from 1.1.5.

**V3 experiments, default off, both refuted with numbers**: a synchronous small-edit path (`syncSmallEdits`)
that computes and installs in the calling thread with no hand-off - correctness-verified by 4/4 bit-identical
probes, measured as a no-op on a loaded machine (7.607 vs 8.190 ms, p=1.0); and skipping the section status
registration on that path (13.148 vs 13.467 ms, ratio 0.976, p=1.0). The earlier `inlineDrain` was 10x worse
in a healthy window. The conclusion, and the corrected premise ("small edits rebuild the region image" is
false - the image is built once per region and the steady state is already incremental), are in
`docs/ARCH-V3-SYNC-STORAGE.md` section 6.

## 1.2.5 — the client-side cut, and the probe gate's second catch

Every publish marked all 27 sections of the 3x3x3 neighbourhood and the server sent all of them: a client
joining the rig server received **11,729 sections (about 24 MB) in two minutes**. `publishDirect` now
compares the six face planes of a section's old and new bytes and notifies only the neighbours whose shared
face changed - **5,783 sections, a 51% cut** - which is safe by construction because a neighbour's light and
mesh can only depend on the layer it shares with this section, and because the probe shows our client does no
light computation at all (0 `propagateLightSources` per session). Verified by 26 tests, 3/3 bit-identical
adjacent-pair probes, a four-workload interleave with no regression, and the client-volume measurement. The
in-game seam screenshot could not be taken on the rig (a stale file handle on the rig's log files, no JVM
holding it) and is better checked on a real server; if a seam ever appears, the revert is one line.

## 1.2.4 — cleanup round: naming, hygiene, and a removal that is mapped instead of rushed

* **The two halo keys now say which path they belong to.** `haloChunks` is the world-generation halo and
  `runtimeHaloChunks` is the runtime (block update) halo; a reader could not tell them apart at a glance. Each
  comment now opens with its path in brackets, warns explicitly not to confuse the two, and cross-references the
  other. **Key names unchanged on purpose** — renaming would invalidate every existing configuration.
* **`dev/lucistarlink/test/package-info.java`**: why the benchmark harness deliberately lives in the main source set
  (the rig jar is built from it, so measuring must build the code that ships) and the discipline that follows —
  everything default-off, and no measurement code may change default behaviour.
* A stray javadoc block in `LuxRuntimeManager` (left floating above the new peak fields) is merged into one comment.
* README and CHANGELOG are aligned with the current numbers: one standing table instead of two, the 1.2.2 default
  change, and the honest summary **"two workloads decisively ahead, two behind"**.
* **`BorderDeltaSupport` + `experimentalBoundaryDeltas` are mapped, not deleted.** Tracing every reference showed
  the dead prototype reaches the *light engine compute classes* (`skyLightEngine`/`blockLightEngine.applyBoundaryDeltas`)
  through the runtime batch, the queue merge and `LuxRuntimeManager`, plus five sites in `CrossRegionDifferentialTest`.
  That spans the publish layer, the runtime layer and the engine itself — the one part of this project that has
  produced two real bugs — so it gets a dedicated round with the full site list and current line numbers in
  `docs/WORK-ORDER-REMAINING.md` §4, and the acceptance that comes with it (26 tests + four-workload interleave,
  expected zero behaviour change because it is default-off).

## 1.2.0 / 1.2.1 / 1.2.2 / 1.2.3 — the client line, two defects the gates caught, and the synchronous default

These four shipped while the client feature line and the `sky_hole` campaign were being finished; they are grouped
here so the record is complete.

**1.2.0 — client light line, step 1+2 (both default off).** A read-only client probe (`ClientLightEngineProbeMixin`
+ `LuxClientLightProbe`) counting what the client's own light engine actually does, and a conservative takeover
(`LuxClientLightEngine` + `ClientChunkCacheMixin`, hooked at the single construction point) that only no-ops
`checkBlock` and `propagateLightSources` while keeping vanilla's data structures and pipeline intact (Sodium and
shaders read them; `queueSectionData` writes the storage synchronously and `runLightUpdates` promotes it, so skipping
that would freeze the client's light). **Measured in-world and the premise failed**: a vanilla client reports
`propagateLightSources` 0 calls and 108 `checkBlock` calls over a session — the client never computes light, it
receives it. Shipped as a default-off switch and a probe tool, not as a performance feature.
(`docs/ARCH-V2-CLIENT-LIGHTING.md`)

**1.2.1 — the probe gate caught a real defect.** Enabling the two synchronous switches made
`lucistarlink$installSection` cast a layer's listener to `LightEngineAccessor` unconditionally, but a layer with no
light engine (a dimension without block light) hands out `DummyLightLayerEventListener`: a `ClassCastException` on a
worker thread, logged and swallowed. Guarded with an `instanceof` check. This is why the gate exists.

**1.2.2 — the synchronous-publish combination becomes the default.** `sky_hole`, same-session interleaved, exact
p: default 0.912 vs 0.573 = **1.583x, p=0.0079** (significantly behind) → `directSectionInstall + syncRuntimeDrain`
0.719 vs 0.573 = **1.256x, p=0.0556** (no longer significant), with ScalableLux's baseline stable at 0.573-0.576
across both groups. Four-workload guardrail: border **2.1x ahead**, structure **1.42x ahead**, dense 1.22x behind,
sky_hole behind — the two wins are intact. Probe gate with the new defaults: fingerprints bit-identical to the
engine-off control. **Closer, not an overtake**: every mechanism that could close the rest has been measured, and
what remains is ~0.15 ms of inherent async-hand-off cost.

**1.2.3 — V2 items 1 and 2.** Memory telemetry keeps **high-water marks** (a 30 s sampler provably misses the work:
45 samples read zero while the engine was busy); soak evidence over 5,563 sustained passes: peaks 2,067 queued
changes (cap 131,072), 10 queued regions, region cache 49 regions / 165 MiB (cap 128 / 256 MiB) — everything inside
budget, and the current values at the end read zero, i.e. without the peaks that run would have looked like the
inconclusive one. **In-job parallelism decided: do not do it** — `/lucistarlink relight 64` over 1,024 loaded chunks
and 6,334 emitters measured 9,062 ms, of which **8,694 ms (96%) repairing emitters** and 368 ms scanning; block
changes must stay on the server thread, so there is nothing to parallelise. The command now reports that breakdown
itself.

## 1.1.5 — the bug that mattered: every restart truncated block light

Reported from a live server and reproduced here: after **every** restart, light sources lit only their own block,
and breaking and replacing a block repaired it only until the next restart. **The save was fine** - reloading the
same world with the mod's engine disabled gave bit-identical fingerprints - so the damage happened **at load**: our
`lightChunk` hook did not distinguish a chunk that declares its light correct from a freshly generated one, and
relighting a loaded chunk produced an emitter-only field (at load time the engine has no light installed yet to use
as a propagation baseline) which was then published **over** the good saved data.

* `lightChunk` now leaves a chunk alone when `chunk.isLightCorrect()` is true - the saved light is authoritative.
  Freshly generated chunks report false and still get our relight, so worldgen lighting is unchanged.
* `publishDirect` also writes `visibleSectionData` (the map chunk packets and serialization read) instead of relying
  on the engine's own update cycle to promote what `queueSectionData` puts into `updatingSectionData`.

Evidence, every line a bit-identical fingerprint comparison on one world: engine OFF (control) identical; engine ON
before the fix, all three block-light labels differed while sky stayed identical; engine ON with
`-Dlucistarlink.enableWorldgen=false` identical (isolating the load-time relight); engine ON with the gate,
identical. The runtime border-edit probe (`ls-border-edit.sh`) matches the engine-off control on both labels, so the
runtime path is untouched. 26 tests green, metadata `incompatible`.

**A world that was already damaged stays damaged** - the broken field is what is on disk. Repair each area once with
`/lucistarlink relight 256`; from this version on, restarts no longer undo it.

## 1.1.4 / 1.1.3 / 1.1.2 — the repair command, and two attempts that did not turn out to be the cause

These three were shipped while chasing the restart bug and are documented here so the record is complete:

* **1.1.4** - `/lucistarlink relight` now repairs **full-cube** emitters as well (glowstone, sea lanterns) by breaking
  and re-placing them within the same tick with the state restored in a `finally`; non-full blocks (torches) keep the
  cheaper no-op change in an air cell beside them. Fluids (lava) and blocks with block entities are skipped on
  purpose. The rule came from the user's own testing: a change beside an emitter repairs torches but not glowstone.
* **1.1.3** - the first version of that repair: a no-op `air -> stone -> air` in an air cell beside every emitter.
  Correct for torches, ineffective for full blocks, which 1.1.4 fixed.
* **1.1.2** - `LuxPublishEngine.forcePublishIdentical` (system property, default off; switched on for the duration of
  `relight`): publish sections even when the computed bytes equal what the engine holds. It was aimed at the
  "identical sections are skipped, so nothing tells the engine to re-propagate" theory; that theory was wrong for this
  bug, and the switch is kept as a documented lever, not as a fix.

## 1.1.1 — `/lucistarlink relight`: after switching engines, light that nothing would ever recompute

A server that had been running ScalableLux and then switched to this mod came up with light sources lighting only
their own block: the saved light had been written **with propagation still pending** while being marked as
complete, and nothing relights a chunk that loads as light-complete, so the missing spread stayed missing until the
block was broken and replaced (which dirties the section and wakes the runtime path). The save-side safety added in
1.0.0 covers chunks with *our* pending work, but it cannot see a foreign engine's pending work. Two halves:

* **`/lucistarlink relight [radiusChunks]`** (permission level 2): marks the loaded chunks of the current dimension
  within the radius as light-incorrect and hands each one to the engine's own `lightChunk` path — the same path
  chunk generation uses, so cross-region halo publication and the external-section refresh apply unchanged. The scan
  uses `getChunkNow` because 1.21.1 has no public way to enumerate loaded chunks, hence a radius (capped at 256).
  Because the marking is written into the save, a job that fails is retried on the next load instead of leaving a
  permanent black patch. Verified in a dev server: `relight 2` queued 25 chunks, no exceptions.
* For the whole world **including chunks that are not loaded**: set `forceLightIncorrectOnSave=true` (config, or
  `-Dlucistarlink.forceLightIncorrectOnSave=true`), run `/save-all flush`, then restart **without** the switch —
  every saved chunk comes back marked as needing light and is recomputed on load. Leaving it on relights everything
  on every boot, so use it as a one-shot.

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
