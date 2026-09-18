# LuciStarlink handover: what is left, with evidence and acceptance criteria

Project: `E:\LuciStarlin\LuciStarlink` (mod id `lucistarlink`, MC 1.21.1 / NeoForge 21.1.234-235).
Artifact: `dist/lucistarlink-1.21.1-0.1.0.jar` (md5 in `docs/verify-baseline.txt`). **Git now exists** (2 commits,
baseline + dist); from here every change is a revertable commit.
Tests: 21 pass / 0 fail / 1 inherited skip.
Docs: `README.md`, `NOTICE` (LGPL change list), `docs/roadmap-and-provenance.md` (§7/§8 hold the measurement
chain), `docs/ARCH-V2-GLOBAL-STORAGE.md` (next architecture, with the risk list this session extended), this file.
Test rig: `E:\LuciStarlin\mc-smoketest` (`fourway.sh`, `ls-run.sh`, `ls-border-scenario.sh`, `bench-compare.sh`,
`stage-ab.sh`, `rig-jar.jar` + `rig/rig-jar.jar` = a pre-session build kept for before/after comparisons).

## Build / run rules (violating these costs an hour - all of them were hit for real)

1. **JDK 21 for everything that runs Minecraft AND for Gradle itself**:
   `export JAVA_HOME="C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"` **and**
   `export PATH="/c/Users/Administrator/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin:$PATH"`.
   PATH alone is not enough: `JAVA_HOME` pointed at Zulu 25 made `gradlew build` fail with
   `DefaultReportContainer: Type T not present` (a Gradle daemon/JVM mismatch that looks like a code error).
2. **TLS**: `export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` or Gradle/dependency
   downloads fail with `PKIX path building failed`.
3. **Rig jar**: the shipped jar declares ScalableLux/Lucis *incompatible* (correct product behaviour). To load
   them for comparison, build the rig variant: `gradlew build -PbenchmarkAllowScalableLux=true
   -PbenchmarkAllowLucis=true` and copy the result to `mc-smoketest/rig-jar.jar`. Verify with
   `unzip -p rig-jar.jar META-INF/neoforge.mods.toml | grep -A1 'modId = "lucis"'` -> must say
   `type = "discouraged"` (a plain `gradlew build` overwrites it back to `incompatible`).
4. **Lucis 2.0 cannot coexist with LuciStarlink** (both `@Redirect` the same `setBlock` in
   `LevelChunk.postProcessGeneration`), so Lucis is measured alone with its own harness (`-Dlucis.*` properties,
   `-Dlucistarlink.enabled=false` is not enough).
5. Cross-session wall times are NOT comparable (same config measured 11 ms and 50.6 ms in different sessions).
   Trust within-run stage metrics, and compare **per-pass minima** (`minPassNanos` in
   `lucistarlink-light-benchmark.jsonl`), median over >=5 repeats, for all engines alike.
6. **Kill stray servers before a rig run**: a leftover `java @user_jvm_args.txt` process holds `logs/` and the
   world directory, so the next run fails with `rm: cannot remove 'logs/latest.log': Device or resource busy` or
   crashes at boot. Find them with
   `powershell -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*user_jvm_args*' }"`.

## How the benchmark actually measures (learned the hard way)

`LuxServerBenchmark` records, per pass, `waitCompleteNanos - currentStartNanos` where `waitCompleteNanos` is set
by the harness's own wait futures. Facts established this session, with counters now in the harness:

* `bench.barrier.poll` = 4 for 4 measured passes: the barrier passes on its **first** poll, i.e. all light work
  finished inside the same tick as the apply. `bench.pass_wall_actual` is therefore exactly one tick (~50 ms)
  and says nothing about the engine; the recorded per-pass number is "time from apply until the light engine's
  mailboxes report idle".
* `ThreadedLevelLightEngine.runUpdate()` calls `super.runLightUpdates()` unconditionally, so **whatever we left
  queued in the engine is absorbed inside the barrier's own pass** - the engine-side cost of our publication is
  always in the measured window, regardless of when our own drain runs.
* The harness logs (2 per pass) are inside the window; that overhead is shared by every engine.
* Number of published sections varies 3-4x between runs of the same config (worldgen streaming), so a single
  wall number is close to meaningless. Per-pass minima are stable and are what we compare.

Instruments added this session (all `-Dlucistarlink.debug=true`):
`bench.barrier.{poll,futurePending,runtimePending,worldgenPending,enginePending}`, `bench.pass_wall_actual`,
`bench.pass_ticks`, `publish_batch.{queueLatency,runLightUpdates,notify}`, `publish_direct.{queueData,notifyQueue}`,
`publish.notify.posted`, `worldgen.publish.{core,halo}.sections`, plus the pre-existing stage metrics.

## Unresolved 1 (performance): `sky_hole` is still ~0.9 ms above the acceptance line

Current state (per-pass min, median over 4-5 reps): queued route **0.87 ms**, direct install **0.60 ms**
(`-Dlucistarlink.directSectionInstall=true`); wall minima 4.92 ms and 3.78 ms respectively. ScalableLux's own
per-pass minimum in the same harness is ~0.69-0.80 ms, vanilla ~1.0 ms.

Mechanism, now measured rather than guessed: the cost is **the hand-off to the engine**, split in two:
(a) the engine's next `runLightUpdates` pass re-deriving/absorbing what we handed over
(`markNewInconsistencies`, `swapSectionMap` full section-map copy per drain, notifications) - removed by the
direct install, measured -31% on per-pass minimum;
(b) **the scheduling round-trip itself**, which survives the direct install because the engine's storage is
single-writer (light thread only): our publish still goes sorter mailbox -> private drain queue (with the
250 us `publishCoalesceNanos`) -> taskMailbox, while the barrier waits on a separate `runUpdate` task.

Rejected hypotheses (do not re-test): halo machinery (`runtimeHaloChunks=0`), measurement artifact, our sky
algorithm (`stage.runtime.incremental.sky` 0.2-0.5 ms), per-job pipeline handoff, the notification fan-out
(dropping it to radius 0 saves 62% of `publish_direct` and changes no wall number - reverted), the coalescing
delay alone (`publishCoalesceNanos=0`, no effect), worldgen core-only publishing (worth ~4% and much lower
variance, but not the missing 0.9 ms).

Next action (in order):
1. Decide the scheduling question with the evidence now in hand (see ARCH-V2 §3 stage 2 and risk 8): the piggyback
   variant implemented this session (`piggybackPublish`, default off) commits at `runUpdate` HEAD via a vanilla
   task trigger and measured **worse** (per-pass minimum 0.48 -> 1.24 ms, plus one 14.6 ms stall), because the
   trigger waits for vanilla's `tryScheduleUpdate`. The 250 us private drain is currently the *better* trigger.
   Two remaining routes: (a) a prompt, deterministic trigger for the same-pass commit; (b) do small edits inline
   on the server thread (compute *and* commit) the way ScalableLux does, which is where its 0.69-0.80 ms
   per-pass minimum comes from.
2. Only after the correctness items below: re-measure with >=5 reps and per-pass minima, four-way.

Also settled this session, so nobody re-derives them: the sorter-mailbox callbacks of the light engine run on the
light thread itself (both handles are backed by the same processor mailbox), so a second task there is a queue
hop and not a thread wakeup; and a mixin class cannot live in `net.minecraft.server.level` under NeoForge - the
module system rejects the split package at boot (`ResolutionException: Module minecraft contains package ...`).

## Unresolved 2 (open bug, now reproducible): our engine occasionally writes block light vanilla does not

The band probe (y 90..112, above the fluid interactions, optional y bounds in `/lucistarlink dumplight`) is a
usable oracle after all: `ls-border-edit.sh` places glowstone across the chunk border at x=15/16 through the
runtime path, and **vanilla reads sky `013c3846466ff9d5` / block `61fb027e7d51ee31` over x 0..31 in every run
(3/3)**, `edit_border` (x 8..24) `sky c4aed9ab13e5d3b5 / block 8f33931a596c664c`.

Our engine reads exactly that in most runs, but in the rest it reads a second, equally stable value
(`sky 013c3846466ff9d5 / block 4d50732804a9a46d`). Counted so far: **7 conforming, 6 divergent** runs. What is
established about it:

* **Both publish routes show it** - the default queued route and `directSectionInstall=true`. It is therefore NOT
  the install mechanism, and the in-place byte copy added this session (which does remove a real lost-write
  hazard when the layer object was swapped under the light thread) did not change the rate: 2/5 divergent.
* **Sky light is identical to vanilla in every run**, divergent ones included. Only block light moves.
* **Not sticky**: vanilla immediately after a divergent run reads the vanilla value again, and our next run
  usually does too. It is a computation/publish path that picks the other branch sometimes, not a saved state,
  and it does not corrupt the world for other engines.
* The divergent value is itself reproducible when it appears, so it is a deterministic alternative outcome
  (a timing-dependent branch), not noise - which is what makes it debuggable.
* `haloPublish=false` is NOT a valid single-variable probe here: it changes what is published at all (the band
  covers the neighbour chunk) and reads a third value by design.

Next actions:
1. Single-variable bisect on the runtime path, all with the band probe and >=3 runs per config:
   `experimentalRuntimeAdoption=false` (in flight), then `experimentalDenseIncremental=false`,
   `experimentalSectionFastPath=false`, `experimentalSkySeedSkip=false`.
2. If adoption is implicated, the hole is that a runtime job may adopt a neighbour's baseline, then publish into
   it after another job moved it - the same class the `externalMarked`/`rerunBaselineMoved` guard covers, so the
   guard has a window. A per-section publish sequence ("do not overwrite engine data newer than your image") is
   the candidate fix, exactly as ARCH-V2 risk 7 says.
3. Only after that: revisit whether the faster `directSectionInstall` route can become the default (it currently
   cannot - same divergence rate, plus it skips the engine's re-check).

## Unresolved 3 (correctness): worldgen border ordering

`LuxRelighter.markLightStaleNeighbours` was wired in the wrong place (called *before* the image was computed, so
it compared un-computed data and the counter could never fire - that, not "neighbours generate concurrently", is
why `worldgen.neighbourStale.marked` read 0). It now runs after the sky/block compute and only when the
neighbour will *not* be published (with halo publishing on, every changed neighbour cell is published, so
nothing can be stale). The counter is now visible in `/lucistarlink status`
(`worldgen neighbour-stale marked <n>`).

Scenario for it: `mc-smoketest/ls-border-scenario.sh gen <tag>` (strip, then one chunk beyond) - run it with
`-Dlucistarlink.worldgenHaloPublish=false` and check the counter is > 0.

Residual known gap (unchanged, still needs the conservative flag or a chunk-save-time check): neighbours that are
*unloaded* at generate time cannot be marked, so their saved light can stay stale.

## Unresolved 4: interop measurement never run

Run a worldgen stress scenario with C2ME and Generator Accelerator installed and require no light divergence.
Jar not present in the rig (`mc-smoketest/mods` expects `c2me-*.jar` / `*Generator Accelerator*.jar`).

## Unresolved 5: cleanup

* `experimentalBoundaryDeltas` and `BorderDeltaSupport` are a superseded prototype (known to oscillate); dead
  code, default off. Safe to delete once nothing references them.
* `haloChunks` (worldgen halo) vs `runtimeHaloChunks` (runtime halo) are easy to confuse in config.
* Config switches added this session and their status: `worldgenHaloPublish` (default **true**, measured ~4%
  and lower variance when false, but leaves a seam until the neighbour is relit - see ARCH-V2 risk 7),
  `directSectionInstall` (default **false**: fastest measured configuration but blocked by Unresolved 2).

## Not started (feature work, not perf)

* Client-side lighting takeover (Starlight/ScalableLux have it; we are server-side only).
* Intra-job parallelism (FlowSched style) - only matters for large `regionChunks` or huge relights.
* Client test: connect a dev client, place a light source on a chunk border, and require the client screen to
  update immediately (the risk of any publish path that skips `queueSectionData`). Indirect evidence available
  today: the notification fan-out counter (`publish.notify.posted`) still fires for every published section, and
  the save/reload fingerprint is stable.

## Verification tooling (new, use these)

* `mc-smoketest/ls-run.sh <tag> <reps> [extra -D ...]` - single-workload benchmark run, prints wall + the publish metrics.
* `mc-smoketest/ls-border-scenario.sh gen|reload <tag> [extra -D ...]` - strip -> one chunk beyond -> fingerprints,
  then `save-all flush` + stop; `reload` boots the same world and fingerprints again (save/reload round trip).
  `DUMP1_DELAY`/`DUMP2_DELAY` (seconds, default 25) push the two dumps later for a longer settle - needed if the
  world is still changing at read time (see Unresolved 2). The script refuses to run if the generated datapack
  functions contain unexpanded shell syntax.
* `mc-smoketest/ls-border-edit.sh <tag> [extra -D ...]` - same world, but places glowstone across a chunk border
  through the runtime path and fingerprints it: the runtime-border counterpart of the worldgen scenario.
* `/lucistarlink dumplight <label> <x1> <z1> <x2> <z2>` - quiescence-gated light fingerprint, logged as
  `LUCIS_LIGHT_FINGERPRINT label=... sky=... block=... samples=... quiesceTicks=...`. Sky light is a usable oracle
  (bit-stable across runs and identical to vanilla); block light is NOT until the world is deterministic by
  construction - vanilla itself varies there.
* `/lucistarlink flags` / `status` - prints `worldgenHaloPublish`, `directSectionInstall` and the worldgen
  neighbour-stale counter.
* Rig lessons that cost time today: datapack functions cannot use `stop`/`save-all` (feed them on stdin after a
  delay - the script does) and a *quoted* heredoc in a generator script silently puts `${VAR}` into the
  datapack, which makes the whole function fail to load and the scenario never run (hence the self-check);
  `JAVA_HOME`, not just `PATH`, must point at JDK 21 or Gradle fails with a confusing internal error.
