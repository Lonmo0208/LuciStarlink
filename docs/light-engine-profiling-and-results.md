# Lux profiling report and benchmark results

All numbers below are from this repository's in-server harness (`LuxServerBenchmark`,
enabled with `-Dlucistarlink.benchmark=true -Dlucistarlink.debug=true`), on Minecraft 1.21.1 /
NeoForge 21.1.234, AMD Ryzen 3700X, fresh JVM per run (`gradlew runBenchmarkServer` /
`runBenchmarkVanillaServer`), fixed seed via `server.properties`, identical coordinates
and workload parameters. Every measured pass waits for a full drain barrier
(`waitForPendingTasks` futures for all touched chunks **and**
`LuxServices.controller().hasPendingRuntimeWork()`), so pending work is verified zero
at the measurement boundary (`runtimePendingBeforeMeasuredPass=0` in every result row).

## 1. Where the old strict path spent its time (per-stage metrics)

Stage metrics from `LUCIS_BENCH_METRIC` lines, `dense_chunk_patch` (2048 changes/pass,
6 passes), before the redesign:

| stage | total | per call | note |
|---|---:|---:|---|
| `lucistarlink.stage.runtime.full.extract` | 2.06 ms | 3 calls | full 16x16x384 material scan per relight |
| `lucistarlink.stage.runtime.full.sky` | 1.96 ms | 3 calls | full sky compute (fill + seed scan + spread) |
| `lucistarlink.stage.runtime.full.block` | 0.34 ms | 3 calls | |
| `lucistarlink.stage.runtime.full.publish` | 0.18 ms | 3 calls | |
| `lucistarlink.stage.runtime.incremental.*` | ~0.1-0.9 ms/job | 19 jobs | materialize/sky/block/publish |
| `lucistarlink.publish_batch.drain` | 1.97 ms | 14 calls | nibble packing + runLightUpdates + notifications |
| `lucistarlink.enqueue_block_change` | 0.73 ms | 5868 calls | server-thread capture cost |
| `bench.apply_pattern` | 5.37 ms | 3 measured passes | setBlock cost, identical for vanilla |

Worldgen (chunk prepare, 36 chunks, before vs after the redesign):

| stage | before | after | ratio |
|---|---:|---:|---:|
| extract | 258 ms | 142 ms | 1.8x |
| sky | 335 ms | 111 ms | 3.0x |
| block | 23 ms | 27 ms | ~1.0x |
| publish | 7 ms | 9 ms | ~1.0x |
| **total light** | **624 ms** | **288 ms** | **2.2x** |

Operation counts (after redesign, one `dense_chunk_patch` run, default config):
`lucistarlink.runtime.region.init=1`, `lucistarlink.runtime.region.adopted=1`,
`lucistarlink.runtime.region.incremental=4`, sections published 12 total (3/pass; the
pre-redesign full-relight path published 48/pass), `lucistarlink.sky.old_seed_candidates=224112`
vs `lucistarlink.sky.frontier.seeds=72` (uniform-section skip), queue pushes/pops and dequeues
available via the same counters (`lucistarlink.sky.spread.dequeues`, `lucistarlink.runtime.drain.*`).

Interpretation: after the redesign the per-pass cost of a 2048-change dense patch is
dominated by the *apply* phase (server-thread `setBlock`, ~1.8 ms, identical for
vanilla) plus removal-BFS work proportional to the relit volume; region-extract and
sky-scan costs are gone from the steady state.

## 2. End-to-end results (final configuration, defaults on)

Two fresh-JVM reps per cell; per-rep `nsPerChange` shown; speedup uses the min
(favourable to vanilla given its tick-quantization variance).

| workload | vanilla reps | lucistarlink reps | speedup |
|---|---|---|---:|
| block_toggle_border | 195,545 | 21,285 | **9.2x** |
| dense_chunk_patch | 8,376 / 9,327 | 1,949 / 1,931 | **4.3x** |
| roof_toggle | 9,450 | 1,926 | **4.9x** |
| block_toggle_sparse | 210,117 | 17,091 | **12.3x** |
| edge_toggle | 30,820 / 19,606 | 12,510 / 15,930 | **1.6-2.5x** |
| sky_hole | 98,197 / 201,959 | 103,176 / 99,868 | **~1.0x** |
| structure_cube 24^3 (bulk) | 4,341 | 1,067 | **4.1x** |

Geometric mean over the six-workload runtime set: **~3.9-4.5x** depending on
rep treatment; over the four representative heavy workloads: **~6.7x**.

Per-pass stability: Lux passes are 1-4 ms consistently; vanilla passes swing 2-37 ms
because the harness's idle server quantizes them to tick sleeps. Both engines' true
compute is therefore compared with in-tick accounting where it matters
(`bench.apply_pattern` vs `bench.wait_after_apply` plus stage metrics), and the
strict drain barrier guarantees no work is deferred past the measurement in either.

### Honest reading

* The 4-8x target is met on representative heavy workloads (dense patches, border
  edits, roof toggles, sparse first-touch, bulk structure placement) and the whole-set
  geometric mean *approaches* 4x.
* `edge_toggle` (96 edits across ~12 regions) is fixed-overhead bound for both engines;
  the remaining Lux overhead is job submission + publish drain (~1 ms/pass floor).
* `sky_hole` is parity: the repair volume (shaft + lateral cone) is identical for both
  engines; see architecture doc section 5 for the remaining lever.
* Earlier README claims (8-12x) were produced by a different harness state and could
  not be reproduced in strict mode before this redesign; treat this document as the
  reproducible baseline.

## 3. Re-producing these numbers

```powershell
# one cell
.\gradlew.bat runBenchmarkServer -PbenchmarkWorkload=dense_chunk_patch `
    -PbenchmarkPasses=6 -PbenchmarkWarmupPasses=2 `
    -PbenchmarkOutput=build/reports/lucistarlink-light-benchmark.jsonl

# full matrix script (vanilla + lucistarlink x N workloads x reps)
powershell -ExecutionPolicy Bypass -File benchmark-final.ps1 -Stamp <name>
```

Feature flags (all default to the validated state; `benchmark-*.ps1` pass them
explicitly for A/B): `lucistarlink.experimentalSectionFastPath`, `lucistarlink.experimentalSkySeedSkip`,
`lucistarlink.experimentalDenseIncremental`, `lucistarlink.experimentalInlineRuntime`,
`lucistarlink.experimentalRuntimeAdoption`, `lucistarlink.experimentalBoundaryDeltas` (off).
Set `lucistarlink.debug=true` (the benchmark runs set it) to emit the
`LUCIS_BENCH_METRIC`/`LUCIS_BENCH_COUNTER` lines used in section 1.
