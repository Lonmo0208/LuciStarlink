# LuciStarlink handover: what is left, with evidence and acceptance criteria

Project: `E:\LuciStarlin\LuciStarlink` (mod id `lucistarlink`, MC 1.21.1 / NeoForge 21.1.234-235).
Artifact: `build/libs/lucistarlink-1.21.1-0.1.0.jar`. Tests: 21 pass / 0 fail / 1 inherited skip.
Docs: `README.md`, `NOTICE` (LGPL change list), `docs/roadmap-and-provenance.md` (§7/§8 hold the measurement
chain), this file. Test rig: `E:\LuciStarlin\mc-smoketest` (`fourway.sh`, `bench-compare.sh`, `stage-ab.sh`,
`rig-jar.jar`, three comparison jars).

## Build / run rules (violating these costs an hour - all four were hit for real)

1. **JDK 21 for everything that runs Minecraft**: `export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"`. Using the system `java` (26) fails with `Unsupported class file major version 70`.
2. **TLS**: `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` or Gradle/dependency downloads fail with `PKIX path building failed`.
3. **Rig jar**: the shipped jar declares ScalableLux/Lucis *incompatible* (correct product behaviour). To load them
   for comparison, build the rig variant: `gradlew build -PbenchmarkAllowScalableLux=true -PbenchmarkAllowLucis=true`
   and copy the result to `mc-smoketest/rig-jar.jar`. Verify with `unzip -p rig-jar.jar META-INF/neoforge.mods.toml | grep -A1 'modId = "lucis"'` -> must say `type = "discouraged"` (a plain `gradlew build` overwrites it back to `incompatible`).
4. **Lucis 2.0 cannot coexist with LuciStarlink** (both `@Redirect` the same `setBlock` in `LevelChunk.postProcessGeneration`),
   so Lucis is measured alone with its own harness (`-Dlucis.*` properties, `-Dlucistarlink.enabled=false` is not enough).
5. Cross-session wall times are NOT comparable (same config measured 11 ms and 50.6 ms in different sessions). Trust
   within-run stage metrics and min over >=3 repeated runs.

## Unresolved 1 (performance): `sky_hole` is ~1.5 ms behind ScalableLux

Facts: ours min 3.58-4.27 / median ~5.0 ms; ScalableLux min 2.83 / median 3.22 ms; vanilla ~3.9-5.4; Lucis ~4.7-8.1.
The other three workloads we win clearly (dense 10.2/10.8 vs SL 18.8/26.3 vs Lucis 10.4/11.1 vs vanilla 19.6/60.0;
block_toggle_border 3.9/6.9 vs SL 12.0/16.2; structure_cube 8.6/11.6 vs SL 19.9/28.5 vs Lucis 12.0/12.0).

Hypotheses already tested and REJECTED (do not re-test):
* halo machinery - `runtimeHaloChunks=0` gives the same time (4.44 vs 4.76 ms min)
* measurement artifact - adding a generic barrier that also waits for `ThreadedLevelLightEngine.hasLightWork()`
  did not change ScalableLux's number (3.1 -> 3.2 ms); that barrier change is kept in the harness
* our sky algorithm - `stage.runtime.incremental.sky` is only 0.2-0.5 ms total
* per-job pipeline handoff - small-batch inline execution is implemented (`runtime.inlineBatchChanges`, default 8,
  fires 30+ times per run) and changed nothing

Remaining, measured candidate: **the publication path back into the vanilla engine**. Per run for `sky_hole`:
`publish_batch.drain` 9.36 ms / 21 calls, `publish_direct` 2.81 ms / 37 calls, 566 sections published (420 skipped as
identical), for only ~100 edits (a hole legitimately changes whole sky columns, so many sections genuinely change).
ScalableLux updates its storage in place; we hand every section to the engine (mailbox + `queueSectionData` +
`updateSectionStatus` + a 3x3x3 notification fan-out per section).

Next actions, in order:
1. Reduce the notification fan-out in `ThreadedLevelLightEngineMixin.lucistarlink$queueAffectedLightNotifications`
   (27 notifications per published section; batch-level dedup exists, the fan-out itself is the cost).
2. Coalesce publication: one batched submission per chunk instead of per section, and/or publish once at the end of
   a drain instead of per result.
3. Consider writing into the engine's `DataLayer` storage directly while already on the light thread, if semantics allow.

Acceptance: `sky_hole` min <= 3 ms over >=3 runs, with `halo sections published` > 0 and `external sections refreshed`
> 0 (i.e. cross-region correctness still active), and `< 21 tests, 0 failures`.

Measured wins to preserve (do not regress): lazy light materialisation (`init.adopt` 435 -> 0.9 ms) and lazy material
extraction (`init.extract` 56.8 -> 5.6-13.9 ms), both under `-Dlucistarlink.lazyHaloLight` (now default true).

## Unresolved 2 (correctness): worldgen border ordering

Symptom: a chunk generated at the edge of already-loaded terrain publishes only itself; an already light-correct
neighbour can keep stale light (players see a seam) until something else relights it. Today the conservative
`forceLightIncorrectOnSave=true` (relights every chunk on load) is the fallback.

Implemented but NOT verified: `LuxRelighter.markLightStaleNeighbours` + `isStaleAgainstEngine` compare the
neighbour-side cell layer of the freshly computed image against the engine's stored light and clear
`lightCorrect` on neighbours that differ (counter `lucistarlink.worldgen.neighbourStale.marked`). The counter reads 0
in the benchmark scenarios because those generate neighbours concurrently (engine light absent -> skipped by design).

Next action: build the scenario that actually exercises it - pre-generate and load a strip of chunks, then generate
one more chunk beyond the strip, and check: counter > 0, the neighbour's `isLightCorrect()` flipped, and after a
save/reload the neighbour gets relit. Also confirm the comparison cost (one run showed 15.6 ms vs the usual
9.7-13.6 ms on dense_chunk_patch - possibly the comparison, possibly noise; normalise per chunk).

Residual known gap: neighbours that are *unloaded* at generate time cannot be marked (their saved light stays stale);
that case still needs the conservative flag or a chunk-save-time check.

## Unresolved 3 (small): interop measurement never run

Roadmap item: run a worldgen stress scenario with C2ME and Generator Accelerator installed and require no light
divergence. Jar not present in the rig (`mc-smoketest/mods` expects `c2me-*.jar` / `*Generator Accelerator*.jar` for
the project's own benchmark tasks).

## Unresolved 4 (small): cleanup

* `experimentalBoundaryDeltas` and `BorderDeltaSupport` are a superseded prototype (known to oscillate); now dead
  code, default off. Safe to delete once nothing references them.
* `haloChunks` (worldgen halo) vs `runtimeHaloChunks` (runtime halo) are easy to confuse in config; consider renaming
  or documenting in the config comments.

## Not started (feature work, not perf)

* Client-side lighting takeover (Starlight/ScalableLux have it; we are server-side only). Needs a separate engine path
  against the client's single-threaded light engine; benefit is client-side only.
* Intra-job parallelism (FlowSched style) - only matters for large `regionChunks` or huge relights.

## Un-verified changes from the last session (verify or revert)

* `markLightStaleNeighbours` (Unresolved 2) - compiles, tests green, mechanism unexercised.
* Small-batch inline execution - implemented, fires, no measured effect on `sky_hole`; harmless but unproven value.
* Border-proximity gate on the halo pipeline (`changesNearBorder`) - implemented, no measured effect.
* Generic drain barrier in the harness (waits for vanilla light work) - kept for fairness.
