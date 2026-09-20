package dev.lucistarlink.light.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionLightDataMaterializationTest {
    private static RegionBounds bounds(int regionChunks, int haloChunks) {
        int chunks = regionChunks + haloChunks * 2;
        int widthBlocks = chunks * 16;
        int sectionCount = 4;
        int heightBlocks = sectionCount * 16;
        int area = widthBlocks * widthBlocks;
        return new RegionBounds(0, 0, regionChunks, haloChunks, widthBlocks, widthBlocks,
                0, heightBlocks, 0, sectionCount, heightBlocks, area, area * heightBlocks);
    }

    @Test
    void fullPopulateInvalidatesMaterializedSections() {
        RegionLightData data = new RegionLightData(bounds(1, 1));
        data.markAllLightMaterialized();
        assertTrue(data.isLightMaterialized(data.sectionLinearIndexLocal(0, 0, 0)));

        data.beginFullPopulate();

        assertFalse(data.isLightMaterialized(data.sectionLinearIndexLocal(0, 0, 0)));
        assertFalse(data.isLightMaterialized(data.sectionLinearIndexLocal(16, 32, 16)));
    }

    @Test
    void resetForReuseInvalidatesMaterializedSections() {
        RegionLightData data = new RegionLightData(bounds(1, 1));
        data.markAllLightMaterialized();

        data.resetForReuse();

        assertFalse(data.isLightMaterialized(data.sectionLinearIndexLocal(0, 0, 0)));
    }

    @Test
    void resetLightForRecomputeKeepsMaterializedSectionsInvalidated() {
        RegionLightData data = new RegionLightData(bounds(1, 1));
        data.markAllLightMaterialized();

        data.resetLightForRecompute();

        assertFalse(data.isLightMaterialized(data.sectionLinearIndexLocal(0, 0, 0)));
    }

    @Test
    void regionMarkCoversOwnedChunksOnly() {
        RegionBounds bounds = bounds(1, 3);
        RegionLightData data = new RegionLightData(bounds);
        data.markRegionLightMaterialized(bounds.originChunkX(), bounds.originChunkZ(), bounds.regionChunks());

        int owned = data.sectionLinearIndexLocal(bounds.haloChunks() * 16, 0, bounds.haloChunks() * 16);
        assertTrue(data.isLightMaterialized(owned));
        assertFalse(data.isLightMaterialized(data.sectionLinearIndexLocal(0, 0, 0)));
        assertFalse(data.isLightMaterialized(data.sectionLinearIndexLocal(0, 0, bounds.haloChunks() * 16)));
        assertFalse(data.isLightMaterialized(data.sectionLinearIndexLocal(bounds.haloChunks() * 16, 0, 0)));
    }

    @Test
    void regionMarkCoversEverySectionOfTheOwnedChunks() {
        RegionBounds bounds = bounds(2, 2);
        RegionLightData data = new RegionLightData(bounds);
        data.markRegionLightMaterialized(bounds.originChunkX(), bounds.originChunkZ(), bounds.regionChunks());

        for (int sectionY = 0; sectionY < bounds.sectionCount(); sectionY++) {
            for (int chunkZ = 0; chunkZ < bounds.regionChunks(); chunkZ++) {
                for (int chunkX = 0; chunkX < bounds.regionChunks(); chunkX++) {
                    int localChunkX = bounds.haloChunks() + chunkX;
                    int localChunkZ = bounds.haloChunks() + chunkZ;
                    int linear = data.sectionLinearIndexLocal(localChunkX * 16, sectionY * 16, localChunkZ * 16);
                    assertTrue(data.isLightMaterialized(linear),
                            "owned chunk " + chunkX + "," + chunkZ + " section " + sectionY + " must be marked");
                }
            }
        }
    }

    @Test
    void clearMaterialsForChunksZeroesTheOwnedChunksOnly() {
        RegionBounds bounds = bounds(1, 1);
        RegionLightData data = new RegionLightData(bounds);
        int owned = data.localIndex(bounds.haloChunks() * 16, 0, bounds.haloChunks() * 16);
        int halo = data.localIndex(0, 0, 0);
        data.opacity[owned] = 15;
        data.emission[owned] = 12;
        data.opacity[halo] = 15;
        data.emission[halo] = 9;

        data.clearMaterialsForChunks(bounds.originChunkX(), bounds.originChunkZ(), bounds.regionChunks());

        assertEquals(0, data.opacity[owned]);
        assertEquals(0, data.emission[owned]);
        assertEquals(15, data.opacity[halo]);
        assertEquals(9, data.emission[halo]);
    }

    @Test
    void clearMaterialsForChunksDoesNotLeakAcrossTheWholeOwnedSpan() {
        RegionBounds bounds = bounds(2, 3);
        RegionLightData data = new RegionLightData(bounds);
        int start = bounds.haloChunks() << 4;
        int end = start + (bounds.regionChunks() << 4);
        int last = end - 1;
        int lastY = bounds.heightBlocks() - 1;
        data.opacity[data.localIndex(start, 0, start)] = 15;
        data.opacity[data.localIndex(last, 0, last)] = 15;
        data.opacity[data.localIndex(start, lastY, last)] = 15;
        data.opacity[data.localIndex(start - 1, 0, start)] = 15;
        data.opacity[data.localIndex(end, 0, start)] = 15;
        data.opacity[data.localIndex(start, 0, start - 1)] = 15;
        data.opacity[data.localIndex(start, 0, end)] = 15;

        data.clearMaterialsForChunks(bounds.originChunkX(), bounds.originChunkZ(), bounds.regionChunks());

        assertEquals(0, data.opacity[data.localIndex(start, 0, start)]);
        assertEquals(0, data.opacity[data.localIndex(last, 0, last)]);
        assertEquals(0, data.opacity[data.localIndex(start, lastY, last)]);
        assertEquals(15, data.opacity[data.localIndex(start - 1, 0, start)]);
        assertEquals(15, data.opacity[data.localIndex(end, 0, start)]);
        assertEquals(15, data.opacity[data.localIndex(start, 0, start - 1)]);
        assertEquals(15, data.opacity[data.localIndex(start, 0, end)]);
    }

    @Test
    void clearAllMaterialsZeroesEveryCell() {
        RegionBounds bounds = bounds(1, 1);
        RegionLightData data = new RegionLightData(bounds);
        for (int index = 0; index < bounds.volume(); index++) {
            data.opacity[index] = 15;
            data.emission[index] = 15;
        }

        data.clearAllMaterials();

        for (int index = 0; index < bounds.volume(); index++) {
            assertEquals(0, data.opacity[index]);
            assertEquals(0, data.emission[index]);
        }
    }

    @Test
    void clearHaloEmissionZeroesHaloOnly() {
        RegionBounds bounds = bounds(1, 1);
        RegionLightData data = new RegionLightData(bounds);
        int start = bounds.haloChunks() << 4;
        int haloX = data.localIndex(0, 0, start);
        int haloZ = data.localIndex(start, 0, 0);
        int owned = data.localIndex(start, 0, start);
        data.emission[haloX] = 15;
        data.emission[haloZ] = 15;
        data.emission[owned] = 15;
        data.opacity[haloX] = 15;
        data.opacity[haloZ] = 15;
        data.opacity[owned] = 15;

        data.clearHaloEmission(bounds.originChunkX(), bounds.originChunkZ(), bounds.regionChunks());

        assertEquals(0, data.emission[haloX]);
        assertEquals(0, data.emission[haloZ]);
        assertEquals(15, data.emission[owned]);
        assertEquals(15, data.opacity[haloX]);
        assertEquals(15, data.opacity[haloZ]);
        assertEquals(15, data.opacity[owned]);
    }

    @Test
    void clearHaloEmissionCoversEveryCellOutsideTheOwnedChunks() {
        RegionBounds bounds = bounds(1, 1);
        RegionLightData data = new RegionLightData(bounds);
        for (int index = 0; index < bounds.volume(); index++) {
            data.emission[index] = 15;
        }
        int start = bounds.haloChunks() << 4;
        int end = start + (bounds.regionChunks() << 4);

        data.clearHaloEmission(bounds.originChunkX(), bounds.originChunkZ(), bounds.regionChunks());

        for (int y = 0; y < bounds.heightBlocks(); y++) {
            for (int z = 0; z < bounds.depthBlocks(); z++) {
                for (int x = 0; x < bounds.widthBlocks(); x++) {
                    boolean owned = x >= start && x < end && z >= start && z < end;
                    int value = data.emission[data.localIndex(x, y, z)];
                    if (owned) {
                        assertEquals(15, value, "owned cell " + x + "," + y + "," + z + " must be untouched");
                    } else {
                        assertEquals(0, value, "halo cell " + x + "," + y + "," + z + " must be cleared");
                    }
                }
            }
        }
    }
}
