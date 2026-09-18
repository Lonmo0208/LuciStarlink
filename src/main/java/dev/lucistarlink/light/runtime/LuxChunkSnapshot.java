package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.chunk.ChunkAccess;

public record LuxChunkSnapshot(ChunkAccess chunk, boolean trustEdges) {
}
