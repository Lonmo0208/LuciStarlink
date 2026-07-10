package ca.spottedleaf.starlight.mixin.common.lightengine;

import ca.spottedleaf.starlight.common.light.vanillainterface.ThreadedLevelLightEngineVanillaInterface;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkMap.class)
public class ChunkMapMixin {
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/chunk/LightChunkGetter;Lnet/minecraft/server/level/ChunkMap;ZLnet/minecraft/util/thread/ProcessorMailbox;Lnet/minecraft/util/thread/ProcessorHandle;)Lnet/minecraft/server/level/ThreadedLevelLightEngine;"))
    private ThreadedLevelLightEngine redirectLightEngine(LightChunkGetter lightChunkGetter, ChunkMap chunkMap, boolean bl, ProcessorMailbox<Runnable> processorMailbox, ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> processorHandle) {
        return new ThreadedLevelLightEngineVanillaInterface(lightChunkGetter, chunkMap, bl, processorMailbox, processorHandle);
    }
}
