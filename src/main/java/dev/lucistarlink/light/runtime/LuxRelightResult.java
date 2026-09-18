package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.ChunkPos;

import java.util.List;

public record LuxRelightResult(ChunkPos chunkPos, List<LuxSectionData> sections) {
    /** Chunk X without forcing callers to reference {@link ChunkPos} (the test suite runs without the game). */
    public int chunkX() {
        return chunkPos.x;
    }

    /** Chunk Z without forcing callers to reference {@link ChunkPos} (the test suite runs without the game). */
    public int chunkZ() {
        return chunkPos.z;
    }

    public boolean isForChunk(int chunkX, int chunkZ) {
        return chunkPos.x == chunkX && chunkPos.z == chunkZ;
    }
}
