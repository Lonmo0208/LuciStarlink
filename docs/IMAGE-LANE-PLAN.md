# The image lane: bringing 1.x's engine core into 2.0

Status: **kernel proven, integration not started** (2026-09-25). This document is the plan, the fuse, and the
reasoning for why this route is open when so much of §11-§28 of `NEW-ENGINE-TEARDOWN.md` is closed.

## 1. The goal, stated honestly

The user's bar: win **both** axes against **both** predecessors — engine metric (`minPassNanos`) and player axis
(`bench.pass_wall_actual`) on all four workloads, vs ScalableLux and vs the 1.x line. Today (2.0.8) the standing is:

| | vs ScalableLux | vs 1.x |
|---|---|---|
| engine metric | structure won 12-21%; border/dense/sky_hole ties (1-3%, noise) | **0/4 — loses** (border 4.33 vs 0.84, structure 3.78 vs 2.75, dense 4.12 vs 2.30, sky_hole 0.87 vs 0.73) |
| player axis | ties at the one-tick floor (50-51 ms) | **4/4 — wins** (1.x pays 59-81 ms, a second tick) |

The engine-metric gap to 1.x is one number: **per-unit-of-work cost**. On the border workload both engines run the
same propagation algorithm on the same 95 changes — ours costs ~41 ns per BFS pop (nibble storage, two-level pointer
chase), theirs ~8.3 ns (flat byte image, contiguous arrays). Nothing else differs enough to matter.

## 2. Why this route is open when the closures say otherwise

Three closures look like they cover this and do not. This is the reasoning the plan stands on; each item names the
closure it is distinguished from.

1. **§16 (flat mirror as BFS read-cache, "correct but 8-40% slower")** — that mirror lived *next to* the nibbles:
   every pop read the mirror AND the write-through hit the nibbles, so the hot working set doubled and every settle
   paid both stores. The image lane inverts this: during the BFS the flat arrays are the **only** thing touched; the
   nibbles are read once on the way in and written once on the way out. §16 disproved "mirror alongside", not
   "image instead of".

2. **§17/§18 (material/opacity table, fake-fast then truly slow)** — those tables were *persistent*: they cached
   opacity across settles and needed a staleness signal, which is where their cost and their risk lived. The image
   lane's opacity lives inside the settle-local region (phase 2: resident per-chunk opacity with the §18 dirty
   mixin — see below), and the §18 mixin is exactly the reliable signal §17 lacked. §18's "correct but 11% slower"
   verdict priced the table as a read-cache inside the nibble BFS; as the BFS's *own* storage the arithmetic changes
   by an order of magnitude (41 ns → single-digit ns per pop).

3. **OWN-ENGINE closure ("material delivery cannot fit, 185 µs/section")** — that arithmetic priced extraction
   **per edit** against ScalableLux's ~34 µs per edit. With the §18 dirty signal, extraction is priced **per section
   lifetime** (built lazily on first edit-touch, maintained O(1) per change through the same funnel vanilla itself
   uses). Per-edit, the image BFS's advantage (×2.3 measured below) repays the build the way 1.x's does.

What is *not* distinguished from: §13.3's floor. `structure_cube`'s reading is mostly vanilla's own `setBlockState`
(1.x = 2.2 ms apply + 0.26 engine; the floor is 2.5-3.2 ms). No engine change can win that cell by more than the
engine's share. See §6.

## 3. The kernel, and what it proved today

Ported into `ca.spottedleaf.starlight.common.light.image` (the 1.x engine is this project's own Lucis code; this is a
move, not a re-derivation):

- `IntBucketQueue` / `IntRingQueue` — 1.x verbatim, package renamed. Levelled int queue (brightest first) + removal
  ring with packed `(index << 4 | level)`.
- `ImageRegion` — a bounded box as three byte arrays (`light`/`opacity`/`emission`), y-major, constant strides, plus
  cell-level dirty bits (the pack step's input) and touched bits.
- `ImageBlockLightEngine` — the 1.x BFS line for line: unconditional `light := newEmission` on change, the loss rule
  (`newEmission < oldEmission || newOpacity > oldOpacity`), removal walk that zero-fills `< removedLevel` and
  re-enqueues survivors as refill sources, emitter re-seed under removed light, add walk spreading by
  `current - cost(opacity)`. Changes arrive as parallel arrays instead of 1.x's packed buffer (the settle is
  synchronous on one thread; the old values are read from the region itself).

**The boundary rule** (what makes a bounded box exact rather than an approximation): the box extends at least 16
blocks past every changed cell — light cannot travel further — and boundary cells keep the light they entered with
and act as fixed sources. This is the same rule the sky window settle runs under, which has passed the canonical
fingerprint gate since 2.0.5.

**`ImageLaneSelfTest` (`-Dscalablelux.imageLaneSelfTest=true`), first run, 2026-09-25:**

```
IMAGE-LANE-SELFTEST fuzzErrors=0 borderShape=112x112x48 changes=95 bestNanos=1084100 meanNanos=1171226
                    pops=57513 nsPerPop=18 lit=95/95 dirtySections~95 verdict=PASS
```

- **fuzzErrors=0**: 400 random worlds × two sequential bursts each — incremental result equals the from-scratch
  oracle cell for cell, and no boundary cell was ever written. The kernel's correctness property holds.
- **border shape priced at 1.08 ms best / 18 ns per pop** against the nibble BFS's ~41 ns/pop on the same machine —
  ×2.3 on the first, untuned port. 1.x's number is ~8.3 ns/pop; the gap to that is bounds checks and queue decode,
  not storage.

## 4. Phase 2: the integration (what remains to build)

1. **Resident per-chunk images** (light + opacity, byte-per-cell first, 4-bit later if memory demands): built lazily
   on the first edit that touches a chunk, from the nibbles (light) and the section cache (opacity/emission). LRU
   bounded (1.x's `OwnedRegionCache` is the model); a 5×5 working set is ~9 MB byte-per-cell.
2. **The dirty signal**: the §18 mixin on `LevelChunkSection.setBlockState` (both descriptors — the Mixin
   `Invalid descriptor` trap is recorded there) marks a section's image stale; the lane re-extracts a stale section
   before use. Worldgen and disk-load paths bypass `setBlockState`, which is fine: images are created *after* those
   (lazy first-touch), never before.
3. **Expand / pack**: expand = copy sections into the region box (4.7 µs/section measured by `OwnFlatField.selfTest`);
   pack = write dirty sections back through the engine's caches to the SWMR updating layer + `updateVisible`
   (4.3 µs/section). Only dirty sections are packed.
4. **Lane wiring**: the inline lane's flush settles the burst through the image engine instead of the nibble BFS;
   small-burst dispatch (the 2.0.8 rule) then inverts — the image settle is synchronous, queue-free and cheap, so
   `LUCIS_INLINE_MIN_BURST` goes back to 0 and every edit takes the image lane. Sky keeps the existing windowed
   settle (phase 3 would port the sky side; not needed for correctness, only for `sky_hole`'s last 0.15 ms).
5. **Recording old material**: the change hook must capture `oldOpacity`/`oldEmission` where the old state is visible
   (the same place ScalableLux's opacity invalidation runs), into the edit buffer alongside the packed position.

## 5. The fuse (pre-registered, per house rules)

The lane ships (default on) only if **all** of the following hold; any miss reverts it and records why:

- `block_toggle_border` ≤ **2.0 ms** (from 4.15; 1.x is 0.84 — see §6 for the honest ceiling),
- `dense_chunk_patch` ≤ **2.3 ms** (must beat 1.x's 2.30),
- `sky_hole` ≤ **0.73 ms** (must beat 1.x) or unchanged within noise if phase 3 (sky port) is deferred,
- `structure_cube` not worse than 2.0.8 (3.78) — the image path does not touch the bulk fill,
- canonical fingerprints everywhere (`sky=905931078dfc5ace block=59e2252f732ce67b`),
- the emitter gradient gate passes (15/14/14/14/13/10) with the lane taking every placement,
- player axis stays at the one-tick floor on all four (settles are synchronous; this should *improve*, never regress),
- the fuzz property (incremental == oracle) holds in the shipped kernel, and the harness's own A/B fingerprint probe
  agrees with the nibble path on random edits in a live world.

## 6. The 8/8 accounting, before anyone asks

With the lane built and the fuse met, the projected standing:

| cell | vs ScalableLux (engine) | vs 1.x (engine) | note |
|---|---|---|---|
| `block_toggle_border` | **win ×3** (1.3-1.5 vs 4.03) | **close**: ~1.3-1.5 vs 0.84 | our number *includes* the synchronous pack (0.2-0.4 ms) that 1.x defers — the deferral is exactly why its player axis pays a second tick. Tuning (bounds-check moat, fewer queue ops) targets ≤1.0 ms; a photo finish either way |
| `dense_chunk_patch` | **win ×4** (~0.8-1.2 vs 3.67) | **win ×2+** (vs 2.30) | the shadow decrease cascade is pure BFS work — the image's home turf |
| `sky_hole` | win (0.5-0.6 vs 0.80) | **win** (vs 0.73) | needs phase 3 (sky image) for the last margin; the block half alone lands ~0.6 |
| `structure_cube` | **win** (already, 12-21%) | **floor-bound coin flip** (2.9-3.3 vs 2.75) | ~70% of this reading is vanilla's own `setBlockState`; 1.x pays it too. Beating 2.75 means winning by less than the floor's own wobble — a tie by construction for *any* engine, including a vanilla rewrite |

Player axis: 4/4 vs 1.x (already), ties-at-worst vs ScalableLux (already) — the lane only moves work *out* of ticks.

So the honest ceiling of this architecture is **15-16 of 16 vs ScalableLux** and **~14 of 16 vs 1.x**, with
`structure_cube`-vs-1.x tied by vanilla's own cost, not by ours. Any source that claims a flat 8/8 with decisive
multiples on every cell is selling the 2.0.5 mistake again. What the lane *does* remove is the sentence "loses the
engine metric to 1.x on all four" — the loss that actually contradicted the mod's pitch.
