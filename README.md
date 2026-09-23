# LuciStarlink

**English** | [中文](README.zh-CN.md)

**LuciStarlink** is a server-side Minecraft light engine for **Minecraft 1.21.1 / NeoForge**.

It is built on **ScalableLux** (Spottedleaf's stateless light engine, by Spottedleaf, ishland and RelativityMC),
and on top of that base it carries **an update loop written and measured here**: an edit is propagated inside the
call that made it, buffered per chunk, and a bulk sky change is settled by an *exact windowed recompute* instead of
a seeded flood fill. The lineages it draws on are ScalableLux and, through the previously shipped 1.x line,
**Lucis** — plus the lessons this project's own benchmark harness produced, including the ones that said *don't*.

## What is ours and what is not

This distinction is the first thing to get right, so it is the first section.

| Layer | Whose code | Notes |
|---|---|---|
| Light storage, incremental propagation BFS, chunk/worldgen pipeline, vanilla interfaces, client publish path | **ScalableLux** (Spottedleaf, ishland, RelativityMC) — LGPL-3.0-only | The engine core. Packages (`ca.spottedleaf.starlight.*`) and the `scalablelux$` mixin prefixes are kept on purpose, so the origin of every file stays visible. Produced from the NeoForge 1.21.1 backport tree `0.3.0-alpha.0.8`. |
| **The update loop this release is judged by** | **LuciStarlink** | 21 new files plus 29 modified ScalableLux files (+1734/−457). The five mechanisms below. |
| Telemetry, profiler, in-game commands, Sable compatibility, config, save-guarantee work, the rig entrypoint | **LuciStarlink** | Carried over in spirit from the 1.x line (see below). |
| The 1.x line's own engine (region-owned light images) | **not in here at all** | Lucis's design needs a full region image and its own material extraction; porting it was measured and is not feasible on this base (`docs/NEW-ENGINE-TEARDOWN.md`, `docs/PORT-LUCIS-IDEAS.md`). Zero lines of it are in this build. |

**So: the engine core is ScalableLux's code, and the part that decides the numbers below is ours.** Calling this "a
fully self-developed engine" would be wrong; calling it "ScalableLux with a patch" would be wrong the other way —
the M0–M3 milestones *were* the latter, and stage R replaced the update path.

### The five mechanisms that are ours (all in `docs/NEW-ENGINE-TEARDOWN.md`)

1. **Inline edit lane** — propagate and install an edit inside the `setBlockState` call that made it, instead of
   handing it to the light thread. It never touches the engine queue, so none of the ~4.3 ms completion-path
   turnaround that killed twelve queue-side attempts is paid.
2. **Per-chunk edit buffering** — one engine call handles a whole burst instead of one call per block; each flush
   pays a fixed setup + seed + drain cost.
3. **A consolidated decrease drain — implemented, measured, then removed for correctness (2.0.4).** It seeded each
   touched chunk and walked the engine's decrease queue once for the whole burst, which looked like the cheapest way
   to handle many small bursts. It ran that drain after the seeding step had already torn its caches down, so the
   drain could not read a neighbour or write a cell: nothing propagated, and placed light sources lit only their own
   cell (`docs/BUG-EMITTER-BLOCK-LIGHT.md`, root cause four). The batch path now uses the base's per-chunk
   setup → seed → drain → publish → destroy order. Its measured "win" on `block_toggle_border` was the work it was
   not doing — see the performance note above.
4. **Windowed sky settle** — a bulk sky change is settled by recomputing only the y window an edit can reach
   (`±16`, exact because a light level is 0..15 and each propagation step costs one), with the sweep decided by a
   single light read at the window's top edge instead of a palette walk per column. Per settle: 8900–9700 µs
   whole-chunk against **213–281 µs** windowed, with the light identical.
5. **Hoisted inline guards and the block-light skip** — the thread/chunk/status checks are per *chunk*, not per
   change (apply phase 704 → **548 ns/change**); and a change that makes an already dark cell fully opaque provably
   cannot move block light, which is the shape of every bulk fill.

Both switches that turn those on are **on by default**; each can be turned off by hand
(`-Dscalablelux.ownEdit=false`, `-Dscalablelux.recomputeSky=false` — see Configuration). The third switch this list
used to carry (`scalablelux.batchDecrease`) is gone with the code it controlled.

## Performance

Numbers from this project's own harness (`run-benchmark-scalablelux`), which measures each engine in the same
session, interleaved, on the same world, with a fixed seed and a settled world before each measurement.

> **This table is superseded as of 2.0.4, and the reason matters more than the numbers.** The `block_toggle_border`
> figure below was measured while that workload's block-light propagation was **not being done at all** — a defect
> that also made placed light sources light only their own cell (`docs/BUG-EMITTER-BLOCK-LIGHT.md`). With the light
> actually computed, the same harness reads **5.03 ms** for that cell, i.e. this engine is currently *behind*
> ScalableLux's 4.01 ms there, and the "12× faster" claim that used to stand on it was an artefact. The other three
> workloads are unaffected in kind and remain wins on a first post-fix reading (`structure_cube` 2.79, `dense`
> 1.07, `sky_hole` 0.56 ms, one round, CPU 17.8%). **A multi-round, same-session re-measurement is required before
> any of this is quoted again**; the row-by-row table below is kept only as the pre-2.0.4 record.

**Engine metric** — `minPassNanos`, the engine's own work for one pass (the best measured pass, so tick-crossing
noise is excluded). Median of 3 rounds, one session, CPU average 29.9%:

| workload (what a player calls it) | LuciStarlink 2.0 (pre-2.0.4) | ScalableLux | 1.x (Lucis line) |
|---|---|---|---|
| `block_toggle_border` — placing/breaking fast along a chunk border | ~~0.32 ms~~ **5.03 ms** | 4.01 ms | 0.84 ms |
| `structure_cube` — building a solid structure | **2.51 ms** (2.79 ms post-fix) | 4.97 ms | 2.69 ms |
| `dense_chunk_patch` — large-area edits | **1.07 ms** (1.07 ms post-fix) | 3.65 ms | 1.96 ms |
| `sky_hole` — a single small edit | **0.69 ms** (0.56 ms post-fix) | 0.88 ms | 0.73 ms |

For scale, **vanilla** (no light mod) reads 10.41 / 10.81 / 10.17 / 1.03 ms on the same four workloads in the same
harness — measured in a separate session, so quote it as an order of magnitude rather than as a same-session ratio.

**Player metric** — `bench.pass_wall_actual`, the wall time of a whole pass, which *does* include the tick
crossings a player waits through. Median / best round, same session:

| workload | LuciStarlink 2.0 | ScalableLux | 1.x |
|---|---|---|---|
| border | **49 / 49 ms** | 50 / 49 ms | 101 / 85 ms |
| structure | **49 / 49 ms** | 49 / 49 ms | 68 / 55 ms |
| dense | **50 / 50 ms** | 50 / 47 ms | 73 / 65 ms |
| sky_hole | 50 / 50 ms | 50 / 50 ms | **49 / 27 ms** |

**The honest reading of those two tables:**

- **Against ScalableLux, as measured after the 2.0.4 correctness fix: two clear wins, one loss.** `structure_cube`
  2.79 vs 4.97, `dense_chunk_patch` 1.07 vs 3.65, `sky_hole` 0.56 vs 0.88 — and `block_toggle_border` **5.03 vs
  4.01, a loss** (the pre-2.0.4 0.32 there was the artefact described above). This is one round; the multi-round
  re-measurement is the next record to publish.
- **The player metric is level with ScalableLux** — 49–50 ms is one server tick, the floor of what any engine can
  deliver for an edit that has to be visible to the next tick, and both engines sit on it.
- **Against 1.x:** its window reads 0.84 / 2.69 / 1.96 / 0.73 ms, which we cannot compare cell by cell without a
  same-session run; what is directly comparable is that our light is bit-identical to vanilla's and 1.x's is not.
- **The `structure_cube` caveat, which belongs in any claim about that cell:** roughly 90% of that reading is
  Minecraft's own `Level.setBlockState` (state write, heightmap, dirty marking, vanilla's synchronous light hook) —
  600–775 ns per change for *every* engine measured, vanilla included. 1.x's 2.86 ms is ~2.6 ms of that floor plus
  ~0.26 ms of its own work. That cell cannot produce a large victory for anybody, this build included.
- **Not speed, but correctness:** the light this engine produces is **bit-identical to vanilla and to ScalableLux**
  on a 599,040-cell verification box — every run prints a fingerprint (`sky=905931078dfc5ace block=59e2252f732ce67b`).
  The 1.x line's fingerprint is `641356fc41163add`, i.e. it is not vanilla-identical. The windowed recompute
  reproduces the engine's own propagation rule instead of approximating it, and that is checked on every acceptance
  run.

**Re-verified on the shipped defaults** (no `-Dscalablelux.*` flag anywhere, only the harness protocol triple,
CPU average 8.3%): 0.331 / 2.698 / 1.029 / 0.772 ms, wall 48.5–50.5 ms per pass, canonical fingerprint. The default
build *is* the measured build.

## Compatibility

- Replaces the light engine, so it is **mutually exclusive** with Starlight, ScalableLux, Lucis and anything else
  that owns the vanilla lighting pipeline. The mod metadata declares ScalableLux incompatible.
- **Sable** is supported: per-plot light engines are deferred to Sable instead of fighting them.
- Works alongside worldgen optimizers (C2ME, Generator Accelerator, Fast Noise); the benchmark harness can measure
  those combinations directly.
- The three engine switches are consumed **only** inside paths guarded by "am I a `ServerLevel` and is this the
  server thread", so a client runs the unmodified base path — the client cannot see this change at all.

## Configuration

`config/lucistarlink.properties` is written on first run with every key in it. Each key can also be overridden by a
system property (`-Dscalablelux.<key>=`), which is how the measurement rig and admins set it without editing files.

| Key | Default | Meaning |
|---|---|---|
| `parallelism` | cores / 3 | light engine worker parallelism (the base's key) |
| `enabled` | `true` | install the light engine at all. `false` leaves every level on the vanilla engine — a compatibility escape hatch, and a way to A/B the engine on a real server. Needs a restart |
| `telemetrySeconds` | `30` | seconds between `SLTELEM` lines (queue depth, dirty positions, pooled propagators); `0` disables |
| `profile` | `false` | development profiler counters in the log |
| `batchLimit` | `1` | how many changed positions the per-change hook batches before taking the full scheduling path (`1` = base behaviour). Measured neutral, kept for A/B work |

Engine switches (system property only, because they are read once when the class loads):

| Property | Default | Meaning |
|---|---|---|
| `-Dscalablelux.ownEdit` | **`true`** | the inline edit lane (mechanisms 1 and 2). `=false` restores the base's queue path |
| `-Dscalablelux.recomputeSky` | **`true`** | the windowed sky settle (mechanism 4) |
| `-Dscalablelux.recomputeMinChanges` | `128` | burst size at which a sky settle switches to the recompute (the buffer flushes every 256 changes, so a larger threshold could never trigger) |
| `-Dscalablelux.inlineMinBurst` | `0` (off) | dispatch rule that sends small bursts back to the async path. **Closed by measurement**: it took the border cell from 0.43 ms to 4.03 ms |
| `-Dscalablelux.bulkRelight` | `false` | experimental: settle a large same-chunk burst with one full chunk relight |
| `-Dscalablelux.recomputeDebug` | `false` | per-settle phase timings on the log |

## Commands

`/lucistarlink` (permission level 2):

- `stats` — live engine state: queue depth, dirty positions, pooled propagator counts, the new-engine counters.
- `light <pos>` — the light level the engine holds at a position, per layer, with the block state it used.
- `relight [radius]` — mark loaded chunks in the radius light-incorrect and hand them to the engine's own
  `lightChunk` path. This is also the repair for a save written by a *different* light engine that recorded
  unfinished light as finished; the symptom is "blocks light only their own cell after switching mods".

## Building

Needs **JDK 21** (`JAVA_HOME` must point at it — Gradle picks its own JDK otherwise), a Loom-based build:

```bash
./gradlew build          # jar lands in build/libs/
```

The rig build (`./gradlew build -Pmod_id=lucistarlinkrig`) renames the mod id and the mixin config so the jar can be
loaded *next to* the 1.x harness mod for A/B measurement.

On a machine whose TLS chain only lives in the OS certificate store, set
`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` before building, otherwise dependency downloads
fail with `PKIX path building failed`.

CI (GitHub Actions) compiles the tree with JDK 21 and uploads nothing: release artifacts stay local for this
project.

## License and attribution

**LuciStarlink is by Lonmo** — Copyright (c) 2026 Lonmo.

**LGPL-3.0-only** (see [LICENSE](LICENSE)) — required, and unchanged from both lineages: this distribution is a
derivative work of **ScalableLux** (Spottedleaf, ishland, RelativityMC), and its predecessor line was a derivative
of **Lucis** (Team Argentum, DenisMasterHerobrine). See [NOTICE](NOTICE) for the full attribution and the change
list relative to the base. Redistribution must keep those notices and the license.
