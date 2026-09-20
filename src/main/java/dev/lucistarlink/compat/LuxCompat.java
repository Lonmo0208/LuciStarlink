package dev.lucistarlink.compat;

import dev.lucistarlink.compat.sable.SableCompat;
import dev.lucistarlink.compat.sable.SablePresence;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;

public final class LuxCompat {
    public static final String SABLE_MOD_ID = "sable";

    private LuxCompat() {
    }

    /**
     * 与 mixin 应用时的判据一致（{@link SablePresence}），不能用 {@code ModList.isLoaded("sable")}：
     * modid 命中而标记类不在时，accessor 没有被混进去，按 Sable 处理就会每次都抛 ClassCastException。
     */
    public static boolean isSableLoaded() {
        return SablePresence.isPresent();
    }

    public static boolean isSablePlotChunk(LightChunkGetter getter, ChunkPos chunkPos) {
        return isSableLoaded() && isSablePlotChunk(getter, chunkPos.x, chunkPos.z);
    }

    public static boolean isSablePlotChunk(LightChunkGetter getter, int chunkX, int chunkZ) {
        // Presence first: without Sable the cached flag answers the whole question and the getter is never touched.
        if (!isSableLoaded()) {
            return false;
        }
        Level level = levelFromGetter(getter);
        return level != null && isSablePlotChunk(level, chunkX, chunkZ);
    }

    public static boolean isSablePlotChunk(Level level, int chunkX, int chunkZ) {
        return isSableLoaded() && SableCompat.isSablePlotChunk(level, chunkX, chunkZ);
    }

    private static Level levelFromGetter(LightChunkGetter getter) {
        if (getter instanceof ServerChunkCache serverChunkCache) {
            return serverChunkCache.getLevel();
        }
        return null;
    }
}
