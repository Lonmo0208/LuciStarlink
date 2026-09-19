package dev.lucistarlink.compat;

import dev.lucistarlink.compat.sable.SableCompat;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.neoforged.fml.ModList;

public final class LuxCompat {
    public static final String SABLE_MOD_ID = "sable";

    private static volatile Boolean sableLoaded;

    private LuxCompat() {
    }

    public static boolean isSableLoaded() {
        Boolean cached = sableLoaded;
        if (cached == null) {
            ModList modList = ModList.get();
            if (modList == null) {
                return false;
            }
            cached = modList.isLoaded(SABLE_MOD_ID);
            sableLoaded = cached;
        }
        return cached;
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
