package dev.lucistarlink.light.region;

import org.junit.jupiter.api.Test;

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
}
