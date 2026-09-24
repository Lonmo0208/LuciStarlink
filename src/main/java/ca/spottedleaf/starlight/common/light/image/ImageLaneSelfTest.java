package ca.spottedleaf.starlight.common.light.image;

import java.util.Random;
import java.util.zip.CRC32;

/**
 * Acceptance + pricing for the settle-local image lane kernel (docs/IMAGE-LANE-PLAN.md). Run at server start with
 * {@code -Dscalablelux.imageLaneSelfTest=true}; prints one {@code IMAGE-LANE-SELFTEST} line.
 *
 * <ol>
 *   <li><b>Fuzz — the correctness property:</b> for random worlds, applying changes incrementally
 *       ({@link ImageBlockLightEngine#applyChanges}) must produce <i>exactly</i> the interior that recomputing from
 *       emissions with fixed boundary sources produces ({@link ImageBlockLightEngine#compute}), and must never write a
 *       boundary cell. Bursts of 1..N changes, sequential bursts on the same region, opacity walls, emitter
 *       removals — every disagreement is fatal.</li>
 *   <li><b>Pricing — the border shape:</b> a 112x112x48 region, 95 glowstone placements along the border-corridor L,
 *       timed over repeated rounds (state restored from a snapshot each round, JIT warmed). Reports nanoseconds per
 *       apply, queue pops and nanoseconds per pop — the number that decides whether the lane beats the nibble BFS
 *       (~41 ns/pop) and meets 1.x's ~8 ns/pop.</li>
 * </ol>
 */
public final class ImageLaneSelfTest {

    private ImageLaneSelfTest() {}

    public static void run() {
        final Random random = new Random(20260925L);
        final ImageBlockLightEngine engine = new ImageBlockLightEngine();

        final long fuzzErrors = fuzz(engine, random);
        final String price = price(engine, random);

        System.out.println("IMAGE-LANE-SELFTEST fuzzErrors=" + fuzzErrors + " " + price
                + " verdict=" + (fuzzErrors == 0 ? "PASS" : "FAIL"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // 1) fuzz: incremental must equal the oracle, and the boundary is untouchable
    // ------------------------------------------------------------------------------------------------------------

    private static long fuzz(final ImageBlockLightEngine engine, final Random random) {
        long errors = 0L;
        final int cases = 400;

        for (int c = 0; c < cases; c++) {
            final int w = 40 + random.nextInt(20);
            final int d = 40 + random.nextInt(20);
            final int h = 40 + random.nextInt(20);
            final ImageRegion a = randomRegion(random, w, d, h);
            final ImageRegion b = cloneRegion(a);

            // consistent pre-state: the oracle's own answer is what "already lit" means
            engine.compute(b);
            copyLight(b, a);
            final byte[] lightSnapshot = a.light.clone();
            final byte[] opacitySnapshot = a.opacity.clone();
            final byte[] emissionSnapshot = a.emission.clone();

            // two sequential bursts on the same region (the lane flushes per settle; state between them is re-expanded,
            // but the kernel must also be correct when it is not)
            final int[][] burstA = randomChanges(random, a, 1 + random.nextInt(24));
            engine.applyChanges(a, burstA[0], burstA[1], burstA[2]);
            final int[][] burstB = randomChanges(random, a, 1 + random.nextInt(24));
            engine.applyChanges(a, burstB[0], burstB[1], burstB[2]);

            // oracle: same material, recomputed from scratch
            applyMaterialTo(b, a);
            engine.compute(b);

            errors += compareInterior(a, b, lightSnapshot, w, d, h, c);
        }

        return errors;
    }

    private static ImageRegion randomRegion(final Random random, final int w, final int d, final int h) {
        final ImageRegion region = new ImageRegion(w, d, h);

        for (int index = 0; index < region.volume; index++) {
            final int roll = random.nextInt(100);
            // mostly air, some walls, rare emitters - the shape real terrain has
            region.opacity[index] = (byte) (roll < 70 ? 0 : (roll < 90 ? 15 : random.nextInt(16)));
            region.emission[index] = (byte) (roll >= 97 ? 1 + random.nextInt(15) : 0);
        }

        return region;
    }

    /** {@code ret[0]=indices, ret[1]=newOpacity, ret[2]=newEmission}, all at least 15 cells inside every face. */
    private static int[][] randomChanges(final Random random, final ImageRegion region, final int count) {
        final int[] indices = new int[count];
        final int[] newOpacity = new int[count];
        final int[] newEmission = new int[count];

        for (int i = 0; i < count; i++) {
            final int x = 15 + random.nextInt(region.width - 30);
            final int y = 15 + random.nextInt(region.height - 30);
            final int z = 15 + random.nextInt(region.depth - 30);
            final int index = (y * region.depth + z) * region.width + x;

            indices[i] = index;
            newOpacity[i] = random.nextInt(100) < 60 ? 0 : (random.nextInt(100) < 70 ? 15 : random.nextInt(16));
            newEmission[i] = random.nextInt(100) < 85 ? 0 : 1 + random.nextInt(15);
        }

        return new int[][]{indices, newOpacity, newEmission};
    }

    private static ImageRegion cloneRegion(final ImageRegion source) {
        final ImageRegion region = new ImageRegion(source.width, source.depth, source.height);
        System.arraycopy(source.light, 0, region.light, 0, source.volume);
        System.arraycopy(source.opacity, 0, region.opacity, 0, source.volume);
        System.arraycopy(source.emission, 0, region.emission, 0, source.volume);
        return region;
    }

    private static void copyLight(final ImageRegion source, final ImageRegion target) {
        System.arraycopy(source.light, 0, target.light, 0, source.volume);
    }

    private static void applyMaterialTo(final ImageRegion target, final ImageRegion source) {
        System.arraycopy(source.opacity, 0, target.opacity, 0, source.volume);
        System.arraycopy(source.emission, 0, target.emission, 0, source.volume);
    }

    private static long compareInterior(final ImageRegion incremental, final ImageRegion oracle,
                                        final byte[] lightBefore, final int w, final int d, final int h,
                                        final int caseId) {
        long errors = 0L;

        for (int y = 1; y < h - 1; y++) {
            for (int z = 1; z < d - 1; z++) {
                final int rowBase = (y * d + z) * w;
                for (int x = 1; x < w - 1; x++) {
                    final int index = rowBase + x;
                    if ((incremental.light[index] & 0xF) != (oracle.light[index] & 0xF)) {
                        errors++;
                        if (errors <= 8L) {
                            System.out.println("IMAGE-LANE-MISMATCH case=" + caseId
                                    + " at " + x + "," + y + "," + z
                                    + " incremental=" + (incremental.light[index] & 0xF)
                                    + " oracle=" + (oracle.light[index] & 0xF));
                        }
                    }
                }
            }
        }

        // the boundary must hold the light it entered with
        for (int index = 0; index < incremental.volume; index++) {
            final int x = index % w;
            final int z = (index / w) % d;
            final int y = index / incremental.area;
            final boolean boundary = x == 0 || x == w - 1 || z == 0 || z == d - 1 || y == 0 || y == h - 1;
            if (boundary && (incremental.light[index] & 0xF) != (lightBefore[index] & 0xF)) {
                errors++;
                if (errors <= 8L) {
                    System.out.println("IMAGE-LANE-BOUNDARY-VIOLATION case=" + caseId
                            + " at " + x + "," + y + "," + z
                            + " before=" + (lightBefore[index] & 0xF)
                            + " after=" + (incremental.light[index] & 0xF));
                }
            }
        }

        return errors;
    }

    // ------------------------------------------------------------------------------------------------------------
    // 2) pricing: the border workload's shape
    // ------------------------------------------------------------------------------------------------------------

    private static String price(final ImageBlockLightEngine engine, final Random random) {
        final int w = 112, d = 112, h = 48;
        final ImageRegion region = new ImageRegion(w, d, h);
        final Random materialRandom = new Random(7L);

        // ground: a bumpy stone plane around the lower third, air above - the corridor sits on it
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                final int ground = 12 + (int) (Math.abs(Math.sin(x * 0.3) + Math.cos(z * 0.23)) * 4.0);
                for (int y = 0; y <= ground && y < h; y++) {
                    final int index = (y * d + z) * w + x;
                    region.opacity[index] = 15;
                }
            }
        }
        // scattered opacity so the BFS has to think
        for (int index = 0; index < region.volume; index++) {
            if (region.opacity[index] == 0 && materialRandom.nextInt(20) == 0) {
                region.opacity[index] = (byte) (1 + materialRandom.nextInt(14));
            }
        }

        // 95 glowstone along the L the harness walks: z-line at x=56, x-line at z=56
        final int[] indices = new int[95];
        final int[] newOpacity = new int[95];
        final int[] newEmission = new int[95];
        int n = 0;
        for (int t = 0; t < 48 && n < 95; t++, n += 1) {
            final int x = 56, z = 16 + t * 2, y = 14 + (t & 15);
            final int index = (y * d + z) * w + x;
            indices[n] = index; newOpacity[n] = 0; newEmission[n] = 15;
        }
        for (int t = 0; t < 48 && n < 95; t++, n += 1) {
            final int x = 16 + t * 2, z = 56, y = 14 + ((t * 3) & 15);
            final int index = (y * d + z) * w + x;
            indices[n] = index; newOpacity[n] = 0; newEmission[n] = 15;
        }

        // pre-state: oracle with the glowstone absent
        engine.compute(region);
        final byte[] lightSnapshot = region.light.clone();
        final byte[] opacitySnapshot = region.opacity.clone();

        // JIT warmup
        for (int round = 0; round < 30; round++) {
            System.arraycopy(lightSnapshot, 0, region.light, 0, region.volume);
            System.arraycopy(opacitySnapshot, 0, region.opacity, 0, region.volume);
            region.clearChangeTracking();
            engine.applyChanges(region, indices, newOpacity, newEmission);
        }

        final int rounds = 60;
        long bestNanos = Long.MAX_VALUE;
        long totalNanos = 0L;
        long pops = 0L;
        final CRC32 crc = new CRC32();

        for (int round = 0; round < rounds; round++) {
            System.arraycopy(lightSnapshot, 0, region.light, 0, region.volume);
            System.arraycopy(opacitySnapshot, 0, region.opacity, 0, region.volume);
            region.clearChangeTracking();

            final long t0 = System.nanoTime();
            engine.applyChanges(region, indices, newOpacity, newEmission);
            final long t1 = System.nanoTime();

            final long nanos = t1 - t0;
            bestNanos = Math.min(bestNanos, nanos);
            totalNanos += nanos;
            pops = engine.lastPopCount;
            if (round == rounds - 1) {
                // keep the result alive so the JIT cannot drop the work
                for (final long word : region.dirty) {
                    crc.update((int) word);
                }
            }
        }

        // what the change did (sanity): the corridor must now be lit
        int lit = 0;
        for (final int index : indices) {
            if ((region.light[index] & 0xF) == 15) {
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
                + " dirtySections~" + countDirty(region)
                + " crc=" + crc.getValue();
    }

    private static int countDirty(final ImageRegion region) {
        // sections the pack step would have to write, in the region's own y-major layout (4096-cell blocks)
        int sections = 0;
        for (int base = 0; base < region.volume; base += 4096) {
            final int end = Math.min(base + 4096, region.volume);
            boolean any = false;
            for (int index = base; index < end && !any; index++) {
                any = region.isDirty(index);
            }
            if (any) {
                sections++;
            }
        }
        return sections;
    }
}
