# LuciStarlink handover: what is left, with evidence and acceptance criteria

Project: `E:\LuciStarlin\LuciStarlink` (mod id `lucistarlink`, MC 1.21.1 / NeoForge 21.1.234-235).

## 现在的状态（2026-09-21，新窗口从这里看起）

* 发布线 `master` = **1.2.10**（`dist/lucistarlink-1.21.1-1.2.10.jar`，md5 `36418d8500615bbff68263f1b35b5512`，
  tag `v1.2.10`）；git 已存在，每个改动都是可回退的提交。
* **三方口径的最后一项未判定已判定**：社区三方脚本（`E:\LuciStarlin\threeway.ps1`，未改）默认协议下
  `block_toggle_border` 单档 **12+12 轮读 1.2954**（U=113/31，**p=0.0173**，上游 Lucis 同样落后 1.3007）——
  所以逐档是 **1 胜 3 负**，加权 0.9339 由 `structure_cube` 的 0.819 带过去。**读 `docs/TASK-PERF-SKY.md` §18 / §18.5。**
* **V2 全局存储**已完整实现并**收线**在 `v2-storage` 分支：阶段 0/1/2 落地并逐格验证（storage-parity），
  性能中性（0.815 vs 0.819 ms，p=0.69），阶段 3 因前提错误而取消。默认仍是 `lightEngineMode = region`，
  storage 是一条可切换、经 parity 验证的替代路径。**读 `docs/V2-CONTINUATION.md`。**
* **dense 档差距（本次诊断）**：不在光照引擎，而在我们自己的改方块拦截路径（每方块 ~256 ns 的守卫与仪表开销）。
  已修（守卫只问一次、背压不看时钟、世界生成抑制抽成 `WorldgenWriteScope` 快路径、Sable 先查存在性）。
  **读 `docs/TASK-PERF-DENSE.md`** —— 里面也有「progress 总计不能当每轮归因」和「14~15.6 ms 调度饥饿特征」两条教训。
* 测试 **40 通过 / 0 失败**（1.2.10；`RuntimeUpdateQueue.enqueueFullRelight` 的记账泄漏窗口已修，
  未解决项 3 见 `docs/BUG-WORLDGEN-NATURAL-LIGHT.md`）。
* 本文件下面的正文来自更早的会话，**测量数字已被新的 ≥5 轮交错表取代**：读的时候以
  `docs/TASK-PERF-SKY.md`、`docs/TASK-PERF-DENSE.md` 和 `docs/verify-baseline.txt` 为准；
  仍然有效的部分是**构建/运行规则、测量机制（怎么测才有效）、以及未解决项的清单**。

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
   **Never copy a rig build into `dist/`**: the published artifact must say `incompatible`, otherwise a user who
   also installs ScalableLux gets only a warning and then a crash at boot (both replace the light engine). The
   release build is the plain `./gradlew build` with **no** `-PbenchmarkAllow*` switches; verify the two metadata
   lines before recording the md5.
4. **Interleaved measurement only for cross-engine claims**: run the two engines alternately, rep by rep
   (`mc-smoketest/ab-interleaved.sh <reps> [workloads...]`), because sequential groups are not comparable and
   the jsonl accumulates runs of many different configurations. Every run records `label` plus the publish-path
   switches, so a third party can regroup without trusting a summary.
5. **Lucis 2.0 cannot coexist with LuciStarlink** (both `@Redirect` the same `setBlock` in
   `LevelChunk.postProcessGeneration`), so Lucis is measured alone with its own harness (`-Dlucis.*` properties,
   `-Dlucistarlink.enabled=false` is not enough).
6. Cross-session wall times are NOT comparable (same config measured 11 ms and 50.6 ms in different sessions).
   Trust within-run stage metrics, and compare **per-pass minima** (`minPassNanos` in
   `lucistarlink-light-benchmark.jsonl`), median over >=5 repeats, for all engines alike.
7. **Kill stray servers before a rig run**: a leftover `java @user_jvm_args.txt` process holds `logs/` and the
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

## Unresolved 4: interop measured with C2ME (2026-09-19), Generator Accelerator still not run

C2ME `c2me-neoforge-mc1.21.1-0.4.0-alpha.0.122.jar` (user-provided) was put in `mc-smoketest/mods` (the border
scenario script does not wipe that directory, only the world and logs) and the same `gen` scenario was run twice on
the same seed: once without C2ME, once with it.

* **No crash, save and shutdown clean**; no mixin apply failure of ours (grep for `mixin apply failed` /
  `Critical injection` - empty), so our engine's hooks are in place under C2ME. C2ME's own log noise is limited to a
  `RuntimeDistCleaner` client-class warning and its sub-mod discovery lines.
* **Sky light bit-identical on all three fingerprints**: `strip` `ce0e43e726463e8d`, `strip_plus_beyond`
  `cf916cd9aa3dca8d`, `beyond` `b85c37ba14d2bd5c` - same values with and without C2ME.
* Block light matched on the pure-worldgen area (`beyond` `1eb24bed88e1b855`) and differed on the two labels that
  contain the scenario's own edits - which is the established non-oracle: block light is not a usable comparison,
  vanilla itself varies there (see Unresolved 2), and sky is the oracle.
* Caveat, stated plainly: this says the combination boots and produces identical light on a worldgen-heavy scenario;
  it is not a promise about C2ME's faster chunk pipeline in general, and C2ME's developer warns its module can break
  NeoForge chunk capabilities. Generator Accelerator is still not measured (no jar).


## Unresolved 5: cleanup

* `experimentalBoundaryDeltas` and `BorderDeltaSupport` are a superseded prototype (known to oscillate); dead
  code, default off. Safe to delete once nothing references them.
* `haloChunks` (worldgen halo) vs `runtimeHaloChunks` (runtime halo) are easy to confuse in config.
* Config switches added this session and their status: `worldgenHaloPublish` (default **true**, measured ~4%
  and lower variance when false, but leaves a seam until the neighbour is relit - see ARCH-V2 risk 7),
  `directSectionInstall` (default **false**: fastest measured configuration but blocked by Unresolved 2).

## Unresolved 6 (correctness, **FIXED 2026-09-21**): a cross-region light *decrease* used to not converge

* Fixed by community PR #2 (`fix/crossregion-clear`, `2a2bb9e`, merged as `65d0d32`): the regions a single bulk
  write drains now form one group and only one member is in flight at a time, so the next one starts on a
  baseline that already includes the previous one's staleness marks. The gate is an occupant marker that every
  early return releases, so it cannot deadlock, and the threshold (>= 64 changes on the drain) keeps
  single-edit workloads out of it entirely.
* Independently verified here with the reproducible probe (`mc-smoketest/ls-refill-probe.sh`): both air states
  in the glowstone -> air -> glowstone -> air cycle now read the all-dark canonical `b93a0c83ce3b6325`
  (`d80ac658736bb725` for the stacked 8x8 fill at y=125) where the pre-fix build read `3206f1df4c73167c`.
  The save/reload round trip reads the same canonical values, so nothing residual reaches the disk.
* The historical write-up - the reproduction, the per-cell planes, the diagnostic counters and the two repair
  attempts that were measured and reverted - stays in **`docs/BUG-CROSSREGION-LIGHT-DECREASE.md`** as the
  record of how the mechanism was found.

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

## Interleaved A/B (the number to quote): ours vs ScalableLux, alternating, n=5, nothing else running

`mc-smoketest/ab-interleaved.sh 5` - ours and ScalableLux alternate run by run, fresh world each run, same rig
and JVM flags; grouped by the `label` the jsonl now records.

| workload | ours per-pass min (median) | SL per-pass min (median) | ours wall | SL wall |
|---|---|---|---|---|
| `sky_hole` | 0.767 | **0.572** | 4.87 | 4.71 |
| `dense_chunk_patch` | **1.990** | 2.210 | 34.7 | **11.1** |
| `block_toggle_border` | **0.636** | 2.285 | **5.31** | 10.76 |
| `structure_cube` | **1.757** | 3.540 | **9.35** | 18.63 |

Settled by this run:
* **Three of four workloads win on both metrics** (per-pass minimum and wall). `sky_hole` loses on per-pass
  minimum by ~34% (0.767 vs 0.572) - bigger than the ~10% the sequential groups suggested, smaller than the
  ~74% an independent reader derived from the unlabelled jsonl.
* `dense_chunk_patch`'s wall loses (34.7 vs 11.1) even though its per-pass minimum wins: **one pass out of four
  occasionally costs ~30 ms** instead of ~2 ms. Reproduced when running alone (walls 34.3 / 10.0 / 36.3; the slow
  runs have a single 29-46 ms pass), so it is not an interleaving artifact. Open item, needs its own look - the
  candidate is a large batch's commit meeting concurrent worldgen publication. The agreed metric (per-pass
  minimum) is unaffected, which is exactly why it is the agreed metric.
* Sequential groups are not comparable: the same configuration measured 0.651-0.776 in one group and 0.767 in
  the interleaved group, and 9.5 vs 34.7 on dense's wall.

## Client case (the entry point the supervisor asked for)

The visual check - "a light source placed on a chunk border must show up on a connected client immediately" - is
prepared as a two-command flow:

1. **Server side** (the scenario places the glowstone itself and keeps running):
   `cd mc-smoketest && KEEP_SERVER_RUNNING=1 ./ls-border-edit.sh clientcase`
   It prints the connect address; `online-mode=false` is already set in the rig's `server.properties`.
   For the negative control (the test must be able to fail): add `-Dlucistarlink.haloPublish=false` - the
   neighbour must then stay dark until the chunk is reloaded.
2. **Client side** (in this project): `./gradlew runClientMultiplayer -PclientServer=127.0.0.1:25565`
   The `clientMultiplayer` run config adds `--quickPlayMultiplayer` with that address, so no menus are involved;
   `./gradlew createClientMultiplayerLaunchScript` produces a script to launch it outside Gradle.

Judgement: the light appears on both sides of the border immediately = pass; it needs a world reload = the client
sync path was broken (the risk of any publish route that skips the engine's section data and its notifications).

## Direct install ON vs OFF, interleaved (n=5 each, `ab-direct.sh`)

| workload | OFF per-pass min | ON per-pass min | OFF wall | ON wall |
|---|---|---|---|---|
| `sky_hole` | 1.069 | **0.929** | 4.89 | 5.08 |
| `dense_chunk_patch` | 2.096 | 2.101 | 26.25 | **14.57** |
| `block_toggle_border` | **0.914** | 0.948 | **5.03** | 6.19 |
| `structure_cube` | 1.877 | **1.819** | **8.91** | 9.26 |

Reading: ON is better only on `sky_hole` (-13%) and marginally on `structure_cube` (-3%), equal on `dense`, and
+4% worse on `block_toggle_border` - all within the drift this session's groups show. The earlier sequential
measurement of the same switch (-31% on `sky_hole`) does **not** survive interleaving, and ON's wall is worse on
two workloads. Conclusion: **do not promote `directSectionInstall`** - the gain does not justify skipping the
engine's re-check on the sections we hand over. It stays an experimental switch.

Also note for anyone reading absolute numbers: the *same* configuration measured per-pass minima of 0.651, 0.767
and 1.069 in three different groups of this session. Only comparisons **within** one interleaved run are usable,
so the ScalableLux comparison has to come from the interleaved ours-vs-SL run (0.767 vs 0.572 on `sky_hole`).
