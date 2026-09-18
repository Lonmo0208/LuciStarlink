package dev.lucistarlink.mixin;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.IntSupplier;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("getChunkQueueLevel")
    IntSupplier lucistarlink$getChunkQueueLevel(long chunkPos);
}
