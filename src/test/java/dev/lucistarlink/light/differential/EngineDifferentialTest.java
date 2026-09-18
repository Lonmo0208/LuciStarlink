package dev.lucistarlink.light.differential;

import dev.lucistarlink.light.LightMaterial;
import dev.lucistarlink.light.engine.LuxBlockLightEngine;
import dev.lucistarlink.light.engine.LuxSkyLightEngine;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.reference.ReferenceLightEngine;
import dev.lucistarlink.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Randomized differential testing of the Lux runtime light engines against a
 * full-recompute reference model with vanilla-equivalent semantics.
 */
class EngineDifferentialTest {
    private static final int WIDTH = 48;
    private static final int HEIGHT = 64;
    private static final long[] SEEDS = {0xC0FFEE1L, 0xBADC0DE2L, 0x5EED0003L};

    private record Material(int opacity, int emission, int flags) {
        int packed() {
            return LightMaterial.pack(opacity, emission, flags);
        }
    }

    private static final Material AIR = new Material(0, 0, LightMaterial.FLAG_AIR | LightMaterial.FLAG_SKYLIGHT_DOWN);
    private static final Material STONE = new Material(15, 0, LightMaterial.FLAG_OCCLUDES);
    private static final Material GLOWSTONE = new Material(0, 15, LightMaterial.FLAG_SKYLIGHT_DOWN);
    private static final Material TORCH = new Material(0, 14, LightMaterial.FLAG_SKYLIGHT_DOWN);
    private static final Material LEAVES = new Material(1, 0, LightMaterial.FLAG_FOLIAGE);
    private static final Material WATER = new Material(1, 0, LightMaterial.FLAG_SKYLIGHT_DOWN);
    private static final Material GLASS = new Material(0, 0, LightMaterial.FLAG_GLASS | LightMaterial.FLAG_SKYLIGHT_DOWN);
    private static final Material SEA_LANTERN = new Material(0, 15, LightMaterial.FLAG_SKYLIGHT_DOWN);

    private static final Material[] PALETTE = {AIR, STONE, GLOWSTONE, TORCH, LEAVES, WATER, GLASS, SEA_LANTERN};

    @Test
    void initialComputeMatchesReference() {
        for (long seed : SEEDS) {
            Scenario scenario = new Scenario(seed);
            scenario.randomTerrain(0.02, 0.15);
            scenario.assertInitialMatchesReference("seed=" + seed);
        }
    }

    @Test
    void randomizedEditSequencesMatchReference() {
        for (long seed : SEEDS) {
            Scenario scenario = new Scenario(seed);
            scenario.randomTerrain(0.02, 0.15);
            scenario.assertInitialMatchesReference("seed=" + seed);
            scenario.runRandomEdits(24, 24, "seed=" + seed);
        }
    }

    @Test
    void roofConstructionAndDestructionMatchesReference() {
        for (long seed : SEEDS) {
            Scenario scenario = new Scenario(seed);
            scenario.randomTerrain(0.02, 0.15);
            scenario.assertInitialMatchesReference("seed=" + seed);
            scenario.runRoofScenario("seed=" + seed);
        }
    }

    @Test
    void repeatedTogglingMatchesReference() {
        Scenario scenario = new Scenario(0x7A11);
        scenario.randomTerrain(0.02, 0.1);
        scenario.assertInitialMatchesReference("seed=0x7A11");
        scenario.runToggleScenario("seed=0x7A11");
    }

    @Test
    void fullyOpenWorldMatchesReference() {
        Scenario scenario = new Scenario(0xF11);
        scenario.fillUniform(AIR);
        scenario.assertInitialMatchesReference("fully-open");
        List<int[]> edits = new ArrayList<>();
        edits.add(scenario.editPlace(WIDTH / 2, HEIGHT / 2, WIDTH / 2, STONE));
        edits.add(scenario.editPlace(WIDTH / 2 + 2, HEIGHT / 2, WIDTH / 2, GLOWSTONE));
        scenario.applyBatchAndCompare(edits, "fully-open place");
    }

    @Test
    void mixedOpenAndRoofedColumnsMatchReference() {
        Scenario scenario = new Scenario(0xF12);
        scenario.fillUniform(AIR);
        scenario.assertInitialMatchesReference("half-roof initial");
        int roofY = HEIGHT / 2;
        List<int[]> roof = new ArrayList<>();
        for (int z = 0; z < WIDTH / 2; z++) {
            for (int x = 0; x < WIDTH; x++) {
                roof.add(scenario.editPlace(x, roofY, z, STONE));
            }
        }
        scenario.applyBatchAndCompare(roof, "half-roof placed");
        List<int[]> holes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int x = scenario.random.nextInt(WIDTH);
            int z = scenario.random.nextInt(WIDTH / 2);
            holes.add(scenario.editPlace(x, roofY, z, AIR));
        }
        scenario.applyBatchAndCompare(holes, "half-roof holes");
        scenario.applyBatchAndCompare(roof.stream()
                .map(edit -> scenario.editPlace(edit[0], edit[1], edit[2], AIR))
                .toList(), "half-roof removed");
    }

    private static final class Scenario {
        private final RegionLightData data;
        private final ReferenceLightEngine reference;
        private final Random random;
        private final LuxSkyLightEngine skyEngine = new LuxSkyLightEngine();
        private final LuxBlockLightEngine blockEngine = new LuxBlockLightEngine();
        private final int[] materials;
        private final String label;
        private long batchCounter;

        Scenario(long seed) {
            RegionBounds bounds = new RegionBounds(0, 0, 3, 0, WIDTH, WIDTH,
                    0, HEIGHT, 0, HEIGHT / 16, HEIGHT, WIDTH * WIDTH, WIDTH * WIDTH * HEIGHT);
            this.data = new RegionLightData(bounds);
            this.materials = new int[bounds.volume()];
            this.random = new Random(seed);
            this.label = "seed=0x" + Long.toHexString(seed);
            this.reference = new ReferenceLightEngine(WIDTH, WIDTH, HEIGHT,
                    data.opacity, data.emission);
        }

        private void setMaterial(int x, int y, int z, Material material) {
            int index = data.localIndex(x, y, z);
            materials[index] = material.packed();
            data.opacity[index] = (byte) material.opacity;
            data.emission[index] = (byte) material.emission;
        }

        private int materialAt(int x, int y, int z) {
            return materials[data.localIndex(x, y, z)];
        }

        private void randomTerrain(double emissionChance, double solidChance) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < WIDTH; z++) {
                    for (int x = 0; x < WIDTH; x++) {
                        Material material = AIR;
                        double roll = random.nextDouble();
                        if (roll < emissionChance) {
                            material = random.nextDouble() < 0.5 ? GLOWSTONE : SEA_LANTERN;
                        } else if (roll < emissionChance + solidChance) {
                            material = random.nextDouble() < 0.75 ? STONE : (random.nextDouble() < 0.5 ? LEAVES : WATER);
                        }
                        setMaterial(x, y, z, material);
                    }
                }
            }
        }

        private void fillUniform(Material material) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < WIDTH; z++) {
                    for (int x = 0; x < WIDTH; x++) {
                        setMaterial(x, y, z, material);
                    }
                }
            }
        }

        private void applyBatchAndCompare(List<int[]> edits, String stage) {
            batchCounter++;
            applyBatch(edits);
            reference.recompute();
            compare(stage + "#" + batchCounter);
        }

        private void assertInitialMatchesReference(String stage) {
            skyEngine.compute(data);
            blockEngine.compute(data);
            reference.recompute();
            compare(stage + " initial");
        }

        private void applyBatch(List<int[]> edits) {
            RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(edits.size());
            for (int[] edit : edits) {
                int x = edit[0];
                int y = edit[1];
                int z = edit[2];
                int index = data.localIndex(x, y, z);
                changes.addMaterial(index, edit[3], edit[4]);
            }
            data.clearDirty();
            for (int[] edit : edits) {
                int index = data.localIndex(edit[0], edit[1], edit[2]);
                data.opacity[index] = (byte) LightMaterial.opacityInt(edit[4]);
                data.emission[index] = (byte) LightMaterial.emissionInt(edit[4]);
            }
            skyEngine.applyRuntimeChanges(data, changes);
            blockEngine.applyRuntimeChanges(data, changes);
            for (int[] edit : edits) {
                materials[data.localIndex(edit[0], edit[1], edit[2])] = edit[4];
            }
        }

        private void runRandomEdits(int batches, int editsPerBatch, String stage) {
            for (int batch = 0; batch < batches; batch++) {
                List<int[]> edits = new ArrayList<>();
                for (int i = 0; i < editsPerBatch; i++) {
                    int x = random.nextInt(WIDTH);
                    int z = random.nextInt(WIDTH);
                    int y = 1 + random.nextInt(HEIGHT - 2);
                    int oldMaterial = materialAt(x, y, z);
                    Material next = PALETTE[random.nextInt(PALETTE.length)];
                    edits.add(new int[]{x, y, z, oldMaterial, next.packed()});
                }
                batchCounter++;
                applyBatch(edits);
                reference.recompute();
                compare(stage + " batch#" + batchCounter);
            }
        }

        private void runRoofScenario(String stage) {
            int roofY = HEIGHT - 8;
            List<int[]> roof = new ArrayList<>();
            for (int z = 6; z < WIDTH - 6; z++) {
                for (int x = 6; x < WIDTH - 6; x++) {
                    roof.add(editPlace(x, roofY, z, STONE));
                }
            }
            batchCounter++;
            applyBatch(roof);
            reference.recompute();
            compare(stage + " roofPlaced#" + batchCounter);

            List<int[]> holes = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int x = 8 + random.nextInt(WIDTH - 16);
                int z = 8 + random.nextInt(WIDTH - 16);
                holes.add(editPlace(x, roofY, z, AIR));
            }
            batchCounter++;
            applyBatch(holes);
            reference.recompute();
            compare(stage + " roofHoles#" + batchCounter);

            List<int[]> removal = new ArrayList<>();
            for (int z = 6; z < WIDTH - 6; z++) {
                for (int x = 6; x < WIDTH - 6; x++) {
                    removal.add(editPlace(x, roofY, z, AIR));
                }
            }
            batchCounter++;
            applyBatch(removal);
            reference.recompute();
            compare(stage + " roofRemoved#" + batchCounter);
        }

        private void runToggleScenario(String stage) {
            int x = WIDTH / 2;
            int z = WIDTH / 2;
            int y = HEIGHT / 2;
            for (int i = 0; i < 16; i++) {
                List<int[]> edits = new ArrayList<>();
                edits.add(editPlace(x, y, z, (i & 1) == 0 ? GLOWSTONE : AIR));
                edits.add(editPlace(x + 3, y - 2, z + 1, (i & 1) == 0 ? STONE : AIR));
                batchCounter++;
                applyBatch(edits);
                reference.recompute();
                compare(stage + " toggle#" + batchCounter);
            }
        }

        private int[] editPlace(int x, int y, int z, Material material) {
            return new int[]{x, y, z, materialAt(x, y, z), material.packed()};
        }

        private void compare(String stage) {
            List<String> mismatches = new ArrayList<>();
            for (int index = 0; index < data.bounds.volume(); index++) {
                int expectedBlock = reference.blockLight[index] & 0xF;
                int actualBlock = data.blockLight[index] & 0xF;
                if (expectedBlock != actualBlock) {
                    mismatches.add(mismatch("block", index, expectedBlock, actualBlock));
                    if (mismatches.size() >= 12) {
                        break;
                    }
                }
            }
            if (mismatches.isEmpty()) {
                for (int index = 0; index < data.bounds.volume(); index++) {
                    int expectedSky = reference.skyLight[index] & 0xF;
                    int actualSky = data.skyLight[index] & 0xF;
                    if (expectedSky != actualSky) {
                        mismatches.add(mismatch("sky", index, expectedSky, actualSky));
                        if (mismatches.size() >= 12) {
                            break;
                        }
                    }
                }
            }
            if (!mismatches.isEmpty()) {
                StringBuilder builder = new StringBuilder("Light divergence at ").append(stage)
                        .append(" (").append(mismatches.size()).append("+ mismatches):");
                for (String mismatch : mismatches) {
                    builder.append("\n  ").append(mismatch);
                }
                builder.append("\n").append(windowDump(mismatches.get(0)));
                throw new AssertionError(builder.toString());
            }
        }

        private String windowDump(String firstMismatch) {
            int[] coords = parseCoords(firstMismatch);
            if (coords == null) {
                return "";
            }
            int cx = coords[0];
            int cy = coords[1];
            int cz = coords[2];
            StringBuilder dump = new StringBuilder("window around mismatch (x, y=z planes, lucistarlink/ref):");
            for (int z = Math.max(0, cz - 2); z <= Math.min(WIDTH - 1, cz + 2); z++) {
                dump.append("\n z=").append(z).append(": ");
                for (int x = Math.max(0, cx - 3); x <= Math.min(WIDTH - 1, cx + 3); x++) {
                    int index = data.localIndex(x, cy, z);
                    dump.append(String.format("%d/%d(%d,%d) ", data.skyLight[index] & 0xF,
                            reference.skyLight[index] & 0xF, data.opacity[index] & 0xF, data.emission[index] & 0xF));
                }
            }
            dump.append("\n column (x=").append(cx).append(", z=").append(cz).append(") top-down:");
            for (int y = Math.min(HEIGHT - 1, cy + 4); y >= Math.max(0, cy - 4); y--) {
                int index = data.localIndex(cx, y, cz);
                dump.append(String.format("%ny=%d %d/%d(op %d)", y, data.skyLight[index] & 0xF,
                        reference.skyLight[index] & 0xF, data.opacity[index] & 0xF));
            }
            return dump.toString();
        }

        private int[] parseCoords(String mismatch) {
            try {
                int open = mismatch.indexOf('(');
                int close = mismatch.indexOf(')');
                String[] parts = mismatch.substring(open + 1, close).split(",");
                return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim())};
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private String mismatch(String layer, int index, int expected, int actual) {
            int y = index / data.bounds.area();
            int rem = index - y * data.bounds.area();
            int z = rem / data.bounds.widthBlocks();
            int x = rem - z * data.bounds.widthBlocks();
            return String.format("%s light at (%d,%d,%d): expected %d got %d (opacity=%d emission=%d)",
                    layer, x, y, z, expected, actual, data.opacity[index] & 0xF, data.emission[index] & 0xF);
        }
    }
}
