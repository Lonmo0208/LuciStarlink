package dev.lucistarlink.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the two section maps a light engine layer reads light from: {@code updatingSectionData} (gameplay
 * queries and the write path) and {@code visibleSectionData} (chunk packets and serialization). Writing final
 * light into both keeps the engine's view correct without the copy-on-swap that {@code swapSectionMap}
 * performs, and without the {@code changedSections}/{@code hasInconsistencies} bookkeeping that makes the next
 * light update pass re-derive the same values.
 *
 * <p>Accessor types are the erased field types ({@code M extends DataLayerStorageMap<M>}), since mixin matches
 * accessors against the field descriptor.
 */
@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageAccessor {
    @Accessor("updatingSectionData")
    DataLayerStorageMap<?> lucistarlink$updatingSectionData();

    @Accessor("visibleSectionData")
    DataLayerStorageMap<?> lucistarlink$visibleSectionData();

    /** Pending external data would shadow a directly installed layer in {@code getDataLayerData}. */
    @Accessor("queuedSections")
    Long2ObjectMap<DataLayer> lucistarlink$queuedSections();
}
