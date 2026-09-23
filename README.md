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
interleaved (the three engines alternate round by round), on the same world, with a fixed seed and a settled world
before each measurement. Medians of 3 rounds per side, after the 2.0.5 release.

**Engine metric** — `minPassNanos`, the engine's own work for one pass (the best measured pass, so tick-crossing
noise is excluded):

| workload (what a player calls it) | **LuciStarlink 2.0.5** | ScalableLux | 1.x (Lucis line) | verdict |
|---|---|---|---|---|
| `block_toggle_border` — placing/breaking fast along a chunk border | **1.51 ms** | 4.54 ms | **0.81 ms** | 3× faster than ScalableLux; 1.x still ahead |
| `structure_cube` — building a solid structure | **2.99 ms** | 4.78 ms | 3.22 ms | **win** vs both |
| `dense_chunk_patch` — large-area edits | **1.11 ms** | 3.77 ms | 2.39 ms | **win** vs both |
| `sky_hole` — a single small edit | 1.06 ms | 0.98 ms | 0.81 ms | within noise of both |

For scale, **vanilla** (no light mod) reads roughly 10–11 ms on the three heavy workloads and 1.0 ms on `sky_hole` in
the same harness — measured in a separate session, so quote it as an order of magnitude rather than as a same-session
ratio.

**Player metric** — `bench.pass_wall_actual`, the wall time of a whole pass, which *does* include the tick crossings
a player waits through (median / best round):

| workload | LuciStarlink 2.0.5 | ScalableLux | 1.x |
|---|---|---|---|
| border | 50 / 48 ms | 49 / 47 ms | 80 / 72 ms |
| structure | 50 / 48 ms | 50 / 50 ms | 60 / 53 ms |
| dense | 50 / 48 ms | 50 / 48 ms | 68 / 48 ms |
| sky_hole | **49 / 48 ms** | 50 / 50 ms | 71 / 63 ms |

The window these numbers come from was **loaded** (CPU average 35.9%, peak 68.1% — the machine this project measures
on runs other work by design). Same-session interleaving is what makes the comparison valid; the absolute values are
the player-facing picture, not a quiet-room number.

**The honest reading:**

- **Two wins, one 3× improvement, one tie.** `structure_cube` and `dense_chunk_patch` are won against both
  predecessors; `block_toggle_border` went from 22% *behind* ScalableLux (2.0.4, 5.00 vs 4.18) to **3× ahead of it**
  (1.51 vs 4.54) — the 1.x line's 0.81 there is its region-batched architecture doing exactly the small synchronous
  work it was built for, and that cell is the one place a predecessor beats this engine on compute.
- **`sky_hole` is a tie between all three** (0.81–1.06 ms) and the ordering moves with machine load; nothing is
  claimed on it at this round count.
- **The player axis is the one-tick floor** (48–50 ms) for this engine and for ScalableLux on all four; 1.x sits
  10–30 ms behind on three of four.
- **Not speed, but correctness:** the light this engine produces is bit-identical to vanilla and to ScalableLux on the
  measurement box — every run prints a fingerprint (`sky=905931078dfc5ace`). The 1.x line's is
  `641356fc41163add`, identical to neither.
- **Two corrections that belong with any quote of the numbers above.** Before 2.0.4 the `block_toggle_border` figure
  was 0.32 ms and the headline was "12× faster than ScalableLux": that was measured while the cell's block-light
  propagation was not being done at all, the same defect that made placed torches light only their own cell, and it is
  no longer quoted. And the 2.0.4 table's 5.00 ms for the same cell was the cost of doing the work with the wrong
  settle rule; 2.0.5 fixed that rule (see `docs/BUG-EMITTER-BLOCK-LIGHT.md` and the 2.0.5 changelog entry).
- **The `structure_cube` caveat, which belongs in any claim about that cell:** roughly 90% of that reading is
  Minecraft's own `Level.setBlockState` (state write, heightmap, dirty marking, vanilla's synchronous light hook) —
  600–775 ns per change for *every* engine measured, vanilla included. It cannot produce a large victory for anybody.

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
