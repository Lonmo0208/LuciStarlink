# LuciStarlink

**LuciStarlink** is a server-side Minecraft light engine for Minecraft 1.21.1 / NeoForge that fuses the
three strongest public designs in this space into one engine.

| Source | What LuciStarlink takes from it |
|---|---|
| **Lucis** (DenisMasterHerobrine, LGPLv3) — region-owned engine | The engine core: owned region light images, region coalescing + worker jobs, homogeneity certificates for bulk material extraction, uniform-source skylight seed skipping, adoption-backed region init, exact two-queue incremental skylight repair, change-class routing, dirty-section-only publication, the differential test suite and the benchmark harness |
| **ScalableLux / Starlight** (Spottedleaf, ishland, RelativityMC, LGPLv3) | Ideas: byte-budgeted resource accounting and leak hardening (applied in M0), heightmap/column-driven sky propagation (M1) and task-level parallelism inside a job (M3) |
| **LuciStarlink hardening** | Region cache bounded by bytes, leaked bulk-write scopes auto-reaped, full-relight queue respecting its record budget, runtime halo knob so cross-chunk light is not truncated, verbose memory telemetry, guarded shutdown paths |

It is **not** a scheduler over vanilla light tasks and **not** a Starlight fork: it owns the computation
(material image + propagation) per region and publishes only dirty sections back into the vanilla engine.

> Status: **1.0.0 — server-side engine, correct across region borders, memory-bounded, saves safe.**
> Benchmarked against **vanilla** and **ScalableLux** in same-session interleaved runs (the engines alternate
> round by round, ≥5 reps each; statistic = the median of per-pass minima over a run; every comparison carries an
> exact two-sided Mann-Whitney p). All numbers use the settled-world protocol
> (`-Dlucistarlink.benchmark.prepareRing=3 -Dlucistarlink.benchmark.quiesceSettleMs=1000`); see
> [docs/TASK-PERF-SKY.md](docs/TASK-PERF-SKY.md) §7.8 for why older numbers were not comparable.
>
> **Two independent interleaved sessions are shown as ranges** — every *direction* below was replicated in both,
> with perfect separation (all five runs of one engine on one side of all five of the other), but magnitudes vary
> up to ~40% between sessions, so read the range rather than a single value:
>
> | workload | vanilla | LuciStarlink | ScalableLux | vs vanilla | vs ScalableLux |
> |---|---|---|---|---|---|
> | `block_toggle_border` | 3.44 | **0.70–0.81** | 1.64–1.75 | **4.3–4.9× faster** | **2.05–2.49× faster** (p=0.008) |
> | `structure_cube` | 3.69–4.33 | **1.74–2.43** | 3.04–3.19 | **1.8–2.1× faster** | **1.31–1.77× faster** (p=0.008) |
> | `dense_chunk_patch` | 3.02–3.16 | **1.80–2.45** | 1.42–1.49 | **1.23–1.76× faster** | 1.21–1.73× slower (p≤0.095) |
> | `sky_hole` | 0.76–0.82 | 0.71–0.82 | **0.36–0.44** | parity (p=0.69) | 1.87–2.0× slower (p≤0.016) |
>
> Wall time (the whole run rather than the best pass) tells the same story: 1.5–4.9× faster than vanilla and
> parity on `sky_hole`; 1.3–2.5× faster than ScalableLux on the two workloads that matter most, 16–73% behind on
> `dense_chunk_patch` and 1.9–2.2× behind on `sky_hole` — where we sit at vanilla level. ScalableLux's edge on
> that one workload is structural: it never hands sections to the light engine at all, which is what the V2
> storage mode ([docs/ARCH-V2-GLOBAL-STORAGE.md](docs/ARCH-V2-GLOBAL-STORAGE.md), plan in
> [docs/V2-PLAN.md](docs/V2-PLAN.md)) exists for.
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

## License and attribution

LGPLv3 (see [LICENSE](LICENSE)) — required, because LuciStarlink is a derived work of Lucis (LGPLv3) and takes
ideas from ScalableLux/Starlight (LGPLv3). See [NOTICE](NOTICE) for full attribution and the change list
relative to Lucis 2.0. Redistribution must keep these notices and the license.
