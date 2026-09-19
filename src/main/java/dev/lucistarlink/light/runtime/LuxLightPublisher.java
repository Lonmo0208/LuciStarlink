package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.chunk.LightChunk;

import java.util.concurrent.CompletableFuture;

public interface LuxLightPublisher {
    void lucistarlink$publish(LuxRelightResult result, LightChunk expectedChunk);

    /**
     * Runs any queued publication now, on the light thread, and returns once it is done. Same work, same thread,
     * same data as the asynchronous path - only the waiting changes: the caller (the tick thread) blocks until the
     * light thread has committed, so the commit is already visible when the tick ends instead of being picked up
     * after it. Bounded, so a busy engine degrades to the asynchronous behaviour instead of stalling the tick.
     */
    void lucistarlink$drainNow();

    /**
     * 服务器线程直提（实验，默认关）：在**调用线程**（服务器线程）把待发布的任务装进引擎，并**跳过引擎自己的
     * 更新吸收**。理由是一条实测：我们的 `wait` 地板是 ~235 µs，ScalableLux 是 ~38 µs —— 差别就是「发布必须
     * 经邮箱交给光照线程」这段交接。`directSectionInstall` 已证明「把最终数据直接写进两份地图」是自洽的
     * （我们本来就在光照线程上这么写），这里只是把同样的写搬到服务器线程上做一次。
     *
     * <p>由 {@code -Dlucistarlink.inlineDrain=true} 打开（默认关），只走「调用方说现在」的那条路径。
     */
    void lucistarlink$drainInline();

    /**
     * Queues a no-op publish task and completes the returned future when it runs, i.e. after every publication
     * queued before it has been handed to the engine.
     *
     * <p>Exists for the benchmark. Its per-pass number comes from {@code LevelLightEngine.waitForPendingTasks},
     * which for a synchronous engine (ScalableLux updates light inside the block change) is the moment the work is
     * done, but for us only says "the vanilla engine's own queue for that chunk is empty" - our light work runs on
     * our scheduler and reaches the engine afterwards, so the reported time did not respond to our publish volume
     * at all (a pass that published nothing measured 0.36 ms; a run with 6.4 ms of runtime work measured the same).
     * This marker gives the pass a completion event on our side of the boundary. Engines that never publish
     * complete it immediately, so the metric stays comparable.
     */
    CompletableFuture<Void> lucistarlink$flushMarker(int chunkX, int chunkZ);
}

