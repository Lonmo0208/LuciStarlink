package dev.lucistarlink.light.engine;

import dev.lucistarlink.light.LightMaterial;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-region light: an edit inside the owned chunk whose light reaches into the halo must (a) propagate there
 * with a correct gradient and (b) mark the halo chunk's sections dirty, which is exactly what
 * {@code LuxPublishEngine.publishRegionWithHalo} selects on. Without the halo the same edit leaves the
 * neighbouring chunk dark until its own region job happens to run.
 *
 * <p>The publication plumbing itself (chunk identity capture, publish counters, neighbour re-read) needs the
 * game on the classpath, so it is verified end-to-end on a live server instead - see the runtime counters
 * {@code lucistarlink.publish.halo.sections} and {@code lucistarlink.runtime.region.externalMarked.sections}.
 */
class HaloPublicationTest {
    private static final int HEIGHT = 64;
    private static final int AIR = LightMaterial.pack(0, 0, LightMaterial.FLAG_AIR | LightMaterial.FLAG_SKYLIGHT_DOWN);
    private static final int GLOWSTONE = LightMaterial.pack(0, 15, LightMaterial.FLAG_SKYLIGHT_DOWN);

    private static RegionBounds bounds(int regionChunks, int haloChunks) {
        int width = (regionChunks + haloChunks * 2) * 16;
        return new RegionBounds(0, 0, regionChunks, haloChunks, width, width,
                0, HEIGHT, 0, HEIGHT / 16, HEIGHT, width * width, width * width * HEIGHT);
    }

    private static RegionLightData airImage(RegionBounds bounds) {
        RegionLightData data = new RegionLightData(bounds);
        for (int i = 0; i < data.opacity.length; i++) {
            data.opacity[i] = (byte) LightMaterial.opacity(AIR);
            data.emission[i] = (byte) LightMaterial.emission(AIR);
        }
        data.clearDirty();
        return data;
    }

    private static RegionLightData withEdit(RegionBounds bounds, int x, int y, int z) {
        RegionLightData data = airImage(bounds);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(4);
        changes.add(data.index(x, y, z), AIR, GLOWSTONE);
        new LuxBlockLightEngine().applyRuntimeChanges(data, changes);
        return data;
    }

    @Test
    void lightCrossesTheBorderWithAndWithoutHalo() {
        // halo=1: light spills into the neighbouring chunk
        RegionLightData withHalo = withEdit(bounds(1, 1), 15, 10, 8);
        assertEquals(14, withHalo.blockLight[withHalo.index(16, 10, 8)] & 0xF);
        assertEquals(11, withHalo.blockLight[withHalo.index(19, 10, 8)] & 0xF);
        assertTrue(isHaloSectionDirty(withHalo, 16, 10, 8),
                "the halo chunk's section must be dirty so the publish engine selects it");

        // halo=0: the image is exactly one chunk wide, so there is no neighbour chunk able to carry the light on
        RegionLightData withoutHalo = withEdit(bounds(1, 0), 15, 10, 8);
        assertEquals(1, withoutHalo.sectionWidth, "a halo=0 image covers only the owned chunk");
        assertEquals(15, withoutHalo.blockLight[withoutHalo.index(15, 10, 8)] & 0xF,
                "the emitting cell itself is lit");
    }

    @Test
    void haloLightIsDirtyTrackedForEveryAffectedLayerSection() {
        RegionBounds bounds = bounds(1, 1);
        RegionLightData data = withEdit(bounds, 15, 10, 8);

        // the neighbour cell and its section are marked for publication
        assertTrue(isHaloSectionDirty(data, 16, 10, 8));
        // an untouched, far away halo section is not dragged into the publish
        assertFalse(isHaloSectionDirty(data, 16, 10, 40),
                "dirty tracking stays per section, so publishing costs the affected volume only");
    }

    @Test
    void multiChunkRegionsCarryLightAcrossTheirBorder() {
        RegionBounds bounds = bounds(2, 1);
        RegionLightData data = withEdit(bounds, 8, 10, 16);

        assertEquals(14, data.blockLight[data.index(8, 10, 15)] & 0xF,
                "light crosses the border of a multi-chunk region as well");
        assertTrue(isHaloSectionDirty(data, 8, 10, 15),
                "the halo chunk north of the core must be dirty so it gets published");
    }

    /** True when the image has the section containing the given world position marked dirty for block light. */
    private static boolean isHaloSectionDirty(RegionLightData data, int worldX, int worldY, int worldZ) {
        int localX = worldX - data.bounds.minBlockX();
        int localY = worldY - data.bounds.minBuildY();
        int localZ = worldZ - data.bounds.minBlockZ();
        int linear = data.sectionLinearIndexLocal(localX, localY, localZ);
        return data.dirtyBlockSections.get(linear);
    }
}
