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

> Status: **M0 + M2 cross-region correctness (runtime path)**. The engine core is the hardened Lucis 2.0 design;
> cross-chunk light is now correct by construction (halo images + halo publication + per-region refresh). The
> performance work that can make LuciStarlink strictly better than either parent (heightmap sky columns for the
> `sky_hole` class) is M1, save/load hardening is M2b. See
> [docs/roadmap-and-provenance.md](docs/roadmap-and-provenance.md) for milestones and known limitations.

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
| `regionChunks` | 1 | owned region size in chunks per axis |
| `haloPublish` | **on** | publish the halo chunks' dirty sections so light computed across a border reaches the neighbouring chunk immediately |
| `runtimeHaloChunks` | **1** | halo for runtime jobs. `1` gives vanilla-equivalent chunk borders (light travels 15 blocks); `0` is faster but truncates cross-chunk light propagation |
| `maxCachedRegions` | 128 | upper bound on cached region images |
| `maxCachedRegionMegabytes` | **256** | memory budget for those images - each one is megabytes, so an entry count alone is not a bound |
| `maxBatchChunks` | 64 | region jobs submitted per tick |
| `experimental*` | on | the validated redesign paths (section fast path, sky seed skip, dense incremental, inline runtime, runtime adoption) |
| `experimentalBoundaryDeltas` | off | cross-region boundary continuation prototype; known to oscillate for roof-crossing batches |

With `verboseLogging=true` the mod logs a memory telemetry line every 30 s (region cache bytes/entries,
coalescing entries, queued changes/regions, pending batches, commits, scheduled regions), so growth of those
structures is visible rather than guessed.

## Building

Needs **JDK 21** — the project pins the Gradle 8.8 wrapper, which does not run on newer JDKs:

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
