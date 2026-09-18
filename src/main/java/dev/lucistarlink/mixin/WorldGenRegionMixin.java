package dev.lucistarlink.mixin;

import dev.lucistarlink.light.engine.LuxServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(net.minecraft.server.level.WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Redirect(method = "setBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;onBlockStateChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void lucistarlink$suppressRuntimeLightDuringWorldgen(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        LuxServices.controller().beginWorldgenWrite();
        try {
            level.onBlockStateChange(pos, oldState, newState);
        } finally {
            LuxServices.controller().endWorldgenWrite();
        }
    }
}
