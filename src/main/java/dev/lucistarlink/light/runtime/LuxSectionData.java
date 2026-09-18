package dev.lucistarlink.light.runtime;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

public record LuxSectionData(SectionPos sectionPos, LightLayer layer, DataLayer dataLayer) {
}
