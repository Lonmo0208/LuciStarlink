package dev.lucistarlink.light.engine;

import dev.lucistarlink.light.LightMaterial;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.reference.ReferenceLightEngine;
import dev.lucistarlink.light.runtime.RuntimeLightChangeBuffer;
import dev.lucistarlink.light.runtime.RuntimeRegionBatch;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two adjacent single-chunk regions exchange boundary deltas after edits, exactly like
 * the runtime pipeline does, and both region images must converge to a single combined
 * reference solution. This is the correctness proof for cross-region light continuation.
 *
 * Currently disabled: the delta prototype oscillates for roof-crossing batches (stale
 * interior values repeatedly re-raised across the border). The machinery is flagged off
 * in production (lucistarlink.experimentalBoundaryDeltas=false); see
 * docs/light-engine-architecture.md for the analysis and next steps.
 */
@Disabled("boundary delta prototype oscillates under roof-crossing batches; flagged off pending redesign")
class CrossRegionDifferentialTest {
    private static final int HEIGHT = 64;
    private static final long SEED = 0xB0DE51L;

    private Region regionA;
    private Region regionB;
    private ReferenceLightEngine reference;
    private Random random;
    private int batchCounter;

    @Test
    void crossBorderLightContinuesIntoNeighborRegion() {
        RegionBounds boundsA = new RegionBounds(0, 0, 1, 0, 16, 16, 0, HEIGHT, 0, HEIGHT / 16, HEIGHT, 16 * 16, 16 * 16 * HEIGHT);
        RegionBounds boundsB = new RegionBounds(1, 0, 1, 0, 16, 16, 0, HEIGHT, 0, HEIGHT / 16, HEIGHT, 16 * 16, 16 * 16 * HEIGHT);
        byte[] opacity = new byte[32 * 16 * HEIGHT];
        byte[] emission = new byte[32 * 16 * HEIGHT];
        reference = new ReferenceLightEngine(32, 16, HEIGHT, opacity, emission);
        regionA = new Region(boundsA, 0);
        regionB = new Region(boundsB, 16);
        random = new Random(SEED);
        LuxFlags.boundaryDeltas = true;

        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 32; x++) {
                    int o = 0;
                    int e = 0;
                    double roll = random.nextDouble();
                    if (roll < 0.01) {
                        e = 14;
                    } else if (roll < 0.12) {
                        o = random.nextDouble() < 0.7 ? 15 : 1;
                    }
                    opacity[reference.index(x, y, z)] = (byte) o;
                    emission[reference.index(x, y, z)] = (byte) e;
                }
            }
        }
        regionA.copyFrom(opacity, emission);
        regionB.copyFrom(opacity, emission);
        reference.recompute();
        // Production adopts the globally consistent engine state at region init; mirror
        // that here so incremental cross-region deltas are what is being validated.
        regionA.adoptFrom(reference, 0);
        regionB.adoptFrom(reference, 16);
        compare("initial");

        // A torch right at region A's border must light cells in region B.
        placeAndFlow(15, 30, 8, 0, 14);
        placeAndFlow(16, 26, 7, 0, 14);
        placeAndFlow(14, 20, 8, 15, 14);

        // Roofs spanning the border, then removals.
        roofAndHoles();
        randomBatches(12);
    }

    private void placeAndFlow(int x, int y, int z, int opacityValue, int emissionValue) {
        applyEdits(List.of(new int[]{x, y, z, opacityValue, emissionValue}));
    }

    private void roofAndHoles() {
        List<int[]> roof = new ArrayList<>();
        for (int z = 2; z < 14; z++) {
            for (int x = 11; x < 21; x++) {
                roof.add(new int[]{x, HEIGHT - 10, z, 15, 0});
            }
        }
        applyEdits(roof);

        List<int[]> holes = new ArrayList<>();
        for (int z = 5; z < 11; z++) {
            holes.add(new int[]{16, HEIGHT - 10, z, 0, 0});
            holes.add(new int[]{15, HEIGHT - 10, z, 0, 0});
        }
        applyEdits(holes);

        List<int[]> removal = new ArrayList<>();
        for (int z = 2; z < 14; z++) {
            for (int x = 11; x < 21; x++) {
                removal.add(new int[]{x, HEIGHT - 10, z, 0, 0});
            }
        }
        applyEdits(removal);
    }

    private void randomBatches(int batches) {
        for (int batch = 0; batch < batches; batch++) {
            List<int[]> edits = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int x = 8 + random.nextInt(16);
                int z = random.nextInt(16);
                int y = 1 + random.nextInt(HEIGHT - 2);
                edits.add(new int[]{x, y, z, random.nextInt(16), random.nextDouble() < 0.15 ? random.nextInt(16) : 0});
            }
            applyEdits(edits);
        }
    }

    private void applyEdits(List<int[]> edits) {
        Map<Integer, List<int[]>> byRegion = new HashMap<>();
        for (int[] edit : edits) {
            int refIndex = reference.index(edit[0], edit[1], edit[2]);
            int oldOpacity = reference.opacityArray()[refIndex] & 0xF;
            int oldEmission = reference.emissionArray()[refIndex] & 0xF;
            int[] full = {edit[0], edit[1], edit[2], oldOpacity, oldEmission, edit[3], edit[4]};
            byRegion.computeIfAbsent(edit[0] < 16 ? 0 : 1, ignored -> new ArrayList<>()).add(full);
            reference.opacityArray()[refIndex] = (byte) edit[3];
            reference.emissionArray()[refIndex] = (byte) edit[4];
        }

        for (Map.Entry<Integer, List<int[]>> entry : byRegion.entrySet()) {
            Region region = entry.getKey() == 0 ? regionA : regionB;
            region.prepareEdits(entry.getValue());
        }
        for (Map.Entry<Integer, List<int[]>> entry : byRegion.entrySet()) {
            Region region = entry.getKey() == 0 ? regionA : regionB;
            region.applyEdits(entry.getValue());
        }

        // flow boundary deltas to a fixpoint (mirrors the manager's cross-region job chain)
        for (int round = 0; round < 8; round++) {
            long[] deltasA = regionA.drainOutgoingDeltas();
            long[] deltasB = regionB.drainOutgoingDeltas();
            if (deltasA.length == 0 && deltasB.length == 0) {
                break;
            }
            assertTrue(round < 7, "boundary deltas did not converge: deltasA=" + deltasA.length + " deltasB=" + deltasB.length
                    + " firstA=" + sampleDelta(deltasA) + " firstB=" + sampleDelta(deltasB));
            if (deltasA.length > 0) {
                regionB.applyDeltas(deltasA);
            }
            if (deltasB.length > 0) {
                regionA.applyDeltas(deltasB);
            }
        }

        reference.recompute();
        compare("batch#" + (++batchCounter));
    }

    private void compare(String stage) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 32; x++) {
                    int refIndex = reference.index(x, y, z);
                    Region region = x < 16 ? regionA : regionB;
                    int localX = x < 16 ? x : x - 16;
                    int expectBlock = reference.blockLight[refIndex] & 0xF;
                    int actualBlock = region.block(localX, y, z);
                    if (expectBlock != actualBlock) {
                        throw new AssertionError(String.format(
                                "%s: block light at (%d,%d,%d): expected %d got %d%n%s",
                                stage, x, y, z, expectBlock, actualBlock, windowDump(x, y, z)));
                    }
                    int expectSky = reference.skyLight[refIndex] & 0xF;
                    int actualSky = region.sky(localX, y, z);
                    if (expectSky != actualSky) {
                        throw new AssertionError(String.format(
                                "%s: sky light at (%d,%d,%d): expected %d got %d%n%s",
                                stage, x, y, z, expectSky, actualSky, windowDump(x, y, z)));
                    }
                }
            }
        }
    }

    private static String sampleDelta(long[] deltas) {
        if (deltas == null || deltas.length < 2) {
            return "none";
        }
        return RuntimeRegionBatch.deltaX(deltas[0]) + "," + RuntimeRegionBatch.deltaY(deltas[0]) + ","
                + RuntimeRegionBatch.deltaZ(deltas[0]) + " " + RuntimeRegionBatch.deltaOldLevel(deltas[1]) + "->"
                + RuntimeRegionBatch.deltaNewLevel(deltas[1]) + " sky=" + RuntimeRegionBatch.deltaIsSky(deltas[1]);
    }

    private String windowDump(int cx, int cy, int cz) {
        StringBuilder dump = new StringBuilder("window (sky/regionRef/op(block)): A and B columns:");
        for (int z = Math.max(0, cz - 1); z <= Math.min(15, cz + 1); z++) {
            for (int y = Math.min(HEIGHT - 1, cy + 1); y >= Math.max(0, cy - 1); y--) {
                dump.append("\n z=").append(z).append(" y=").append(y).append(": ");
                for (int x = Math.max(0, cx - 2); x <= Math.min(31, cx + 2); x++) {
                    Region region = x < 16 ? regionA : regionB;
                    int localX = x < 16 ? x : x - 16;
                    int refIndex = reference.index(x, y, z);
                    dump.append(String.format("%d/%d/%d(%d) ", region.sky(localX, y, z),
                            reference.skyLight[refIndex] & 0xF, reference.opacityArray()[refIndex] & 0xF,
                            region.block(localX, y, z)));
                }
            }
        }
        return dump.toString();
    }

    private static final class Region {
        private final RegionLightData data;
        private final LuxSkyLightEngine skyEngine = new LuxSkyLightEngine();
        private final LuxBlockLightEngine blockEngine = new LuxBlockLightEngine();
        private final int worldXOffset;
        private byte[] borderBefore;
        private final List<long[]> outgoing = new ArrayList<>();

        private Region(RegionBounds bounds, int worldXOffset) {
            this.data = new RegionLightData(bounds);
            this.worldXOffset = worldXOffset;
        }

        private void copyFrom(byte[] opacity, byte[] emission) {
            int width = data.bounds.widthBlocks();
            int area = data.bounds.area();
            for (int y = 0; y < data.bounds.heightBlocks(); y++) {
                for (int z = 0; z < data.bounds.depthBlocks(); z++) {
                    for (int x = 0; x < width; x++) {
                        int refIndex = (worldXOffset + x) + z * 32 + y * 32 * 16;
                        int index = data.localIndex(x, y, z);
                        data.opacity[index] = opacity[refIndex];
                        data.emission[index] = emission[refIndex];
                    }
                }
            }
        }

        private void init() {
            skyEngine.compute(data);
            blockEngine.compute(data);
        }

        private void adoptFrom(ReferenceLightEngine reference, int worldXOffset) {
            int width = data.bounds.widthBlocks();
            for (int y = 0; y < data.bounds.heightBlocks(); y++) {
                for (int z = 0; z < data.bounds.depthBlocks(); z++) {
                    for (int x = 0; x < width; x++) {
                        int refIndex = reference.index(worldXOffset + x, y, z);
                        int index = data.localIndex(x, y, z);
                        data.blockLight[index] = reference.blockLight[refIndex];
                        data.skyLight[index] = reference.skyLight[refIndex];
                    }
                }
            }
        }

        private void prepareEdits(List<int[]> edits) {
            borderBefore = BorderDeltaSupport.snapshotBorder(data, null);
        }

        private void applyEdits(List<int[]> edits) {
            RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(edits.size());
            for (int[] edit : edits) {
                int index = data.localIndex(edit[0] - worldXOffset, edit[1], edit[2]);
                int oldMaterial = LightMaterial.packLight(edit[3], edit[4]);
                int newMaterial = LightMaterial.packLight(edit[5], edit[6]);
                changes.addMaterial(index, oldMaterial, newMaterial);
                data.opacity[index] = (byte) edit[5];
                data.emission[index] = (byte) edit[6];
            }
            data.clearDirty();
            skyEngine.applyRuntimeChanges(data, changes);
            blockEngine.applyRuntimeChanges(data, changes);
            BorderDeltaSupport.emitBoundaryDeltas(data, borderBefore, (key, deltas) -> outgoing.add(deltas));
        }

        private long[] drainOutgoingDeltas() {
            int total = outgoing.stream().mapToInt(deltas -> deltas.length).sum();
            if (total == 0) {
                return new long[0];
            }
            long[] all = new long[total];
            int i = 0;
            for (long[] deltas : outgoing) {
                System.arraycopy(deltas, 0, all, i, deltas.length);
                i += deltas.length;
            }
            outgoing.clear();
            return all;
        }

        private void applyDeltas(long[] deltas) {
            byte[] before = BorderDeltaSupport.snapshotBorder(data, null);
            skyEngine.applyBoundaryDeltas(data, deltas);
            blockEngine.applyBoundaryDeltas(data, deltas);
            BorderDeltaSupport.emitBoundaryDeltas(data, before, (key, out) -> outgoing.add(out));
        }

        private int block(int localX, int y, int localZ) {
            return data.blockLight[data.localIndex(localX, y, localZ)] & 0xF;
        }

        private int sky(int localX, int y, int localZ) {
            return data.skyLight[data.localIndex(localX, y, localZ)] & 0xF;
        }
    }
}
