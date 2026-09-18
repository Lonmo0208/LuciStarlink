package dev.lucistarlink.mixin;

import dev.lucistarlink.light.engine.LuxServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Save-side light safety: when a chunk is written to disk while the engine may still change its light, the
 * serialized data must say "light is not correct" so the game relights the chunk on load. Nothing relights a
 * chunk that loads as light-correct, so without this a chunk saved during a pending light update would come
 * back with that light permanently missing (a torch placed and the player quitting right after is the obvious
 * case).
 *
 * <p>Whether to force it is decided by {@code LuxServices.controller().shouldRelightOnLoad(...)}: precisely for
 * chunks with pending engine work, or for every chunk when {@code forceLightIncorrectOnSave} is enabled.
 */
@Mixin(net.minecraft.world.level.chunk.storage.ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
    @Redirect(method = "write",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;isLightCorrect()Z"))
    private static boolean lucistarlink$forceLightIncorrectBeforeSave(ChunkAccess chunk, ServerLevel level,
                                                                      ChunkAccess chunkBeingWritten) {
        if (LuxServices.controller().shouldRelightOnLoad(level, chunk)) {
            return false;
        }
        return chunk.isLightCorrect();
    }
}
