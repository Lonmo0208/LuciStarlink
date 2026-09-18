package dev.lucistarlink.light.engine;

import dev.lucistarlink.light.LuxConstants;
import dev.lucistarlink.light.LightMaterial;
import dev.lucistarlink.light.reference.ReferenceLightEngine;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuxSkyLightEngineTest {
    private static final int WIDTH = 48;
    private static final int HEIGHT = 32;
    private static final int ROOF_Y = 24;
    private static final int ROOF_MIN = 8;
    private static final int ROOF_MAX = 39;
    private static final int CENTER = 24;
    private static final int DEEP_Y = 10;
    private static final int LEAF_Y = 12;

    @Test
    void runtimeSolidRoofRepairClearsStaleSkylightBelowShallowBand() {
        RegionLightData data = newRegionData();
        LuxSkyLightEngine engine = new LuxSkyLightEngine();
        engine.compute(data);
        data.clearDirty();

        RuntimeLightChangeBuffer changes = applyRoofOpacity(data, LuxConstants.MAX_LIGHT);

        engine.applyRuntimeChanges(data, changes);

        assertEquals(0, sky(data, CENTER, ROOF_Y - 1, CENTER));
        assertEquals(0, sky(data, CENTER, DEEP_Y, CENTER));
        assertTrue(data.dirtySkySections.get(data.sectionLinearIndexLocal(CENTER, DEEP_Y, CENTER)));
    }

    @Test
    void runtimePartialOpacityRepairPreservesFilteredSkylight() {
        RegionLightData data = newRegionData();
        LuxSkyLightEngine engine = new LuxSkyLightEngine();
        engine.compute(data);

        RuntimeLightChangeBuffer changes = applyRoofOpacity(data, 1);

        engine.applyRuntimeChanges(data, changes);

        assertEquals(14, sky(data, CENTER, ROOF_Y, CENTER));
        assertEquals(13, sky(data, CENTER, ROOF_Y - 1, CENTER));
        assertEquals(0, sky(data, CENTER, DEEP_Y, CENTER));
    }

    @Test
    void computeStopsDirectSkySourceAtLeavesLikeVanilla() {
        RegionLightData data = newRegionData();
        for (int z = CENTER - 2; z <= CENTER + 2; z++) {
            for (int x = CENTER - 2; x <= CENTER + 2; x++) {
                data.opacity[data.localIndex(x, LEAF_Y, z)] = 1;
            }
        }

        new LuxSkyLightEngine().compute(data);

        assertEquals(15, sky(data, CENTER, LEAF_Y + 1, CENTER));
        assertEquals(14, sky(data, CENTER, LEAF_Y, CENTER));
        assertEquals(13, sky(data, CENTER, LEAF_Y - 1, CENTER));
        assertEquals(12, sky(data, CENTER, LEAF_Y - 2, CENTER));
    }

    @Test
    void runtimeGlassMaterialChangePublishesSkySectionWhenLevelIsUnchanged() {
        RegionLightData data = newRegionData();
        LuxSkyLightEngine engine = new LuxSkyLightEngine();
        engine.compute(data);
        data.clearDirty();

        int index = data.localIndex(CENTER, LEAF_Y, CENTER);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(1);
        changes.addMaterial(index,
                LightMaterial.pack(0, 0, LightMaterial.FLAG_AIR | LightMaterial.FLAG_SKYLIGHT_DOWN),
                LightMaterial.pack(0, 0, LightMaterial.FLAG_GLASS | LightMaterial.FLAG_SKYLIGHT_DOWN));

        engine.applyRuntimeChanges(data, changes);

        assertEquals(15, sky(data, CENTER, LEAF_Y, CENTER));
        assertTrue(data.dirtySkySections.get(data.sectionLinearIndexLocal(CENTER, LEAF_Y, CENTER)));
    }

    @Test
    void runtimeSolidRoofRepairDoesNotReseedFromStaleCoveredLeavesOutsideRepairBox() {
        RegionLightData data = newRegionData();
        for (int z = ROOF_MIN; z <= ROOF_MAX; z++) {
            for (int x = ROOF_MIN; x <= ROOF_MAX; x++) {
                data.opacity[data.localIndex(x, LEAF_Y, z)] = 1;
            }
        }

        LuxSkyLightEngine engine = new LuxSkyLightEngine();
        engine.compute(data);

        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer((ROOF_MAX - ROOF_MIN + 1) * (ROOF_MAX - ROOF_MIN + 1));
        for (int z = ROOF_MIN; z <= ROOF_MAX; z++) {
            for (int x = ROOF_MIN; x <= ROOF_MAX; x++) {
                int index = data.localIndex(x, ROOF_Y, z);
                data.opacity[index] = LuxConstants.MAX_LIGHT_BYTE;
                changes.addLight(index, LightMaterial.packLight(0, 0),
                        LightMaterial.packLight(LuxConstants.MAX_LIGHT, 0));
            }
        }

        engine.applyRuntimeChanges(data, changes);

        ReferenceLightEngine vanilla = referenceModel(data);
        assertEquals(vanilla.skyLight[vanilla.index(21, LEAF_Y, 21)] & 0xF, sky(data, 21, LEAF_Y, 21));
        assertEquals(vanilla.skyLight[vanilla.index(21, DEEP_Y, 21)] & 0xF, sky(data, 21, DEEP_Y, 21));
        assertEquals(vanilla.skyLight[vanilla.index(35, LEAF_Y, 35)] & 0xF, sky(data, 35, LEAF_Y, 35));
        assertEquals(vanilla.skyLight[vanilla.index(35, DEEP_Y, 35)] & 0xF, sky(data, 35, DEEP_Y, 35));
        assertTrue(data.dirtySkySections.get(data.sectionLinearIndexLocal(21, LEAF_Y, 21)));
    }

    private static ReferenceLightEngine referenceModel(RegionLightData data) {
        ReferenceLightEngine reference = new ReferenceLightEngine(data.bounds.widthBlocks(), data.bounds.depthBlocks(),
                data.bounds.heightBlocks(), data.opacity, data.emission);
        reference.recompute();
        return reference;
    }

    @Test
    void runtimeLargeRoofSliceClearsDeepLeafSections() {
        int width = 16;
        int height = 128;
        int roofY = 112;
        int leafY = 24;
        int deepY = 10;
        RegionLightData data = new RegionLightData(new RegionBounds(0, 0, 1, 0, width, width,
                0, height, 0, height / 16, height, width * width, width * width * height));
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                data.opacity[data.localIndex(x, leafY, z)] = 1;
            }
        }

        LuxSkyLightEngine engine = new LuxSkyLightEngine();
        engine.compute(data);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(width * width);
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int index = data.localIndex(x, roofY, z);
                data.opacity[index] = LuxConstants.MAX_LIGHT_BYTE;
                changes.addLight(index, LightMaterial.packLight(0, 0),
                        LightMaterial.packLight(LuxConstants.MAX_LIGHT, 0));
            }
        }

        engine.applyRuntimeChanges(data, changes);

        assertEquals(0, sky(data, 8, leafY, 8));
        assertEquals(0, sky(data, 8, deepY, 8));
    }

    @Test
    void runtimeFullDepthRepairClearsRegionBeyondSeedRadius() {
        int width = 64;
        int height = 64;
        int roofY = 48;
        int leafY = 18;
        int deepY = 8;
        int roofMin = 8;
        int roofMax = 55;
        RegionLightData data = new RegionLightData(new RegionBounds(0, 0, 4, 0, width, width,
                0, height, 0, height / 16, height, width * width, width * width * height));
        for (int z = roofMin; z <= roofMax; z++) {
            for (int x = roofMin; x <= roofMax; x++) {
                data.opacity[data.localIndex(x, leafY, z)] = 1;
            }
        }

        LuxSkyLightEngine engine = new LuxSkyLightEngine();
        engine.compute(data);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer((roofMax - roofMin + 1) * (roofMax - roofMin + 1) + 4);
        for (int z = roofMin; z <= roofMax; z++) {
            for (int x = roofMin; x <= roofMax; x++) {
                int index = data.localIndex(x, roofY, z);
                data.opacity[index] = LuxConstants.MAX_LIGHT_BYTE;
                changes.addLight(index, LightMaterial.packLight(0, 0),
                        LightMaterial.packLight(LuxConstants.MAX_LIGHT, 0));
            }
        }

        engine.applyRuntimeChanges(data, changes);

        ReferenceLightEngine vanilla = referenceModel(data);
        assertEquals(vanilla.skyLight[vanilla.index(52, leafY, 52)] & 0xF, sky(data, 52, leafY, 52));
        assertEquals(vanilla.skyLight[vanilla.index(52, deepY, 52)] & 0xF, sky(data, 52, deepY, 52));
    }

    private static RegionLightData newRegionData() {
        RegionBounds bounds = new RegionBounds(0, 0, 3, 0, WIDTH, WIDTH,
                0, HEIGHT, 0, HEIGHT / 16, HEIGHT, WIDTH * WIDTH, WIDTH * WIDTH * HEIGHT);
        return new RegionLightData(bounds);
    }

    private static RuntimeLightChangeBuffer applyRoofOpacity(RegionLightData data, int opacity) {
        return applyRoofOpacity(data, ROOF_MIN, ROOF_MAX, ROOF_MIN, ROOF_MAX, opacity);
    }

    private static RuntimeLightChangeBuffer applyRoofOpacity(RegionLightData data, int minX, int maxX, int minZ, int maxZ,
                                                             int opacity) {
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer((maxX - minX + 1) * (maxZ - minZ + 1));
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int index = data.localIndex(x, ROOF_Y, z);
                data.opacity[index] = (byte) opacity;
                changes.addLight(index, LightMaterial.packLight(0, 0), LightMaterial.packLight(opacity, 0));
            }
        }
        return changes;
    }

    private static int sky(RegionLightData data, int x, int y, int z) {
        return data.skyLight[data.localIndex(x, y, z)] & 0xF;
    }
}
