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

Numbers from this project's own harness (`run-benchmark-scalablelux`): each engine runs in the same session,
interleaved round by round, on the same world, with a fixed seed and a settled world before each measurement.
Protocol: `-Dlucistarlink.benchmark.prepareRing=8 -Dlucistarlink.benchmark.quiesceSettleMs=1000
-Dlucistarlink.benchmark.globalEngineBarrier=false`, 3 measured passes per run, 3 interleaved rounds, medians.

**Engine metric** — `minPassNanos`, the engine's own work for one pass. It is measured from the pass start to the
moment the *engine* reports it has finished (`waitCompleteNanos` in the harness), so it is dominated by the engine
computing the light, not by the harness's own block loop.

| workload (what a player calls it) | **LuciStarlink 2.0** | ScalableLux | 1.x (Lucis line) |
|---|---|---|---|
| `block_toggle_border` — placing/breaking fast along a chunk border | 5.01 ms | 4.16 ms | **0.76 ms** |
| `structure_cube` — building a solid structure | **5.11 ms** | 5.36 ms | 3.37 ms |
| `dense_chunk_patch` — large-area edits | **3.48 ms** | 5.74 ms | 2.85 ms |
| `sky_hole` — a single small edit | **1.17 ms** | 1.18 ms | 3.85 ms |

**Player metric** — `bench.pass_wall_actual`, the wall time of a whole pass, which *does* include the tick crossings
a player waits through (median / best round):

| workload | LuciStarlink 2.0 | ScalableLux | 1.x |
|---|---|---|---|
| border | 48 / 47 ms | 49 / 49 ms | 102 / 83 ms |
| structure | 47 / 47 ms | 50 / 48 ms | 83 / 48 ms |
| dense | 49 / 48 ms | 49 / 49 ms | 80 / 57 ms |
| sky_hole | 49 / 49 ms | 50 / 50 ms | 70 / 48 ms |

The window was **loaded** (CPU average 44.3%, peak 60.1% — the machine this project measures on runs other work by
design). Same-session interleaving is what makes the comparison valid; absolute values are the player-facing picture,
not a quiet-room number. The same build was measured in a lighter window (CPU average 40.0%) at border 4.30,
structure 4.44, dense 3.75, sky_hole 0.93 — that spread is the honest resolution of this machine, so read the table as
"within roughly ±30%", not as three significant figures.

**The honest reading:**

- **The player axis is at the one-tick floor for both LuciStarlink and ScalableLux** — every reading is 47–50 ms,

Since 2.0.10 the lane's nibble indexing is correct: before that, enabling the image lane made a placed patch read
dark in the world (the lane adopted and packed one section too low), which the four-cell fingerprints could not see
because the base queue also processes those chunks. The gate now covers that configuration.
  which is 20 TPS. That means both engines finish inside the same tick and the metric cannot separate them; "faster"
  on this axis is not claimable by anyone here. 1.x is genuinely behind on it (64–117 ms) because it crosses into a
  second tick.
- **On the engine axis the 1.x line is ahead on three of four cells**, and the reason is architectural rather than a
  tuning gap: 1.x's world light storage *is* its flat image, so a change writes a byte and pushes a queue, while this
  engine must derive material from the world and write back into ScalableLux nibbles. Measured decomposition of the
  four-cell gap: **roughly 1.2× of it is this project's own layer (its capture, settle, pack and publish work) and the
  rest is the base engine itself** — on the same workload ScalableLux reads 4028 µs of post-apply engine time against
  1.x's 2331 µs before this layer is even involved.
- **`sky_hole` is the one cell this engine wins** (0.72 vs 0.86 ms, 1.19×), because a 25-change burst is small enough
  that the deferred settle costs less than a seeded flood fill.
- **Not speed, but correctness:** the light this engine produces is bit-identical to vanilla and to ScalableLux. Every
  run prints a per-cell fingerprint over the measurement box (`sky=905931078dfc5ace`), and since 2026-09-27 all four
  workloads are fingerprinted rather than only `structure_cube`. The 1.x line's is `641356fc41163add`, identical to
  neither. A real correctness defect was found and fixed this way in 2.0.9 (a bulk edit used to leave the sky light of
  the affected cells at its old value — sky shining through a roof just built; see the changelog).
- **Retractions that belong with any quote of older numbers.** The 2.0.5-era table (border 1.51 ms "3× faster than
  ScalableLux", structure 2.99 and dense 1.11 "win vs both") was measured **before the harness protocol fixes** — the
  global engine barrier and the engine-queue race that made one and the same task jump between 1 and 29 ticks. Those
  numbers are not comparable to the table above and are withdrawn; the current table supersedes them. An earlier
  figure of 0.32 ms for the border cell was measured while that cell's block-light propagation was not being done at
  all (the defect that also made placed torches light only their own cell) and was already withdrawn.
- **The `structure_cube` caveat, which belongs in any claim about that cell:** a large share of that reading is
  Minecraft's own `Level.setBlockState` (state write, heightmap, dirty marking, vanilla's synchronous light hook) —
  hundreds of nanoseconds per change for *every* engine measured, vanilla included. It cannot produce a large victory
  for anybody.


## Compatibility

- Replaces the light engine, so it is **mutually exclusive** with Starlight, ScalableLux, Lucis and anything else
  that owns the vanilla lighting pipeline. The mod metadata declares ScalableLux incompatible.
- **Sable** is supported: per-plot light engines are deferred to Sable instead of fighting them.
- Works alongside worldgen optimizers (C2ME, Generator Accelerator, Fast Noise); the benchmark harness can measure
  those combinations directly.
- The engine switches are consumed **only** inside paths guarded by "am I a `ServerLevel` and is this the
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
