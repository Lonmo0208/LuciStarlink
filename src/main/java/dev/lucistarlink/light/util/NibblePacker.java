package dev.lucistarlink.light.util;

import dev.lucistarlink.light.region.RegionLightData;
import net.minecraft.world.level.chunk.DataLayer;

public final class NibblePacker {
    private NibblePacker() {
    }

    public static DataLayer packSection(RegionLightData data, byte[] source, int sectionWorldX, int sectionY, int sectionWorldZ) {
        byte[] packed = NibbleSectionPacker.packSectionBytes(data, source, sectionWorldX, sectionY, sectionWorldZ);
        return packed == null ? new DataLayer() : new DataLayer(packed);
    }
}
