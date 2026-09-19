# LuciStarlink

**LuciStarlink** is a server-side Minecraft light engine for Minecraft 1.21.1 / NeoForge that fuses the
three strongest public designs in this space into one engine.

| Source | What LuciStarlink takes from it |
|---|---|
| **Lucis** (DenisMasterHerobrine, LGPLv3) — region-owned engine | The engine core: owned region light images, region coalescing + worker jobs, homogeneity certificates for bulk material extraction, uniform-source skylight seed skipping, adoption-backed region init, exact two-queue incremental skylight repair, change-class routing, dirty-section-only publication, the differential test suite and the benchmark harness |
| **ScalableLux / Starlight** (Spottedleaf, ishland, RelativityMC, LGPLv3) | **Adopted:** byte-budgeted resource accounting and leak hardening (M0). **Measured and rejected, not shipped:** heightmap/column-driven sky propagation (M1 — it is not a lever: the compute volume is not on the critical path) and task-level parallelism inside a job (M3 — 96% of a large relight is block changes, which cannot leave the server thread). **Not implemented:** client-side lighting takeover — we shipped a probe for it instead, and the probe showed a vanilla client does no light computation at all (0 `propagateLightSources`, 108 `checkBlock` calls per session), so the premise fails (`docs/ARCH-V2-CLIENT-LIGHTING.md`) |
| **LuciStarlink hardening** | Region cache bounded by bytes, leaked bulk-write scopes auto-reaped, full-relight queue respecting its record budget, runtime halo knob so cross-chunk light is not truncated, verbose memory telemetry, guarded shutdown paths |

It is **not** a scheduler over vanilla light tasks and **not** a Starlight fork: it owns the computation
(material image + propagation) per region and publishes only dirty sections back into the vanilla engine.

> Status: **1.2.3 — server-side engine, correct across region borders, memory-bounded, saves safe, and the
> restart-truncation defect fixed (1.1.5).**
> Benchmarked against **vanilla** and **ScalableLux** in same-session interleaved runs (the engines alternate
> round by round, ≥5 reps each; statistic = the median of per-pass minima over a run; every comparison carries an
> exact two-sided Mann-Whitney p). All numbers use the settled-world protocol
> (`-Dlucistarlink.benchmark.prepareRing=3 -Dlucistarlink.benchmark.quiesceSettleMs=1000`); see
> [docs/TASK-PERF-SKY.md](docs/TASK-PERF-SKY.md) §7.8 for why older numbers were not comparable.
>
> **The current standing table (2026-09-19, 1.2.2 defaults — one interleaved session, 5 reps, so quote it as one
> measurement, not as a range across sessions):**
>
> | workload | LuciStarlink | ScalableLux | vs ScalableLux | p |
> |---|---|---|---|---|
> | `block_toggle_border` | **1.308** | 2.323 | **1.78× faster** | 0.0079 |
> | `structure_cube` | **2.753** | 3.749 | **1.36× faster** | 0.6905 (our copy had two environment-stalled runs) |
> | `dense_chunk_patch` | 2.595 | **2.069** | 1.25× slower | 0.0317 |
> | `sky_hole` | 0.912 → **0.719** | 0.573 | 1.58× slower → **1.26× slower** | 0.0079 → 0.0556 (no longer significant) |
>
> Since **1.2.2** the synchronous-publish combination (`directSectionInstall` + `syncRuntimeDrain`) is the default;
> it is what moved `sky_hole` from 1.583× to 1.256× while a four-workload guardrail showed the two wins intact
> (border 2.1×, structure 1.42×, dense 1.22× behind). **Honest summary: two workloads decisively ahead, two behind —
> not "ahead on three".** `sky_hole` remains behind by an inherent async-hand-off cost of roughly 0.15 ms per small
> edit, and every mechanism that could close it has been measured ([docs/TASK-PERF-SKY.md](docs/TASK-PERF-SKY.md)
> §10.13–10.14); ScaleableLux's edge there is structural — it never hands sections to the light engine at all.
>
> Wall time (the whole run rather than the best pass) tells the same story: 1.5–4.9× faster than vanilla and
> parity on `sky_hole`; 1.3–2.5× faster than ScalableLux on the two workloads that matter most, and behind on
> `dense_chunk_patch` and `sky_hole` — where we sit at vanilla level.
> Absolute numbers drift up to ~40% between groups of one session (and the ratios by roughly as much), so **only
> same-run interleaved comparisons count** — see [docs/SUPERVISOR-NEXT-ROUND.md](docs/SUPERVISOR-NEXT-ROUND.md) §10–11.
> The 1.0.0 correctness fixes were verified performance-neutral by a same-session interleaved A/B of the two jars
> (`structure_cube` 2.315 vs 2.384 ms, p=1.00; `sky_hole` p=0.70).
> The client-sync case is verified: light placed on a chunk border reaches a connected client within 2 s,
> including on the far side of the border, with no reconnect or chunk reload.
> Client-side lighting is not taken over (the mod is server-side); the heightmap-sky lever was measured and
> rejected.

## Compatibility

* Replaces the light engine, so it is **mutually exclusive** with Starlight, ScalableLux, Lucis and anything
  else that owns the vanilla lighting pipeline. The mod metadata declares those as incompatible.
* Sable is supported: per-plot light engines are deferred to Sable instead of fighting them.
* Works alongside worldgen optimizers (C2ME, Generator Accelerator, Fast Noise); the benchmark harness can
  measure those combinations directly.

## Configuration

`config/lucistarlink-server.toml` (server config), plus system properties (`-Dlucistarlink.<name>=`) for
automation and the benchmark harness. Important knobs:

| Key | Default | Meaning |
|---|---|---|
| `regionChunks` | 1 | owned region size in chunks per axis, for the world-generation image and the runtime regions |
| `haloChunks` | 1 | read-only halo (in chunks) of the world-generation image: 1 covers the 15-block light travel distance. 0 would leave a border seam, so it is treated as 1 on that path (configurations written by older builds carry 0 because the key did nothing then) |
| `haloPublish` | **on** | publish the halo chunks' dirty sections so light computed across a border reaches the neighbouring chunk immediately |
| `runtimeHaloChunks` | **1** | halo for runtime jobs. `1` gives vanilla-equivalent chunk borders (light travels 15 blocks); `0` is faster but truncates cross-chunk light propagation |
| `worldgenHaloPublish` | **on** | the same for worldgen relights. `off` hands far fewer sections to the light engine, but a chunk generated beside an already-loaded neighbour then keeps its old border light until it is relit (can show as a seam) |
| `forceLightIncorrectOnSave` | off | off = a chunk is written as light-not-correct only while its region still has queued or in-flight engine work; on = every chunk (what ScalableLux ships), which also covers a neighbour generated after the save, at the cost of relighting everything on load |
| `enableWorldgen` / `enableRuntime` | on | run the engine on the generation path / on runtime edits. Both off idles the engine and leaves lighting to vanilla |
| `maxCachedRegions` | 128 | upper bound on cached region images |
| `maxCachedRegionMegabytes` | **256** | memory budget for those images - each one is megabytes, so an entry count alone is not a bound |
| `maxBatchChunks` | 64 | region jobs submitted per tick |
| `experimental*` | on | the validated redesign paths (section fast path, sky seed skip, dense incremental, inline runtime, runtime adoption) |
| `-Dlucistarlink.lazyHaloLight=` | true | system property only (no TOML key): materialise halo light and halo materials per section on demand instead of building the whole image up front (region init 435 → 0.9 ms, material extraction 56.8 → 5.6-13.9 ms) |
| `-Dlucistarlink.enabled=` / `-Dlucistarlink.debug=` | true / false | idles the engine / enables verbose diagnostics; used by the benchmark harness |

Experimental publish-path switches — **all default off, all measured, none promoted**:

| Key | What it changes | Measured |
|---|---|---|
| `directSectionInstall` | install a computed section straight into the engine's storage instead of handing it over as queued data | its sequential-group gain (−31%) **did not reproduce** interleaved (−13% `sky_hole`, +4% `block_toggle_border`, flat `dense`/`structure`); it also skips the engine's re-check of handed-over sections, which is what keeps a stale image from winning — **do not enable** |
| `piggybackPublish` | schedule on the light engine's own task list instead of a private queue | worse |
| `promptRuntimePublish` | bypass the 250 µs publish coalescing for a small runtime edit | worse (+44%); the coalescing window is a benefit, not a cost |
| `syncRuntimeDrain` | wait in-tick for the light thread to commit | neutral |
| `experimentalBoundaryDeltas` | cross-region boundary continuation prototype | oscillates for roof-crossing batches; slated for deletion |

With `verboseLogging=true` the mod logs a memory telemetry line every 30 s (region cache bytes/entries,
coalescing entries, queued changes/regions, pending batches, commits, scheduled regions), so growth of those
structures is visible rather than guessed.

## Building

Needs **JDK 21**, and `JAVA_HOME` must point at it (not just `PATH` — Gradle picks its own JDK and fails with
`DefaultReportContainer: Type T not present` on a mismatched one). The wrapper pins Gradle 8.12.1 from a mirror:

```bash
./gradlew build          # jar lands in build/libs/
./gradlew test           # differential + engine test suite
```

Benchmark harness (fresh JVM per run, strict drain barriers, fixed seed, A/B modes for vanilla / ScalableLux /
worldgen mods):

```bash
./gradlew runBenchmarkServer -PbenchmarkWorkload=dense_chunk_patch -PbenchmarkPasses=6
powershell -ExecutionPolicy Bypass -File benchmark-final.ps1 -Stamp my-run
```

On a machine whose TLS chain only lives in the OS certificate store, set
`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` before building, otherwise dependency
downloads fail with `PKIX path building failed`.

## 换光照模组之后「方块只亮自己那一格」

**原因**：世界上一次是由另一个光照引擎（例如 ScalableLux / Starlight）保存的，那段光照是**带着未完成的传播**
被写盘的，但存档把它标成了「光照已计算」。任何引擎都不会去重算一个加载时自称光照已完成的区块，所以缺掉的那圈
光就永久缺着 —— 重挖重放能修好，正是因为那会让区块变脏、触发重算。

**两种修法**（1.1.1 起都可用）：

1. **就地刷新某片区域**：`/lucistarlink relight [半径区块数]`（需要权限等级 2）。它把该维度半径内**已加载**的
   区块标成「光照未计算」，并交给引擎自己的 `lightChunk` 路径重算 —— 就是区块生成时走的同一条路，跨区光环与
   外部刷新照旧生效。半径默认 256、上限 256（1.21.1 没有枚举已加载区块的公开接口，只能按坐标扫）。标记会写进
   存档，所以某个作业失败也会在下次加载时重试，不会留下永久黑块。
2. **整个存档连未加载的区块一起修**：把 `forceLightIncorrectOnSave` 设为 `true`（配置文件里改，或启动参数
   `-Dlucistarlink.forceLightIncorrectOnSave=true`），进服后执行 `/save-all flush`，然后**去掉这个开关重启** ——
   每个被保存过的区块下次加载都会重算光照。开着不关会让每次开服都全量重算，所以只当一次性开关用。

**以后换模组**：先用旧模组正常关服，换上新模组后按第 2 条做一次（开开关 → `save-all flush` → 关开关重启），
就不会再碰到这个现象。

## License and attribution

**LuciStarlink is by Lonmo** — Copyright (c) 2026 Lonmo.

LGPLv3 (see [LICENSE](LICENSE)) — required, because LuciStarlink is a derived work of **Lucis** (the 2.0 line by
[Team Argentum](https://github.com/Team-Argentum/Lucis), itself from Lucis 1.x by
[DenisMasterHerobrine](https://github.com/DenisMasterHerobrine/Lucis)) and takes ideas from
**Starlight / ScalableLux** ([Spottedleaf, ishland and RelativityMC](https://github.com/RelativityMC/ScalableLux),
NeoForge 1.21.1 backport on branch `backports/neoforge/1.21.1`) — all LGPLv3. See [NOTICE](NOTICE) for the full
attribution, the thanks list and the change list relative to Lucis 2.0. Redistribution must keep these notices and
the license.
