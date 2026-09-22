package ca.spottedleaf.starlight.mixin.common.chunk;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * R5: the invalidation signal for the per-section material (opacity) table.
 *
 * <p>The table's first form was ~10% faster on {@code structure_cube} and produced <b>wrong, unstable</b> light, because
 * it was only dropped in the engine's own block-change entry point - and blocks are also written by worldgen, by
 * section empty/full transitions, and by anything else that touches a section. This mixin moves the signal to the
 * funnel every block write actually goes through, so the table has a reliable "my blocks changed" bit instead of a
 * guess. The engine reads it once per touched slot per propagation call (never per cell) and rebuilds a section only
 * when the bit is set or the section object itself was replaced.</p>
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements ExtendedChunkSection {

    @Unique
    private volatile boolean scalablelux$materialDirty;

    @Override
    public boolean scalablelux$isMaterialDirty() {
        return this.scalablelux$materialDirty;
    }

    @Override
    public void scalablelux$clearMaterialDirty() {
        this.scalablelux$materialDirty = false;
    }

    // Both overloads are needed, with explicit descriptors: the 4-arg form and the 5-arg form both exist on
    // LevelChunkSection in 1.21.1, and a bare name is ambiguous (that mistake cost one startup: "Invalid descriptor on
    // ...LevelChunkSectionMixin", the whole game failing to load chunks).
    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void scalablelux$markMaterialDirty(final int x, final int y, final int z, final BlockState state,
                                               final CallbackInfoReturnable<BlockState> cir) {
        this.scalablelux$materialDirty = true;
    }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void scalablelux$markMaterialDirty(final int x, final int y, final int z, final BlockState state,
                                               final boolean useLocks, final CallbackInfoReturnable<BlockState> cir) {
        this.scalablelux$materialDirty = true;
    }
}
