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

## Unresolved 1 (performance): the bar is met, ScalableLux is still ahead on `sky_hole`

Measured this session on the same rig, per-pass minima from `lucistarlink-light-benchmark.jsonl`:

| engine | `sky_hole` per-pass minima | wall total (4 passes) |
|---|---|---|
| ours, shipped default (region route), 5 runs | 0.651 / 1.018 / 0.976 / 0.602 / 0.595 -> **median 0.651 ms** | 4.55 / 4.75 / 4.82 / 4.90 / 4.30 |
| ScalableLux, 3 runs | 0.598 / 0.299 / 0.593 -> **median 0.593 ms** | 3.05 / 2.15 / 2.82 |

So the agreed bar (per-pass minimum median <= 1.0 ms, which replaced the old "wall <= 3.0 ms" after the
measurement-discipline change) **is met by the shipped default**, but ScalableLux is still ~10% ahead per pass,
has a much better best pass (0.299 ms) and is ~1.5x ahead on the wall number. The gap is specific to this
workload: this session's other three read dense_chunk_patch 9.49, block_toggle_border 5.25, structure_cube 8.40
(walls), against the same day's four-way numbers for SL (18.8/26.3, 12.0/16.2, 19.9/28.5).

Why: our light work reaches the engine asynchronously (job -> publish queue -> drain on the light thread), while
ScalableLux computes and commits inside the tick, so its measured pass contains no hand-off at all. Two remaining
routes, both written up in ARCH-V2 §3 stage 2: (a) a prompt, deterministic trigger that lands our commit in the
same `runUpdate` the barrier waits for; (b) compute *and* commit small edits inline on the server thread, the way
ScalableLux does. The storage prototype (`directSectionInstall`) removes the engine-side absorption and measured
0.599 ms median per-pass minimum, i.e. no better than the default within noise, and it carries the divergence
rate of Unresolved 2 - so it stays off.

Mechanisms settled this session (do not re-derive):
* the harness's per-pass number is "apply -> the light engine's mailboxes report idle"; `bench.barrier.poll` = 1
  per pass means the work finished inside the apply tick, and `bench.pass_wall_actual` is exactly one tick;
* `ThreadedLevelLightEngine.runUpdate()` calls `super.runLightUpdates()` unconditionally, so whatever we queued is
  absorbed inside the barrier's own pass. That is the hand-off cost, and it is why the notification fan-out (62%
  of `publish_direct` in our own drain) moved no wall number at all;
* the light engine's sorter-mailbox callbacks already run on the light thread, so an extra task there is a queue
  hop, not a thread wakeup;
* `piggybackPublish` (commit at `runUpdate` HEAD, triggered by one vanilla task) measured **worse**: per-pass
  minimum 0.48 -> 1.24 ms plus a 14.6 ms stall, because the trigger waits for vanilla's `tryScheduleUpdate` while
  the private drain's 250 us timer is prompt. Committing in the same pass is not the win; being prompt is;
* rejected (do not re-test): halo machinery, measurement artifact, our sky algorithm, per-job pipeline handoff,
  notification fan-out, the coalescing delay alone, worldgen core-only publishing (~4%, lower variance - kept as
  an opt-in switch), `runtimeHaloChunks=0`, and - from an earlier session - the heightmap-sky lever.

## Unresolved 2 (closed for now): no engine-side block-light divergence under a controlled world

Final state of this investigation, after the probe was made able to attribute a reading:

* The edit scenario now **asserts its own emitting blocks** (`execute if block ... run say GLOW_*_YES`), because
  the world it runs on is whatever the previous benchmark left behind - the earlier readings compared runs whose
  emitter layout was not controlled.
* With that in place: **three consecutive runs read the reference value with all six glowstones present**, and the
  earlier group (1 vanilla + 3 ours) agreed 4/4 with a **cell-by-cell identical y=100 plane** (`dumpplane`).
* The one live divergence that was caught (ours `4d50732804a9a46d` while the adjacent vanilla read
  `61fb027e7d51ee31`) had a light pattern **equal to the reference's but shifted in x** while sky light was
  bit-identical - i.e. a different set/position of emitting blocks, which is what an uncontrolled world looks
  like, not what a light-engine bug looks like.
* Therefore: no evidence of an engine-side divergence, and the earlier "7 conforming / 6 divergent" tally is
  withdrawn as a measurement of the engine (the reference drifted because every run re-saves the world).
* Methodology to keep: adjacent pairs only (vanilla -> ours, or ours -> vanilla, same world), with the scenario's
  own block assertions, and the y-banded probe; never compare hashes across long run sequences or across worlds.
* Still true and worth re-checking if this ever reappears: the storage-mode write path must overwrite the engine's
  DataLayer **in place** (never swap the object), because the light thread keeps writing into the object it holds.

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

## Measurement results added this session (for the four-workload table, action C)

Per-pass minima from `lucistarlink-light-benchmark.jsonl` (walls in brackets):

| workload | ours, shipped default | ScalableLux |
|---|---|---|
| `sky_hole` | **0.651** median of 5 (4.30-4.90) | **0.593** median of 3 (2.15-3.05), plus 0.639/0.644/0.708 in earlier runs |
| `dense_chunk_patch` | 1.872 (9.49) | 2.312 / 2.868 (18.8 / 26.3) |
| `block_toggle_border` | 1.089 (5.25) | 1.794 / 2.386 (12.0 / 16.2) |
| `structure_cube` | 1.781 (8.40) | 3.774 / 3.792 (19.9 / 28.5) |

So we win the three heavy workloads on both metrics and lose `sky_hole` by ~10% on per-pass minimum (~1.5x on
wall). Reschedulings measured and rejected this session (all 5 reps, per-pass minimum median): prompt posting of
runtime publications 1.114; coalescing window 2 ms 1.049; synchronous wait for our own commit inside the tick
0.784; worldgen core-only publishing 0.756 (neutral, kept as an opt-in); direct install 0.599 (the only variant
that beat the default, blocked only by the switch discipline now that Unresolved 2 came out clean). Vanilla's own
per-pass minimum on `sky_hole` is ~1.0 ms.
