package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.chunk.LightChunk;

public interface LuxLightPublisher {
    void lucistarlink$publish(LuxRelightResult result, LightChunk expectedChunk);

    /**
     * Runs any queued publication now, on the light thread, and returns once it is done. Same work, same thread,
     * same data as the asynchronous path - only the waiting changes: the caller (the tick thread) blocks until the
     * light thread has committed, so the commit is already visible when the tick ends instead of being picked up
     * after it. Bounded, so a busy engine degrades to the asynchronous behaviour instead of stalling the tick.
     */
    void lucistarlink$drainNow();
}
