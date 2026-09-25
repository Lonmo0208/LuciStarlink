package ca.spottedleaf.starlight.common.light.image;

import java.util.Random;
import java.util.zip.CRC32;

/**
 * Acceptance + pricing for the image lane kernel (docs/IMAGE-LANE-PLAN.md). Run at server start with
 * {@code -Dscalablelux.imageLaneSelfTest=true}; prints one {@code IMAGE-LANE-SELFTEST} line.
 *
 * <ol>
 *   <li><b>Fuzz — the correctness property:</b> for random worlds, applying changes incrementally
 *       ({@link ImageBlockLightEngine#applyChanges}) must produce <i>exactly</i> the interior that recomputing from
 *       emissions with fixed boundary sources produces ({@link ImageBlockLightEngine#compute}), and must never write a
 *       boundary cell. Two sequential bursts per case, opacity walls, emitter removals — every disagreement is
 *       fatal.</li>
 *   <li><b>Pricing — the border shape:</b> a 112x112x48 region, 95 glowstone placements along the border-corridor L,
 *       timed over repeated rounds (state restored from a snapshot each round, JIT warmed). Reports nanoseconds per
 *       apply, queue pops and nanoseconds per pop — the number that decides whether the lane beats the nibble BFS
 *       (~41 ns/pop) and closes on 1.x's ~8 ns/pop.</li>
 * </ol>
 */
public final class ImageLaneSelfTest {

    private ImageLaneSelfTest() {}

    private static final java.util.concurrent.atomic.AtomicInteger DUMPED = new java.util.concurrent.atomic.AtomicInteger();

    public static void run() {
        final Random random = new Random(20260925L);
        final ImageBlockLightEngine engine = new ImageBlockLightEngine();

        final long fuzzErrors = fuzz(engine, random);
        final String price = price(engine);
        final String roundTrip = roundTrip();

        System.out.println("IMAGE-LANE-SELFTEST fuzzErrors=" + fuzzErrors + " " + price + " " + roundTrip
                + " verdict=" + (fuzzErrors == 0 && roundTrip.contains("roundTripErrors=0") ? "PASS" : "FAIL"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // 1) fuzz: incremental must equal the oracle, and the boundary is untouchable
    // ------------------------------------------------------------------------------------------------------------

    private static long fuzz(final ImageBlockLightEngine engine, final Random random) {
        long errors = 0L;
        final int cases = 400;
        // the fuzz box: one core chunk with a one-chunk halo on every side (the lane's own default shape),
        // 40 blocks tall so the vertical faces have room for interior changes too
        final int w = 48, d = 48, h = 48;

        for (int c = 0; c < cases; c++) {
            final ImageRegionData a = randomRegion(random, w, d, h);
            final ImageRegionData b = cloneRegion(a);

            // consistent pre-state: the oracle's own answer is what "already lit" means
            engine.compute(b);
            copyLight(b, a);
            final byte[] lightSnapshot = a.blockLight.clone();

            // two sequential bursts on the same region (the lane flushes per settle; state between them is
            // re-adopted, but the kernel must also be correct when it is not). Each burst is verified against
            // the oracle immediately, so a corruption is attributed to the burst that caused it.
            final int[][] burstA = randomChanges(random, a, 1 + random.nextInt(24));
            engine.applyChanges(a, burstA[0], burstA[1], burstA[2]);
            applyMaterialTo(b, a);
            engine.compute(b);
            long burstErrors = compareInterior(a, b, lightSnapshot, w, d, h, c);
            if (burstErrors > 0L && DUMPED.get() < 2) {
                dumpNeighbourhood(a, b, burstA, new int[][]{new int[0], new int[0], new int[0]}, w, d, h, c);
            }
            errors += burstErrors;
            if (burstErrors > 0L) {
                DUMPED.incrementAndGet();
                continue;
            }

            final int[][] burstB = randomChanges(random, a, 1 + random.nextInt(24));
            engine.applyChanges(a, burstB[0], burstB[1], burstB[2]);

            // oracle: same material, recomputed from scratch
            applyMaterialTo(b, a);
            engine.compute(b);

            errors += compareInterior(a, b, lightSnapshot, w, d, h, c);
            if (errors > 0L && DUMPED.addAndGet(1) <= 2) {
                minimize(a, lightSnapshot, burstA, burstB, w, d, h, c);
            }
        }

        return errors;
    }

    /** Replays the case one change at a time on a fresh region, verifying against the oracle after each single
     * change; dumps the first transition that diverges, with the pre-state, so the corner is exact. */
    private static void minimize(final ImageRegionData pre, final byte[] preLight,
                                 final int[][] burstA, final int[][] burstB,
                                 final int w, final int d, final int h, final int caseId) {
        final ImageBlockLightEngine engine = new ImageBlockLightEngine();
        final ImageRegionData a = cloneRegion(pre);
        final ImageRegionData b = cloneRegion(pre);
        final ImageRegionData probe = a; // padded-index helper for the decode
        int step = 0;

        for (final int[][] burst : new int[][][]{burstA, burstB}) {
            for (int i = 0; i < burst[0].length; i++) {
                step++;
                final int[] idx = {burst[0][i]};
                final int[] op = {burst[1][i]};
                final int[] em = {burst[2][i]};
                engine.applyChanges(a, idx, op, em);
                applyMaterialTo(b, a);
                engine.compute(b);

                int mx = -1, my = -1, mz = -1;
                outer:
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        for (int x = 0; x < w; x++) {
                            final int index = probe.localIndex(x, y, z);
                            if ((a.blockLight[index] & 0xF) != (b.blockLight[index] & 0xF)) {
                                mx = x; my = y; mz = z;
                                break outer;
                            }
                        }
                    }
                }
                if (mx >= 0) {
                    System.out.println("IMAGE-LANE-MIN case=" + caseId + " step=" + step
                            + " change at " + probe.localX(burst[0][i]) + "," + probe.localY(burst[0][i]) + "," + probe.localZ(burst[0][i])
                            + " -> opacity=" + op[0] + " emission=" + em[0]
                            + " mismatchAt=" + mx + "," + my + "," + mz);
                    dumpNeighbourhood(a, b, burstA, new int[][]{new int[0], new int[0], new int[0]}, w, d, h, caseId);
                    return;
                }
            }
        }

        // single steps are all exact: the failure needs a BATCH. Test every consecutive pair applied as one call.
        int index2 = 0;
        for (final int[][] burst : new int[][][]{burstA, burstB}) {
            for (int i = 0; i + 1 < burst[0].length; i++, index2++) {
                final ImageRegionData a2 = cloneRegion(pre);
                // replay changes [0, i) one at a time (each verified exact), then apply {i, i+1} batched
                boolean pairFailed = false;
                for (int j = 0; j < i; j++) {
                    engine.applyChanges(a2, new int[]{burst[0][j]}, new int[]{burst[1][j]}, new int[]{burst[2][j]});
                }
                final ImageRegionData beforePair = cloneRegion(a2);
                engine.applyChanges(a2,
                        new int[]{burst[0][i], burst[0][i + 1]},
                        new int[]{burst[1][i], burst[1][i + 1]},
                        new int[]{burst[2][i], burst[2][i + 1]});
                applyMaterialTo(b, a2);
                engine.compute(b);
                int mx = -1, my = -1, mz = -1;
                outerPair:
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        for (int x = 0; x < w; x++) {
                            final int idx = a2.localIndex(x, y, z);
                            if ((a2.blockLight[idx] & 0xF) != (b.blockLight[idx] & 0xF)) {
                                mx = x; my = y; mz = z;
                                break outerPair;
                            }
                        }
                    }
                }
                if (mx >= 0) {
                    pairFailed = true;
                    System.out.println("IMAGE-LANE-PAIR case=" + caseId + " pair=(" + i + "," + (i + 1) + ") of burst"
                            + (index2 < burstA[0].length ? 0 : 1)
                            + " mismatchAt=" + mx + "," + my + "," + mz);
                    for (int y = Math.max(0, my - 1); y <= Math.min(h - 1, my + 1); y++) {
                        for (int z = Math.max(0, mz - 1); z <= Math.min(d - 1, mz + 1); z++) {
                            final StringBuilder line = new StringBuilder();
                            for (int x = Math.max(0, mx - 2); x <= Math.min(w - 1, mx + 2); x++) {
                                final int idx = a2.localIndex(x, y, z);
                                line.append(String.format("[%d,%d,%d o=%d e=%d pre=%d A=%d B=%d] ", x, y, z,
                                        a2.opacity[idx] & 0xF, a2.emission[idx] & 0xF,
                                        beforePair.blockLight[idx] & 0xF, a2.blockLight[idx] & 0xF, b.blockLight[idx] & 0xF));
                            }
                            System.out.println("  " + line);
                        }
                    }
                }
                if (pairFailed) {
                    return;
                }
            }
        }
        System.out.println("IMAGE-LANE-MIN case=" + caseId + ": no single step diverges (multi-change interaction)");
    }

    /** Dumps a 3-cell box around the first mismatch plus every changed cell's material, to name the corner. */
    private static void dumpNeighbourhood(final ImageRegionData a, final ImageRegionData b,
                                          final int[][] burstA, final int[][] burstB,
                                          final int w, final int d, final int h, final int caseId) {
        // find the first mismatching cell
        int mx = -1, my = -1, mz = -1;
        outer:
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    final int index = a.localIndex(x, y, z);
                    if ((a.blockLight[index] & 0xF) != (b.blockLight[index] & 0xF)) {
                        mx = x; my = y; mz = z;
                        break outer;
                    }
                }
            }
        }
        if (mx < 0) {
            return;
        }
        System.out.println("IMAGE-LANE-DUMP case=" + caseId + " mismatchAt=" + mx + "," + my + "," + mz);
        for (int y = Math.max(0, my - 1); y <= Math.min(h - 1, my + 1); y++) {
            for (int z = Math.max(0, mz - 1); z <= Math.min(d - 1, mz + 1); z++) {
                final StringBuilder line = new StringBuilder();
                for (int x = Math.max(0, mx - 2); x <= Math.min(w - 1, mx + 2); x++) {
                    final int index = a.localIndex(x, y, z);
                    line.append(String.format("[%d o=%d e=%d A=%d B=%d] ", x,
                            a.opacity[index] & 0xF, a.emission[index] & 0xF,
                            a.blockLight[index] & 0xF, b.blockLight[index] & 0xF));
                }
                System.out.println("  y=" + y + " z=" + z + ": " + line);
            }
        }
        for (final int[][] burst : new int[][][]{burstA, burstB}) {
            for (int i = 0; i < burst[0].length; i++) {
                final int index = burst[0][i];
                final int x = a.localX(index), z = a.localZ(index), y = a.localY(index);
                if (Math.abs(x - mx) <= 4 && Math.abs(y - my) <= 4 && Math.abs(z - mz) <= 4) {
                    System.out.println("  change(" + (burst == burstA ? "A" : "B") + ") at " + x + "," + y + "," + z
                            + " -> opacity=" + burst[1][i] + " emission=" + burst[2][i]);
                }
            }
        }
    }

    private static ImageRegionBounds bounds(final int w, final int d, final int h) {
        // regionChunks=1, haloChunks=1 core+halo box, or a raw box for the pricing shape
        final int regionChunks = w == 48 && d == 48 ? 1 : 7;
        final int haloChunks = regionChunks == 1 ? 1 : 0;
        final int sectionCount = (h + 15) >> 4;
        return new ImageRegionBounds(0, 0, regionChunks, haloChunks, w, d, 0, h, 0, sectionCount, h,
                w * d, w * d * h);
    }

    private static ImageRegionData randomRegion(final Random random, final int w, final int d, final int h) {
        final ImageRegionData region = new ImageRegionData(bounds(w, d, h));

        // fill real cells only: the moat ring must keep its opacity-15 walls and zero light
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    final int index = region.localIndex(x, y, z);
                    final int roll = random.nextInt(100);
                    // mostly air, some walls, rare emitters - the shape real terrain has
                    region.opacity[index] = (byte) (roll < 70 ? 0 : (roll < 90 ? 15 : random.nextInt(16)));
                    region.emission[index] = (byte) (roll >= 97 ? 1 + random.nextInt(15) : 0);
                }
            }
        }

        return region;
    }

    /** {@code ret[0]=indices, ret[1]=newOpacity, ret[2]=newEmission}, all at least 15 cells inside every face. */
    private static int[][] randomChanges(final Random random, final ImageRegionData region, final int count) {
        final int w = region.bounds.widthBlocks();
        final int d = region.bounds.depthBlocks();
        final int h = region.bounds.heightBlocks();
        final int[] indices = new int[count];
        final int[] newOpacity = new int[count];
        final int[] newEmission = new int[count];

        for (int i = 0; i < count; i++) {
            final int x = 15 + random.nextInt(w - 30);
            final int y = 15 + random.nextInt(h - 30);
            final int z = 15 + random.nextInt(d - 30);

            indices[i] = region.localIndex(x, y, z);
            newOpacity[i] = random.nextInt(100) < 60 ? 0 : (random.nextInt(100) < 70 ? 15 : random.nextInt(16));
            newEmission[i] = random.nextInt(100) < 85 ? 0 : 1 + random.nextInt(15);
        }

        return new int[][]{indices, newOpacity, newEmission};
    }

    private static ImageRegionData cloneRegion(final ImageRegionData source) {
        final ImageRegionData region = new ImageRegionData(source.bounds);
        final int volume = source.paddedVolume;
        System.arraycopy(source.blockLight, 0, region.blockLight, 0, volume);
        System.arraycopy(source.opacity, 0, region.opacity, 0, volume);
        System.arraycopy(source.emission, 0, region.emission, 0, volume);
        return region;
    }

    private static void copyLight(final ImageRegionData source, final ImageRegionData target) {
        System.arraycopy(source.blockLight, 0, target.blockLight, 0, source.paddedVolume);
    }

    private static void applyMaterialTo(final ImageRegionData target, final ImageRegionData source) {
        System.arraycopy(source.opacity, 0, target.opacity, 0, source.paddedVolume);
        System.arraycopy(source.emission, 0, target.emission, 0, source.paddedVolume);
    }

    private static long compareInterior(final ImageRegionData incremental, final ImageRegionData oracle,
                                        final byte[] lightBefore, final int w, final int d, final int h,
                                        final int caseId) {
        long errors = 0L;

        // real interior and real faces, through the padded index mapping
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    final int index = incremental.localIndex(x, y, z);
                    final boolean face = x == 0 || x == w - 1 || z == 0 || z == d - 1 || y == 0 || y == h - 1;
                    if (!face) {
                        if ((incremental.blockLight[index] & 0xF) != (oracle.blockLight[index] & 0xF)) {
                            errors++;
                            if (errors <= 8L) {
                                System.out.println("IMAGE-LANE-MISMATCH case=" + caseId
                                        + " at " + x + "," + y + "," + z
                                        + " incremental=" + (incremental.blockLight[index] & 0xF)
                                        + " oracle=" + (oracle.blockLight[index] & 0xF));
                            }
                        }
                    } else if ((incremental.blockLight[index] & 0xF) != (lightBefore[index] & 0xF)) {
                        errors++;
                        if (errors <= 8L) {
                            System.out.println("IMAGE-LANE-BOUNDARY-VIOLATION case=" + caseId
                                    + " at " + x + "," + y + "," + z
                                    + " before=" + (lightBefore[index] & 0xF)
                                    + " after=" + (incremental.blockLight[index] & 0xF));
                        }
                    }
                }
            }
        }

        return errors;
    }

    // ------------------------------------------------------------------------------------------------------------
    // 2) pricing: the border workload's shape
    // ------------------------------------------------------------------------------------------------------------

    private static String price(final ImageBlockLightEngine engine) {
        final int w = 112, d = 112, h = 48;
        final ImageRegionData region = new ImageRegionData(bounds(w, d, h));
        final Random materialRandom = new Random(7L);

        // ground: a bumpy stone plane around the lower third, air above - the corridor sits on it
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                final int ground = 12 + (int) (Math.abs(Math.sin(x * 0.3) + Math.cos(z * 0.23)) * 4.0);
                for (int y = 0; y <= ground && y < h; y++) {
                    region.opacity[region.localIndex(x, y, z)] = 15;
                }
            }
        }
        // scattered opacity so the BFS has to think (real cells only; the moat stays at 15)
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    final int index = region.localIndex(x, y, z);
                    if (region.opacity[index] == 0 && materialRandom.nextInt(20) == 0) {
                        region.opacity[index] = (byte) (1 + materialRandom.nextInt(14));
                    }
                }
            }
        }

        // 95 glowstone along the L the harness walks: z-line at x=56, x-line at z=56
        final int[] indices = new int[95];
        final int[] newOpacity = new int[95];
        final int[] newEmission = new int[95];
        int n = 0;
        for (int t = 0; t < 48 && n < 95; t++, n++) {
            indices[n] = region.localIndex(56, 14 + (t & 15), 16 + t * 2);
            newOpacity[n] = 0; newEmission[n] = 15;
        }
        for (int t = 0; t < 48 && n < 95; t++, n++) {
            indices[n] = region.localIndex(16 + t * 2, 14 + ((t * 3) & 15), 56);
            newOpacity[n] = 0; newEmission[n] = 15;
        }

        // pre-state: oracle with the glowstone absent
        engine.compute(region);
        final byte[] lightSnapshot = region.blockLight.clone();
        final byte[] opacitySnapshot = region.opacity.clone();
        final int cells = region.paddedVolume;

        // JIT warmup
        for (int round = 0; round < 30; round++) {
            System.arraycopy(lightSnapshot, 0, region.blockLight, 0, cells);
            System.arraycopy(opacitySnapshot, 0, region.opacity, 0, cells);
            region.clearDirty();
            engine.applyChanges(region, indices, newOpacity, newEmission);
        }

        final int rounds = 60;
        long bestNanos = Long.MAX_VALUE;
        long totalNanos = 0L;
        long pops = 0L;
        final CRC32 crc = new CRC32();

        for (int round = 0; round < rounds; round++) {
            System.arraycopy(lightSnapshot, 0, region.blockLight, 0, cells);
            System.arraycopy(opacitySnapshot, 0, region.opacity, 0, cells);
            region.clearDirty();

            final long t0 = System.nanoTime();
            engine.applyChanges(region, indices, newOpacity, newEmission);
            final long t1 = System.nanoTime();

            final long nanos = t1 - t0;
            bestNanos = Math.min(bestNanos, nanos);
            totalNanos += nanos;
            pops = engine.lastPopCount;
            if (round == rounds - 1) {
                // keep the result alive so the JIT cannot drop the work
                for (int i = 0; i < region.dirtyBlockSections.size(); i++) {
                    crc.update(region.dirtyBlockSections.get(i) ? 1 : 0);
                }
            }
        }

        // what the change did (sanity): the corridor must now be lit
        int lit = 0;
        for (final int index : indices) {
            if ((region.blockLight[index] & 0xF) == 15) {
                lit++;
            }
        }

        final long meanNanos = totalNanos / rounds;
        return "borderShape=" + w + "x" + d + "x" + h
                + " changes=" + indices.length
                + " bestNanos=" + bestNanos
                + " meanNanos=" + meanNanos
                + " pops=" + pops
                + " nsPerPop=" + (pops == 0L ? 0L : bestNanos / pops)
                + " lit=" + lit + "/" + indices.length
                + " dirtySections=" + region.dirtyBlockSections.cardinality()
                + " crc=" + crc.getValue();
    }

    /**
     * Nibble round-trip: adopt synthetic section nibbles into the padded region, then write them back through the
     * exact mapping writeSection uses - every adopted cell must come back bit-identical. A one-cell (one-nibble)
     * misalignment anywhere in the padded adopt/pack mapping shows up here as a scattered mismatch, without
     * needing a live world.
     */
    private static String roundTrip() {
        final int w = 48, d = 48, h = 48;
        final ImageRegionData region = new ImageRegionData(bounds(w, d, h));
        final Random random = new Random(99L);
        final byte[][] original = new byte[9 * 3][];
        int slot = 0;
        int errors = 0;

        for (int sectionIndex = 0; sectionIndex < 3; sectionIndex++) {
            for (int cz = 0; cz < 3; cz++) {
                for (int cx = 0; cx < 3; cx++) {
                    final byte[] packed = new byte[2048];
                    random.nextBytes(packed);
                    original[slot++] = packed.clone();
                    region.adoptSectionData(cx, cz, sectionIndex, packed, false);
                }
            }
        }

        // write back with writeSection's mapping (region -> packed), then compare per slot
        slot = 0;
        for (int sectionIndex = 0; sectionIndex < 3; sectionIndex++) {
            for (int cz = 0; cz < 3; cz++) {
                for (int cx = 0; cx < 3; cx++) {
                    final int minX = (cx << 4) + 1, minZ = (cz << 4) + 1, baseY = (sectionIndex << 4) + 1;
                    final int width = region.paddedWidth;
                    final int area = region.paddedArea;
                    final byte[] light = region.blockLight;
                    final byte[] packed = new byte[2048];
                    for (int y = 0; y < 16; y++) {
                        final int yBase = (baseY + y) * area;
                        for (int z = 0; z < 16; z++) {
                            final int rowBase = yBase + (minZ + z) * width + minX;
                            final int packedRow = ((y << 8) | (z << 4)) >> 1;
                            for (int x = 0; x < 16; x += 2) {
                                packed[packedRow + (x >> 1)] =
                                        (byte) ((light[rowBase + x] & 0xF) | ((light[rowBase + x + 1] & 0xF) << 4));
                            }
                        }
                    }
                    for (int i = 0; i < 2048; i++) {
                        if (packed[i] != original[slot][i]) {
                            errors++;
                        }
                    }
                    slot++;
                }
            }
        }
        return "roundTripCells=" + (slot * 2048) + " roundTripErrors=" + errors;
    }
}
