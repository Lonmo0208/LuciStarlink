# Lux correctness / observability contract

This document states which vanilla behaviours Lux must reproduce, how that is
verified, and what is known to differ today.

## 1. Contract

In the default (strict) configuration Lux must be **indistinguishable from vanilla**
for every externally observable light query, specifically:

1. **Block light and skylight values** for every block position, once the engine has
   quiesced (all queued tasks drained), must equal vanilla's stabilized values.
2. **Decreases and removals** (torch removal, opaque placement, roof construction) must
   clear exactly the cells vanilla clears — no persistent dark or bright artefacts.
3. **Increases and openings** must light exactly the cells vanilla lights, including
   up-flow into new openings and lateral spread beyond any bounded repair shape.
4. **Section publication** must publish every section whose content changed and no
   section whose content did not (chunk save data and client notifications depend on
   section presence).
5. **Gameplay thresholds** (mob spawning, plant growth, snow/ice, block ticks, mod
   light queries via `LevelReader.getMaxLocalRawBrightness` and friends) read vanilla
   storage; because Lux publishes into vanilla storage and adopts from it, gameplay
   cannot observe a difference without a light-value difference.
6. **Notifications**: published sections notify the affected client sections through the
   same `onLightUpdate` path vanilla uses (27-neighbourhood deduplicated per batch).
7. **Staleness safety**: results computed against a chunk instance that has been
   replaced are discarded (`expectedChunk` identity check); region ownership is
   single-writer (`RegionOwnerTable`); stale jobs are rejected, never merged.

## 2. What is verified, and how

### 2.1 Differential suite against a full-recompute reference
(`src/test/java/.../light/differential/EngineDifferentialTest`)

A deliberately simple, obviously-correct reference engine
(`light/reference/ReferenceLightEngine`) implements vanilla-equivalent block and sky
semantics on plain material grids and recomputes the entire volume from scratch. Lux's
incremental engines are compared cell-by-cell against it after every edit batch for:

* 3 deterministic seeds, initial compute,
* 24 randomized edit batches per seed (place/remove/toggle stone, glowstone, torches,
  leaves, water, glass, sea lanterns),
* roof construction, roof holes, roof removal,
* rapid repeated toggling at the same cells.

This suite **found real bugs** in the pre-redesign engine and drove them out:

* stale bright skylight surviving outside the old repair box after roof cuts,
* missing lateral re-light beyond the old repair radius,
* a seed gap for openings whose light arrives by up-flow (terrain above the opening).

### 2.2 Cross-region differential (`CrossRegionDifferentialTest`, currently `@Disabled`)

Two adjacent regions exchange boundary deltas and must converge to a combined
32-wide reference. This is the validation harness for the boundary-continuation
prototype; it is disabled because the prototype currently oscillates for roof-crossing
batches (see architecture doc section 5). The test is kept as the acceptance gate for
re-enabling `lucistarlink.experimentalBoundaryDeltas`.

### 2.3 In-server verification
* The benchmark's drain barrier verifies `hasPendingRuntimeWork()` is false and all
  light-engine futures are complete at every measurement boundary.
* Publication completeness is covered by the dirty-section machinery being the only
  publication path (no unconditional section writes outside `LuxPublishEngine`).

## 3. Known deviations (all documented, none silent)

1. **Cross-region border gap** (pre-existing): with `haloChunks=0` an incremental job
   does not propagate light across its region border. A torch adjacent to a chunk border
   lights fewer cells in the neighbouring chunk than vanilla until some later event
   touches that chunk. The boundary-delta prototype addresses this and is off by
   default while it oscillates; region re-initialization (chunk unload/reload, chunk
   replacement) re-adopts correct state, which bounds how long a gap can persist.
2. **Relaxed equivalence**: none. Lux ships no approximate mode; the historical
   `fastApproximate`/asynchronous experiments are gone from the codebase.

## 4. Mismatch policy

Any mismatch found by the differential suite or in-server verification must be
reduced to a failing scenario, fixed at the algorithm level, and added to the suite as
a regression case. Fallbacks (`if`-gating to vanilla delegation, broader recomputes)
may be used as *safety nets* (e.g. adoption falls back to full compute) but never as a
substitute for understanding the divergence.
