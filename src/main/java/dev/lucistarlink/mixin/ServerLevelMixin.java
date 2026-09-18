package dev.lucistarlink.mixin;

import dev.lucistarlink.light.engine.LuxServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void lucistarlink$onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
            return;
        }
        if (!LightEngine.hasDifferentLightProperties(level, pos, oldState, newState)
                && !LuxServices.controller().hasRelevantRuntimeMaterialChange(level, pos, oldState, newState)) {
            return;
        }
        LuxServices.controller().enqueueBlockChange(level, pos, oldState, newState);
    }
}
