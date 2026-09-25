package ca.spottedleaf.starlight.mixin.common.chunk;

import ca.spottedleaf.starlight.common.light.ImageLane;
import ca.spottedleaf.starlight.common.light.image.ImageMaterialCache;
import ca.spottedleaf.starlight.common.light.image.ImageMaterialPlanes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The write funnel for section block states: every block write anyone can see - features, structures, worldgen
 * stages and gameplay - passes through {@code LevelChunkSection.setBlockState} (the finding recorded for the
 * material table in NEW-ENGINE-TEARDOWN.md §18). The image lane's material planes hang off this so a cached plane
 * follows writes exactly instead of going stale; a rewrite that bypasses the funnel
 * ({@code recalcBlockCounts}) drops the plane rather than trusting it.
 *
 * <p>Both overloads carry an explicit descriptor: matching on the name alone makes Mixin reject the whole
 * configuration ({@code Invalid descriptor}) and the game never starts - recorded in §18 and re-encountered here.</p>
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin {

    private static final ImageMaterialCache LUCIS_MATERIAL_CACHE = new ImageMaterialCache();
    private static final BlockPos.MutableBlockPos LUCIS_POS = new BlockPos.MutableBlockPos();

    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("TAIL")
    )
    private void scalablelux$onSectionWrite3(final int x, final int y, final int z, final BlockState state,
                                             final CallbackInfoReturnable<BlockState> cir) {
        if (ImageLane.ENABLED) {
            ImageMaterialPlanes.onSectionWrite((LevelChunkSection) (Object) this, x, y, z, state, LUCIS_MATERIAL_CACHE, LUCIS_POS);
        }
    }

    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("TAIL")
    )
    private void scalablelux$onSectionWrite4(final int x, final int y, final int z, final BlockState state,
                                             final boolean useLocks, final CallbackInfoReturnable<BlockState> cir) {
        if (ImageLane.ENABLED) {
            ImageMaterialPlanes.onSectionWrite((LevelChunkSection) (Object) this, x, y, z, state, LUCIS_MATERIAL_CACHE, LUCIS_POS);
        }
    }

    @Inject(method = "recalcBlockCounts", at = @At("HEAD"))
    private void scalablelux$onRecalc(final CallbackInfo ci) {
        if (ImageLane.ENABLED) {
            ImageMaterialPlanes.drop((LevelChunkSection) (Object) this);
        }
    }
}
