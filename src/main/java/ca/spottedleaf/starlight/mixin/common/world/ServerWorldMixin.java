package ca.spottedleaf.starlight.mixin.common.world;

import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.world.ExtendedWorld;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Mixin(ServerLevel.class)
public abstract class ServerWorldMixin extends Level implements WorldGenLevel, ExtendedWorld {

    @Shadow
    @Final
    private ServerChunkCache chunkSource;

    protected ServerWorldMixin(WritableLevelData writableLevelData, ResourceKey<Level> resourceKey, RegistryAccess registryAccess, Holder<DimensionType> holder, Supplier<ProfilerFiller> supplier, boolean bl, boolean bl2, long l, int i) {
        super(writableLevelData, resourceKey, registryAccess, holder, supplier, bl, bl2, l, i);
    }

    @Override
    public final LevelChunk scalablelux$getChunkAtImmediately(final int chunkX, final int chunkZ) {
        final ChunkMap storage = this.chunkSource.chunkMap;
        final ChunkHolder holder = storage.getVisibleChunkIfPresent(CoordinateUtils.getChunkKey(chunkX, chunkZ));

        if (holder == null) {
            return null;
        }

        final ChunkResult<LevelChunk> result = holder.getFullChunkFuture().getNow(null);

        return result == null ? null : result.orElse(null);
    }

    @Override
    public final ChunkAccess scalablelux$getAnyChunkImmediately(final int chunkX, final int chunkZ) {
        final ChunkMap storage = this.chunkSource.chunkMap;
        final ChunkHolder holder = storage.getVisibleChunkIfPresent(CoordinateUtils.getChunkKey(chunkX, chunkZ));

        return holder == null ? null : holder.getLatestChunk();
    }

    /**
     * The guaranteed server-thread settle point for the inline edit lane (see {@code lucis$flushPendingEdits}).
     *
     * <p><b>Why this hook is not optional.</b> The lane buffers an edit instead of queueing it, and its buffers may
     * only be applied on the server thread. Its other settle point, {@code hasUpdates()}, is also reached from the
     * light engine's own worker thread - where the thread guard correctly refuses to run the engine - and vanilla only
     * asks from the server thread when the engine's <i>queue</i> has work, which the lane deliberately does not create.
     * On an idle dedicated server that left an edit buffered until some unrelated server-thread call happened to ask:
     * measured 5 seconds, and a save or quit inside that window wrote the chunk's light exactly as it had been before
     * the edit, so a placed light source came back dark after a reload. This tick hook removes the dependency: a
     * buffered edit is applied at most one tick after it was made, before anything can save it.</p>
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void scalablelux$settleBufferedEdits(final BooleanSupplier hasTimeLeft, final CallbackInfo ci) {
        if (this.chunkSource.getLightEngine() instanceof StarLightLightingProvider provider) {
            provider.scalablelux$getLightEngine().lucisFlushPendingEdits();
        }
    }
}
