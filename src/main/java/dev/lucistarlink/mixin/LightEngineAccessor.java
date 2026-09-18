package dev.lucistarlink.mixin;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the section storage of a light engine layer ({@code BlockLightEngine}/{@code SkyLightEngine}) so the
 * publisher can install final light data without going through {@code queueSectionData}, which would mark the
 * whole engine inconsistent and make the next {@code runLightUpdates} re-derive light that is already correct.
 *
 * <p>The accessor's type must be the erased field type ({@code S extends LayerLightSectionStorage<M>}), not
 * {@code Object}: mixin matches accessors against the field descriptor.
 */
@Mixin(LightEngine.class)
public interface LightEngineAccessor {
    @Accessor("storage")
    LayerLightSectionStorage<?> lucistarlink$storage();
}
