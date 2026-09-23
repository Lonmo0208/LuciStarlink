# 2.0 architecture, ownership and acceptance

This file is the map: what code sits where, which part of it is ours, how a change is accepted, and which routes
were already walked and closed. The measurements behind every claim are in
[NEW-ENGINE-TEARDOWN.md](NEW-ENGINE-TEARDOWN.md) (R0–R5, sections 1–34); the older design material is in
[LS2-PLAN.md](LS2-PLAN.md) and [PORT-LUCIS-IDEAS.md](PORT-LUCIS-IDEAS.md).

## 1. The three layers, and whose each one is

```
                ┌───────────────────────────────────────────────────────────────┐
  ours          │ edit hook: LevelChunk.setBlockState → StarLightInterface      │
                │  · per-chunk buffering      (R3)                              │
                │  · inline propagation       (R2, ownEdit)                     │
                │  · windowed sky settle      (R5, recomputeSky)                │
                └───────────────────────────┬───────────────────────────────────┘
                                            │ installs into / reads from
                ┌───────────────────────────▼───────────────────────────────────┐
ScalableLux     │ the engine: SWMRNibbleArray storage, increase/decrease BFS,    │
                │ chunk load + worldgen pipeline, vanilla interfaces, publish    │
                └───────────────────────────┬───────────────────────────────────┘
                                            │ publishes
                ┌───────────────────────────▼───────────────────────────────────┐
Minecraft       │ the chunk's own light nibble arrays (saves, packets, other mods)│
                └───────────────────────────────────────────────────────────────┘
```

The bottom layer is non-negotiable: the light a player sees, the light a save contains and the light a client
receives all come from the chunk section's own nibble arrays, so every engine here reads and writes those. That is
the "truth" contract `NEW-ENGINE-TEARDOWN.md` §1 records as L1.

## 2. File census

Counted against the import commit `ec496c9` ("base: ScalableLux Fabric source"); regenerate with
`git diff --stat $(git rev-list --max-parents=0 HEAD) HEAD`.

**New, entirely ours (21 files)**

| Area | Files |
|---|---|
| Bootstrap | `common/LuciStarlinkEntrypoint.java`, `common/LuciStarlinkRigEntrypoint.java`, `mixin/LuciStarlinkMixinPlugin.java`, `src/main/resources/META-INF/neoforge.mods.toml` |
| Tools | `common/command/LuciStarlinkCommand.java`, `common/debug/LuxTelemetry.java`, `common/debug/LuxProfiler.java` |
| Compatibility | `common/compat/SableCompat.java`, `common/compat/SablePresence.java`, `mixin/compat/sable/SableServerLevelAccessor.java` |
| Engine experiments (probes, not on the hot path) | `common/light/own/OwnLightField.java`, `common/light/own/OwnFlatField.java`, `common/light/own/OwnSkySweepProbe.java`, `common/light/sched/EditScheduler.java` |
| Documentation | `NOTICE`, `README.md`, `README.zh-CN.md`, `CHANGELOG.md`, `docs/*` |

**ScalableLux files we modified (29 files, +1734/−457)**

| Area | Files | What we changed |
|---|---|---|
| Update loop | `light/StarLightInterface.java`, `light/StarLightEngine.java`, `light/SkyStarLightEngine.java`, `light/BlockStarLightEngine.java` | the five mechanisms; the windowed settle lives in `SkyStarLightEngine.settleSkyWindow` |
| Storage | `light/SWMRNibbleArray.java` | identity-preserving publish (`updateVisible` no longer sends a nibble whose bytes did not change) |
| Block states | `common/blockstate/ExtendedAbstractBlockState.java`, `mixin/common/blockstate/BlockStateBaseMixin.java` | cached opacity (0..15, or "not cached") so the propagation loop does not rebuild a palette lookup per neighbour |
| Save guarantees | `common/util/SaveUtil.java`, `mixin/common/world/SerializableChunkDataMixin.java`, `mixin/common/world/ServerWorldMixin.java`, `mixin/common/lightengine/ChunkMapMixin.java` | precise per-chunk "light incorrect" marking, the restart-truncation fix, and the worldgen write path |
| Client | `mixin/client/multiplayer/ClientPacketListenerMixin.java`, `mixin/client/world/ClientLevelMixin.java`, `light/vanillainterface/*` | no client-side light takeover; the client keeps the base path |
| Config | `common/config/Config.java` | `config/lucistarlink.properties` plus `-Dscalablelux.*` overrides |
| Build | `build.gradle`, `settings.gradle`, `gradle.properties`, `.github/workflows/build.yml`, `lucistarlink.mixins.json`, `scalablelux.accesswidener` | Loom build, NeoForge 1.21.1, identity rename, CI |

The 1.x line's engine is **not** here: Lucis's design needs a region-owned light image and its own material
extraction, and both were measured to be unaffordable on this base (§43 of the teardown doc and
`PORT-LUCIS-IDEAS.md`).

## 3. What the 1.x line actually contributed

Not code — a method and a set of measurements:

| Ported idea | Outcome |
|---|---|
| Batching the propagation path (1.x's `queueTask` batching) | **Closed by measurement.** Batching works (`queueTask` 17924 → 204 calls) and moves no metric. The cost is the per-change hook (666 ns, 31% of a pass), not propagation. |
| Halving the client light traffic (1.x 1.2.5, −51%) | **Closed by measurement, and it does not apply here.** The base's send path is already per-layer, one packet per chunk per tick, zero payload for empty layers — only ~1% idle publishes existed, and they are now skipped. |
| Telemetry + commands (`stats` / `light` / `relight`) | **Built and verified** in a real client session. |
| Save guarantees (restart truncation, cross-region decrease) | **Audited**, with the two real defects of the 1.x line carried as fixes: a save marks exactly the chunks whose light is unsettled, and `relight` exists to repair a save written by another engine. |
| The rig and acceptance protocol itself | **Ported.** Every number in the README comes out of it, including `prepareRing` / `quiesceSettleMs` / the box-scoped `globalEngineBarrier` measurement-scope fix. |
| The region-owned light image engine | **Not portable** (needs the image; the extraction alone measures 185 µs per section against a ~34 µs budget). |

## 4. How a change is accepted

Nothing is promoted on a single number. An engine change is accepted only when **all four** hold:

1. **Player metric** (`bench.pass_wall_actual`) — not worse, ideally better; this is what a player waits through.
2. **Engine metric** (`minPassNanos`) — not worse; this is where mechanism quality shows.
3. **Correctness** — the fingerprint of the 599,040-cell box must stay `sky=905931078dfc5ace block=59e2252f732ce67b`,
   i.e. identical to vanilla and to ScalableLux; a change that moves it is rejected even if it is faster.
4. **No hang** — all four phases of a measured pass complete.

**Since 2.0.2 the correctness gate needs a light source in it, and that is not optional.** The first defect reported
from play (`docs/BUG-EMITTER-BLOCK-LIGHT.md`) was an opaque emitter — glowstone, sea lantern, redstone lamp — whose
own cell was never lit, because the only gated workload was `structure_cube`, whose changes are all non-emitters:
273 block-light cells out of 599,040, i.e. the gate could not see a light source at all. Three things are required
now:

- at least one measured workload contains emitters, and its **block-light** fingerprint is compared too (not just
  sky);
- the **place → save → reload** check passes: a placed glowstone must read `block=15` after a restart (the script is
  the two-boot datapack protocol recorded in the bug document);
- a placed emitter must read `block=15` in the same session, with the tick hook as its settle point and no dependency
  on an unrelated call asking the engine for work.

The rig protocol (three settings, all recorded in the run logs):

```
-PslArgs="-Dlucistarlink.benchmark.prepareRing=8 \
          -Dlucistarlink.benchmark.quiesceSettleMs=1000 \
          -Dlucistarlink.benchmark.globalEngineBarrier=false"
```

and it exists for reasons that were each a wrong number once: the ring and the settle make the world quiescent
before a pass is timed, and the box-scoped barrier stops a pass being charged for light work belonging to chunks
*outside* the measured box (before that fix, the same engine measured anywhere between 1 and 29 ticks).

## 5. Closed routes (do not re-open without new evidence)

| Route | Why it is closed |
|---|---|
| Whole-chunk sky recompute per burst | 8900–9700 µs of work where the incremental path costs ~1700; superseded by the +-16 window |
| Flat byte-per-cell light mirror | measured twice, negative twice: slot-keyed it was 27.8 s → 322 s per pass; chunk-keyed it was correct but 8–40% slower |
| One-byte material table per cell | its first "10% faster" was wrong light (a stale table); with a correct dirty bit it was 11% slower |
| Dispatching small bursts back to the async path | took the border cell from 0.43 ms to 4.03 ms |
| Batching the per-tick flush | variance exploded (2.42/3.59/3.79 against 2.69–2.86) |
| Deferring the block-light half | 17.8 ms: keeping the buffer made the per-tick `hasUpdates()` settle partial bursts repeatedly |
| Owning the update loop entirely (no engine queue at all) | two distinct hang mechanisms, one a self-dependency deadlock where waiter and releaser are the same server thread — recorded in `OWN-SCHEDULER-PLAN.md` and `OWN-INSTALL-PLAN.md` |
| Carrying a "not bit-identical to vanilla" plan | the evidence for it (a 6.5% history gap) was a bug in the probe, not in the engine — the real gap is 24 cells in 574,340 |
| A consolidated decrease drain (R4-1, `scalablelux.batchDecrease`) | **removed in 2.0.4 for correctness, not for speed**: it drained the engine's queues after the seeding step had already destroyed its caches, so the drain could not read a neighbour or write a cell — nothing propagated, and a placed light source lit only its own cell. Its measured "win" on `block_toggle_border` (0.32 ms) was the work it was not doing; with the drain actually running that cell reads 5.03 ms against ScalableLux's 4.01. Re-opening the idea means designing it so the drain runs with proper caches (`docs/BUG-EMITTER-BLOCK-LIGHT.md`, root cause four) |

## 6. Debug and measurement surface

| Surface | How to enable | What it gives |
|---|---|---|
| `SLTELEM` line | `-Dscalablelux.telemetrySeconds=N` (default 30) | queue depth, dirty positions, pooled propagators, engine counters |
| `LuxProfiler` counters | `-Dscalablelux.profile=true` | per-phase counters (BFS pops, level skips, apply waits, own-edit counters) |
| Windowed-settle timings | `-Dscalablelux.recomputeDebug=true` | expand / halo / sweep / shell / BFS / install µs per settle |
| `/lucistarlink stats\|light\|relight` | in game, permission level 2 | live engine state, a position's light, and the repair path |
| Rig jar | `./gradlew build -Pmod_id=lucistarlinkrig` | the same mod under a different mod id, loadable beside the 1.x harness |

A convention worth keeping: **a probe's number never decides a direction on its own.** The most expensive mistake in
this project's history was a probe that stored opacity as a 0/1/2 classification and then used it as the attenuation
— it let light through stone and manufactured the "history gap" that an entire plan was built on. One point-print
inside the engine exposed it.
