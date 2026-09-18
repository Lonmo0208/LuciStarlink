package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.chunk.LightChunk;

public interface LuxLightPublisher {
    void lucistarlink$publish(LuxRelightResult result, LightChunk expectedChunk);
}
