package ca.spottedleaf.starlight.common.chunk;

import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * R5: the per-section dirty bit behind the material (opacity) table.
 *
 * <p>See {@link ca.spottedleaf.starlight.mixin.common.chunk.LevelChunkSectionMixin} for why the signal has to come from
 * the section's own block-write funnel rather than from the light engine's change hooks: the engine does not own the
 * block data, so the only reliable "these blocks changed" bit is the one set where the blocks are written.</p>
 */
public interface ExtendedChunkSection {

    boolean scalablelux$isMaterialDirty();

    void scalablelux$clearMaterialDirty();
}
