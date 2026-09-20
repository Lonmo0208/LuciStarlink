package dev.lucistarlink.light.engine;

import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.compat.LuxCompat;
import dev.lucistarlink.config.LuxConfig;
import dev.lucistarlink.light.LightMaterial;
import dev.lucistarlink.light.LightMaterialCache;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.runtime.LuxRelightResult;
import dev.lucistarlink.light.runtime.LuxRuntimeManager;
import dev.lucistarlink.test.LuxBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LuxEngineController {
    /**
     * Owned region size in chunks for the runtime path: the config key, with the hidden property as an override so
     * the benchmark rig can vary it without a rebuild. The config key used to be read by nothing at all, which made
     * it a knob that did nothing (the live paths hardcoded 1 or read the hidden property).
     *
     * <p>The property is read once at class initialization: this method sits on the per-block-change path (and on
     * the bulk-bounds walk), and {@code Integer.getInteger} goes through a synchronized property lookup plus a
     * parse every call. The config field stays live, only the override is frozen.
     */
    private static final Integer RUNTIME_REGION_CHUNKS_OVERRIDE =
            Integer.getInteger("lucistarlink.runtimeRegionChunks");
    private static final Integer RUNTIME_HALO_CHUNKS_OVERRIDE =
            Integer.getInteger("lucistarlink.runtimeHaloChunks");
    private static final long PROMPT_DISPATCH_NANOS =
            Math.max(0L, Long.getLong("lucistarlink.promptDispatchNanos", 250_000L));

    public static int runtimeRegionChunks() {
        int configured = RUNTIME_REGION_CHUNKS_OVERRIDE != null
                ? RUNTIME_REGION_CHUNKS_OVERRIDE
                : LuxConfig.regionChunks;
        return Math.max(1, Math.min(configured, 16));
    }

    /**
     * A bulk write scope is closed by its caller. If the caller throws in between (or never closes it),
     * the scope stays in the thread local forever: every later block change on that thread is treated as
     * "already covered by the bulk relight" and never enqueued, while the scope's region map stays
     * referenced. Reap scopes that saw no activity for this long.
     */
    private static final long BULK_SCOPE_MAX_IDLE_NANOS =
            Long.getLong("lucistarlink.runtime.bulk.maxIdleNanos", 30_000_000_000L);

    /**
     * A queued worldgen task holds the whole extracted region buffer (four byte arrays
     * over the region volume, several megabytes per chunk), so the worker queue must be
     * capped. Past the cap the task runs on the submitting thread instead, which turns
     * chunk-load bursts into latency rather than unbounded heap growth.
     */
    private static final int MAX_INFLIGHT_WORLDGEN_TASKS = Math.max(2,
            Integer.getInteger("lucistarlink.maxInflightWorldgenTasks", Math.max(2, worldgenWorkerCount() * 4)));

    private final LightMaterialCache materialCache = new LightMaterialCache();
    private final LuxRelighter relighter = new LuxRelighter(materialCache, new LuxRegionExtractor(materialCache));
    private final LuxRuntimeManager runtimeManager = new LuxRuntimeManager();
    private final ExecutorService worldgenWorkers = Executors.newFixedThreadPool(worldgenWorkerCount(), new LuxWorldgenThreadFactory());
    private final AtomicInteger pendingWorldgenTasks = new AtomicInteger();
    private final AtomicInteger inflightWorldgenTasks = new AtomicInteger();
    private final WorldgenWriteScope worldgenWriteScope = new WorldgenWriteScope();
    private final ThreadLocal<RuntimeBulkScope> runtimeBulkScope = new ThreadLocal<>();
    private final AtomicLong runtimeBackpressureUntilNanos = new AtomicLong();
    /** Set while a prompt dispatch is queued, so a burst of changes costs at most one per tick. */
    private final java.util.concurrent.atomic.AtomicBoolean promptDispatchScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** The last light engine and chunk getter seen by {@link #tickRuntime}, reused by the prompt dispatch. */
    private volatile ThreadedLevelLightEngine lastLightEngine;
    private volatile LightChunkGetter lastGetter;
    private volatile boolean closed;

    public boolean enabled() {
        return !closed && LuxConfig.enabled;
    }

    /**
     * Halo used for runtime jobs. This is what keeps light that crosses a chunk border from being lost:
     * with a halo of 0 a job's propagation stops at its region edge, so light vanilla would carry into the
     * neighbouring chunk only appears when something else touches that region. One chunk (15 blocks) is
     * exactly the light travel distance, so {@code runtimeHaloChunks=1} gives vanilla-equivalent borders.
     */
    public static int runtimeHaloChunks() {
        int configured = RUNTIME_HALO_CHUNKS_OVERRIDE != null
                ? RUNTIME_HALO_CHUNKS_OVERRIDE
                : LuxConfig.runtimeHaloChunks;
        return Math.max(0, Math.min(configured, 2));
    }

    public boolean shouldHandleWorldgen(LightChunkGetter getter, ChunkAccess chunk) {
        return enabled() && LuxConfig.enableWorldgen && chunk != null && !isSablePlotChunk(getter, chunk.getPos());
    }

    public LuxRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        return relighter.relightChunk(getter, chunk, LuxConfig.enableSky, LuxConfig.enableBlock, 1, 1);
    }

    public CompletableFuture<LuxRelightResult> relightChunkAsync(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        ChunkPos chunkPos = chunk.getPos();

        // Prefetch neighbor chunks to reduce latency during extraction
        // This warms up the chunk getter's cache and reduces waiting time
        prefetchNeighborChunks(getter, chunkPos);

        // The world-generation image must keep a halo: 0 stops propagation at the chunk edge, so a chunk generated
        // beside an already-loaded neighbour would leave that neighbour's border light stale (the seam the halo
        // exists to prevent). The config key used to be inert while this call hardcoded 1, so a configuration
        // written by an older build carries haloChunks=0; reading it literally would have switched that protection
        // off for every existing installation. 0 is therefore treated as 1 here, and the key's useful range on this
        // path is 1..2.
        int worldgenHalo = Math.max(1, LuxConfig.haloChunks);

        if (!tryReserveWorldgenSlot()) {
            LuxBenchmarkSupport.count("lucistarlink.worldgen.inflight.inlineFallback");
            return CompletableFuture.completedFuture(
                    computeWorldgenLight(chunkPos, extractWorldgenData(getter, chunk, worldgenHalo), 0L, getter));
        }

        pendingWorldgenTasks.incrementAndGet();
        long submittedAt = LuxBenchmarkSupport.start();
        CompletableFuture<LuxRelightResult> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> computeWorldgenLight(chunkPos, extractWorldgenData(getter, chunk, worldgenHalo), submittedAt, getter),
                    worldgenWorkers);
        } catch (RejectedExecutionException rejected) {
            releaseWorldgenSlot();
            pendingWorldgenTasks.decrementAndGet();
            return CompletableFuture.completedFuture(
                    computeWorldgenLight(chunkPos, extractWorldgenData(getter, chunk, worldgenHalo), 0L, getter));
        }
        return future.whenComplete((result, throwable) -> {
            releaseWorldgenSlot();
            pendingWorldgenTasks.decrementAndGet();
        });
    }

    /**
     * 材质提取：与光照计算放在同一条 worker 线程上。它在生成线程上跑时，每次区块生成都要让出约 5.6 ms
     * （region 加 halo 一共 9 个区块的方块扫描），而这一步只读区块、不碰引擎，vanilla 自己的光照引擎也在
     * 自己的工作线程上读区块，所以挪到 worker 是安全的；换来的是生成线程不再被这一步卡住。
     */
    private RegionLightData extractWorldgenData(LightChunkGetter getter, ChunkAccess chunk, int worldgenHalo) {
        long extractStartedAt = LuxBenchmarkSupport.start();
        RegionLightData data = relighter.extractChunkData(getter, chunk, runtimeRegionChunks(), worldgenHalo);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.worldgen.extract", extractStartedAt);
        return data;
    }

    private LuxRelightResult computeWorldgenLight(ChunkPos chunkPos, RegionLightData data, long submittedAt, LightChunkGetter getter) {
        long startedAt = LuxBenchmarkSupport.start();
        if (startedAt != 0L && submittedAt != 0L) {
            LuxBenchmarkSupport.record("lucistarlink.light_chunk.worker_wait", startedAt - submittedAt);
        }
        LuxRelightResult result = relighter.relightPreparedChunk(chunkPos, data,
                LuxConfig.enableSky, LuxConfig.enableBlock, getter);
        LuxBenchmarkSupport.recordSince("lucistarlink.light_chunk.worker_compute", startedAt);
        return result;
    }

    private boolean tryReserveWorldgenSlot() {
        if (inflightWorldgenTasks.incrementAndGet() <= MAX_INFLIGHT_WORLDGEN_TASKS) {
            return true;
        }
        inflightWorldgenTasks.decrementAndGet();
        return false;
    }

    private void releaseWorldgenSlot() {
        inflightWorldgenTasks.decrementAndGet();
    }
    
    private void prefetchNeighborChunks(LightChunkGetter getter, ChunkPos center) {
        // Prefetch 8 neighbors (N, S, E, W, NE, NW, SE, SW) to reduce cache misses
        // This is especially effective for worldgen where chunks are generated in sequence
        int cx = center.x;
        int cz = center.z;
        
        // Direct neighbors (N, S, E, W) - highest priority
        getter.getChunkForLighting(cx + 1, cz);
        getter.getChunkForLighting(cx - 1, cz);
        getter.getChunkForLighting(cx, cz + 1);
        getter.getChunkForLighting(cx, cz - 1);
        
        // Diagonal neighbors (NE, NW, SE, SW) - secondary priority
        getter.getChunkForLighting(cx + 1, cz + 1);
        getter.getChunkForLighting(cx + 1, cz - 1);
        getter.getChunkForLighting(cx - 1, cz + 1);
        getter.getChunkForLighting(cx - 1, cz - 1);
    }

    public boolean shouldHandleBlockChange(BlockPos pos) {
        return enabled()
                && LuxConfig.enableRuntime
                && pos != null
                && !isWorldgenWriteSuppressed()
                && !runtimeBackpressureActive()
                && runtimeManager.canAcceptMoreWork();
    }

    /**
     * 同 {@link #shouldHandleBlockChange(BlockPos)}，但不看「世界生成写入压制」。
     *
     * <p>入队那条路必须用这个：能走到那里的调用方已经自己确认目标区块是 BLOCK_TICKING 的已满区块，
     * 也就是「世界生成把方块写到了一个已经存在的邻居上」。那种改动不是生成中区块自己的光照（世界生成的
     * 整块计算盖不到它），必须走运行期重算。而如果这里也跟着压制，结果就是：原版 checkBlock 已被取消，
     * 运行期又没入队，那片光照永远停在旧值。
     */
    private boolean shouldHandleBlockChangeIgnoringWorldgen(BlockPos pos) {
        return enabled()
                && LuxConfig.enableRuntime
                && pos != null
                && !runtimeBackpressureActive()
                && runtimeManager.canAcceptMoreWork();
    }

    public boolean shouldHandleBlockChange(LightChunkGetter getter, BlockPos pos) {
        return shouldHandleBlockChange(pos) && !LuxCompat.isSablePlotChunk(getter,
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public boolean shouldHandleBlockChange(Level level, BlockPos pos) {
        return shouldHandleBlockChange(pos) && !LuxCompat.isSablePlotChunk(level,
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public boolean hasRelevantRuntimeMaterialChange(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!enabled() || !LuxConfig.enableRuntime || level == null || pos == null || oldState == newState) {
            return false;
        }
        int oldMaterial = materialCache.lookupLight(level, oldState, pos);
        int newMaterial = materialCache.lookupLight(level, newState, pos);
        return !LightMaterial.hasSameRuntimeProperties(oldMaterial, newMaterial);
    }

    public void enqueueBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!shouldHandleBlockChange(pos)) {
            return;
        }
        long startedAt = LuxBenchmarkSupport.start();
        if (!runtimeManager.enqueue(pos.getX(), pos.getY(), pos.getZ(), oldState, newState)) {
            activateRuntimeBackpressure();
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.enqueue_block_change", startedAt);
    }

    public boolean enqueueBlockChange(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        RuntimeBulkScope bulkScope = runtimeBulkScope.get();
        if (bulkScope != null && bulkScope.boundsRegistered && enabled() && LuxConfig.enableRuntime) {
            return true;
        }
        if (!shouldHandleBlockChangeIgnoringWorldgen(pos)
                || LuxCompat.isSablePlotChunk(level, SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()))) {
            return false;
        }
        if (bulkScope != null) {
            if (!bulkScope.boundsRegistered) {
                bulkScope.record(pos);
                LuxBenchmarkSupport.count("lucistarlink.runtime.bulk.suppressed_block_change");
            }
            return true;
        }
        long startedAt = LuxBenchmarkSupport.start();
        if (!runtimeManager.enqueue(pos.getX(), pos.getY(), pos.getZ(), oldState, newState)) {
            activateRuntimeBackpressure();
            LuxBenchmarkSupport.recordSince("lucistarlink.enqueue_block_change", startedAt);
            return false;
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.enqueue_block_change", startedAt);
        schedulePromptDispatch(level);
        return true;
    }

    /**
     * Wakes the runtime pipeline inside the tick that produced the change.
     *
     * <p>Dispatch otherwise waits for the next {@code tickRuntime}, which is driven from {@code ServerChunkCache.tick}
     * and therefore lands a full tick (50 ms) after a change made later in the tick - measured: a pass whose own
     * publish was tracked landed 55 ms after it applied, against ScalableLux's synchronous ~0.35 ms. Nothing about
     * the work changes here, only when it starts: the batch content, the compute and the publish path are the same,
     * and the dispatch still runs on the server thread. Guarded so a burst of changes costs at most one extra
     * dispatch per tick, and switchable via {@code -Dlucistarlink.promptDispatch=}.
     */
    private void schedulePromptDispatch(Level level) {
        if (!LuxFlags.promptDispatch) {
            return;
        }
        ThreadedLevelLightEngine lightEngine = this.lastLightEngine;
        LightChunkGetter getter = this.lastGetter;
        if (lightEngine == null || getter == null || this.closed) {
            return;
        }
        net.minecraft.server.MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        if (!this.promptDispatchScheduled.compareAndSet(false, true)) {
            return;
        }
        LuxBenchmarkSupport.count("lucistarlink.runtime.promptDispatch.scheduled");
        // The wake-up must not run inline: MinecraftServer.execute() runs the task immediately when it is already
        // on the server thread, which turned one dispatch per *change* (193 per run) instead of one per burst and
        // measurably helped nothing. A short delay off the server thread coalesces a burst, and the server task
        // queue then runs the dispatch later in the same tick instead of at the next tickRuntime.
        long delayNanos = PROMPT_DISPATCH_NANOS;
        Runnable wake = () -> server.execute(() -> {
            this.promptDispatchScheduled.set(false);
            LuxBenchmarkSupport.count("lucistarlink.runtime.promptDispatch.run");
            if (this.closed) {
                return;
            }
            try {
                tickRuntime(lightEngine, getter);
            } catch (Throwable throwable) {
                LuciStarlink.LOGGER.warn("LuciStarlink prompt dispatch failed", throwable);
            }
        });
        if (delayNanos <= 0L) {
            wake.run();
            return;
        }
        java.util.concurrent.CompletableFuture.delayedExecutor(delayNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
                .execute(wake);
    }

    /** Benchmark hook: completes when every publication queued before the call has been handed to the engine. */
    public java.util.concurrent.CompletableFuture<Void> flushMarker(int chunkX, int chunkZ) {
        ThreadedLevelLightEngine lightEngine = this.lastLightEngine;
        if (!enabled() || lightEngine == null) {
            // an engine that never publishes (ScalableLux, or the mod disabled) has nothing to wait for
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return ((dev.lucistarlink.light.runtime.LuxLightPublisher) lightEngine).lucistarlink$flushMarker(chunkX, chunkZ);
    }

    /**
     * Benchmark hook: dispatch whatever runtime work is queued right now, then return a future that completes when
     * that work has actually been handed to the engine.
     *
     * <p>Queuing the marker alone is not enough: a block change is still sitting in the runtime manager's change
     * buffer at that point and has not become a publish task yet, so the marker would complete before the pass's own
     * light was published (measured: 10-20 µs after the engine's own timestamp, i.e. it saw nothing). Dispatching
     * first puts the pass's publication into the queue ahead of the marker, which is what makes the pass's reported
     * time include the work this engine actually did for it. Called on the server thread, so the inline dispatch
     * here is the same call the tick would make.
     */
    public java.util.concurrent.CompletableFuture<Void> flushRuntimeAndMarker(int chunkX, int chunkZ) {
        ThreadedLevelLightEngine lightEngine = this.lastLightEngine;
        LightChunkGetter getter = this.lastGetter;
        if (!enabled() || lightEngine == null || getter == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        try {
            tickRuntime(lightEngine, getter);
        } catch (Throwable throwable) {
            LuciStarlink.LOGGER.warn("LuciStarlink benchmark flush dispatch failed", throwable);
        }
        // 「调用方说：就是现在」—— 把这一 tick 的发布立刻推完，不等那个 250 µs 合并窗口。
        // 为什么不能只在 publishedThisTick 为真时做：像 sky_hole 这种「算完发现不用改」的负载（sections=0、
        // identical=11）永远不为真，于是同步 drain 被整段跳过，调用方要等定时器 —— 实测那正是 wait 里
        // 528~1503 µs 的来源（SL 那一侧只有 3~314 µs）。drain 是幂等的，没有待办时它什么也不做。
        try {
            ((dev.lucistarlink.light.runtime.LuxLightPublisher) lightEngine).lucistarlink$drainInline();
        } catch (Throwable throwable) {
            LuciStarlink.LOGGER.warn("LuciStarlink benchmark flush drain failed", throwable);
        }
        return ((dev.lucistarlink.light.runtime.LuxLightPublisher) lightEngine).lucistarlink$flushMarker(chunkX, chunkZ);
    }

    public void beginRuntimeBulkWrite() {
        reapStaleBulkScope();
        RuntimeBulkScope scope = runtimeBulkScope.get();
        if (scope == null) {
            runtimeBulkScope.set(new RuntimeBulkScope());
            return;
        }
        scope.depth++;
        scope.touch();
    }

    public void recordRuntimeBulkBounds(Level level, int minX, int minZ, int maxXExclusive, int maxZExclusive, long estimatedChanges) {
        RuntimeBulkScope scope = runtimeBulkScope.get();
        if (scope == null || level == null || !enabled() || !LuxConfig.enableRuntime) {
            return;
        }
        scope.touch();
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxXExclusive - 1, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZExclusive - 1, 16);
        long changesPerRegion = Math.max(1L, estimatedChanges / Math.max(1,
                regionCount(minChunkX, maxChunkX, minChunkZ, maxChunkZ)));
        int minRegionChunkX = Math.floorDiv(minChunkX, runtimeRegionChunks()) * runtimeRegionChunks();
        int maxRegionChunkX = Math.floorDiv(maxChunkX, runtimeRegionChunks()) * runtimeRegionChunks();
        int minRegionChunkZ = Math.floorDiv(minChunkZ, runtimeRegionChunks()) * runtimeRegionChunks();
        int maxRegionChunkZ = Math.floorDiv(maxChunkZ, runtimeRegionChunks()) * runtimeRegionChunks();
        for (int regionChunkZ = minRegionChunkZ; regionChunkZ <= maxRegionChunkZ; regionChunkZ += runtimeRegionChunks()) {
            for (int regionChunkX = minRegionChunkX; regionChunkX <= maxRegionChunkX; regionChunkX += runtimeRegionChunks()) {
                if (!LuxCompat.isSablePlotChunk(level, regionChunkX, regionChunkZ)) {
                    scope.recordRegion(RegionBounds.regionKey(regionChunkX, regionChunkZ), changesPerRegion);
                }
            }
        }
        scope.boundsRegistered = true;
    }

    public boolean endRuntimeBulkWrite() {
        RuntimeBulkScope scope = runtimeBulkScope.get();
        if (scope == null) {
            return false;
        }
        if (--scope.depth > 0) {
            scope.touch();
            return true;
        }
        runtimeBulkScope.remove();
        return flushBulkScope(scope);
    }

    private boolean flushBulkScope(RuntimeBulkScope scope) {
        boolean accepted = true;
        for (Map.Entry<Long, Long> entry : scope.regionChangeCounts.entrySet()) {
            if (!runtimeManager.enqueueFullRelight(entry.getKey(), entry.getValue())) {
                accepted = false;
            }
        }
        LuxBenchmarkSupport.count("lucistarlink.runtime.bulk.regions", scope.regionChangeCounts.size());
        LuxBenchmarkSupport.count("lucistarlink.runtime.bulk.estimated_changes", scope.originalChangeCount);
        if (scope.boundsRegistered) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.bulk.suppressed_block_change", scope.originalChangeCount);
        }
        if (!accepted) {
            activateRuntimeBackpressure();
        }
        return accepted && !scope.regionChangeCounts.isEmpty();
    }

    /**
     * Closes a bulk scope that was opened but never closed (an exception thrown in between, or a caller
     * that simply never calls {@link #endRuntimeBulkWrite()}). Without this the scope would sit in the
     * thread local forever: its region map stays referenced and every later block change on that thread
     * is swallowed as "covered by the bulk relight", so light silently stops updating there.
     */
    private void reapStaleBulkScope() {
        RuntimeBulkScope scope = runtimeBulkScope.get();
        if (scope == null) {
            return;
        }
        if (System.nanoTime() - scope.lastActivityNanos < BULK_SCOPE_MAX_IDLE_NANOS) {
            return;
        }
        runtimeBulkScope.remove();
        LuxBenchmarkSupport.count("lucistarlink.runtime.bulk.staleReaped");
        LuciStarlink.LOGGER.warn("LuciStarlink closed a runtime bulk write scope left open for over {} ms by thread "
                        + "\"{}\"; flushing {} region(s) so later block changes are tracked again",
                BULK_SCOPE_MAX_IDLE_NANOS / 1_000_000L,
                Thread.currentThread().getName(),
                scope.regionChangeCounts.size());
        flushBulkScope(scope);
    }

    public void tickRuntime(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter) {
        this.lastLightEngine = lightEngine;
        this.lastGetter = getter;
        if (!enabled() || !LuxConfig.enableRuntime) {
            runtimeManager.flushPendingCommits(lightEngine);
            return;
        }
        reapStaleBulkScope();
        refreshRuntimeBackpressure();
        if (runtimeManager.consumeDroppedWork()) {
            activateRuntimeBackpressure();
        }
        // 峰值保持：遥测窗口必然错过工作瞬间，峰值必须每 tick 采
        runtimeManager.samplePeaks();
        boolean published = runtimeManager.tick(lightEngine, getter, relighter, runtimeRegionChunks(), runtimeHaloChunks(),
                LuxConfig.enableSky, LuxConfig.enableBlock);
        // The commit itself still happens on the light thread; waiting for it here only moves it inside the tick,
        // which is where a block edit's light work belongs (it is what the game's own light pipeline does for the
        // changes it handles). Bounded in the publisher, so a busy light thread degrades to the async path.
        if (published && LuxFlags.syncRuntimeDrain) {
            ((dev.lucistarlink.light.runtime.LuxLightPublisher) lightEngine).lucistarlink$drainInline();
        }
    }

    public List<LuxRelightResult> relightRegion(LightChunkGetter getter, ChunkPos anchorChunk) {
        return relighter.relightRegion(getter, anchorChunk, LuxConfig.enableSky, LuxConfig.enableBlock,
                LuxConfig.regionChunks, LuxConfig.haloChunks);
    }

    /**
     * Whether a chunk being written to disk must be marked as "light not correct", so the game relights it on
     * load instead of trusting light data that the engine may still change.
     *
     * <p>Two reasons to force it: the conservative {@code forceLightIncorrectOnSave} mode (everything relights
     * on load, like ScalableLux ships, which also covers cross-chunk borders of chunks generated after the
     * neighbour was written), or the precise case where this chunk's region still has queued or in-flight work.
     */
    public boolean shouldRelightOnLoad(Level level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (closed || !LuxConfig.enabled) {
            return false;
        }
        if (LuxConfig.forceLightIncorrectOnSave) {
            LuxBenchmarkSupport.count("lucistarlink.save.forcedLightIncorrect.global");
            return true;
        }
        if (!LuxConfig.enableRuntime && !LuxConfig.enableWorldgen) {
            return false;
        }
        ChunkPos pos = chunk.getPos();
        if (runtimeManager.hasPendingWorkFor(pos.x, pos.z)) {
            LuxBenchmarkSupport.count("lucistarlink.save.forcedLightIncorrect.pendingWork");
            return true;
        }
        return false;
    }

    /** Same one-line state as the periodic telemetry, for the /lucistarlink command. */
    public String statusReport() {
        return runtimeManager.statusLine();
    }

    public void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        runtimeBulkScope.remove();
        worldgenWriteScope.reset();
        runtimeManager.close();
        worldgenWorkers.shutdownNow();
    }

    public boolean hasPendingRuntimeWork() {
        return runtimeManager.hasPendingWork();
    }

    public boolean hasPendingWorldgenWork() {
        return pendingWorldgenTasks.get() > 0;
    }

    public void beginWorldgenWrite() {
        worldgenWriteScope.begin();
        dev.lucistarlink.light.LuxFlags.worldgenWriting = worldgenWriteScope.anyOpen();
    }

    public void endWorldgenWrite() {
        worldgenWriteScope.end();
        dev.lucistarlink.light.LuxFlags.worldgenWriting = worldgenWriteScope.anyOpen();
    }

    private boolean isWorldgenWriteSuppressed() {
        return worldgenWriteScope.isActive();
    }

    private static int regionCount(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        int minRegionX = Math.floorDiv(minChunkX, runtimeRegionChunks());
        int maxRegionX = Math.floorDiv(maxChunkX, runtimeRegionChunks());
        int minRegionZ = Math.floorDiv(minChunkZ, runtimeRegionChunks());
        int maxRegionZ = Math.floorDiv(maxChunkZ, runtimeRegionChunks());
        return (maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1);
    }

    private static long regionKeyForBlock(BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        int originChunkX = Math.floorDiv(chunkX, runtimeRegionChunks()) * runtimeRegionChunks();
        int originChunkZ = Math.floorDiv(chunkZ, runtimeRegionChunks()) * runtimeRegionChunks();
        return RegionBounds.regionKey(originChunkX, originChunkZ);
    }

    private static final class RuntimeBulkScope {
        private final HashMap<Long, Long> regionChangeCounts = new HashMap<>();
        private int depth = 1;
        private long originalChangeCount;
        private boolean boundsRegistered;
        private long lastActivityNanos = System.nanoTime();

        private void touch() {
            lastActivityNanos = System.nanoTime();
        }

        private void record(BlockPos pos) {
            recordRegion(regionKeyForBlock(pos), 1);
        }

        private void recordRegion(long regionKey, long changes) {
            long count = Math.max(1L, changes);
            regionChangeCounts.merge(regionKey, count, Long::sum);
            originalChangeCount += count;
        }
    }

    private boolean runtimeBackpressureActive() {
        // A cleared or past deadline blocks nothing, so the clock is only read while backpressure is actually on.
        long until = runtimeBackpressureUntilNanos.get();
        return until != 0L && System.nanoTime() < until;
    }

    /** Zeroes an expired backpressure deadline so {@link #runtimeBackpressureActive} can answer without reading the clock. */
    private void refreshRuntimeBackpressure() {
        long until = runtimeBackpressureUntilNanos.get();
        if (until != 0L && System.nanoTime() >= until) {
            runtimeBackpressureUntilNanos.set(0L);
        }
    }

    private void activateRuntimeBackpressure() {
        LuxBenchmarkSupport.count("lucistarlink.runtime.backpressure");
        runtimeBackpressureUntilNanos.set(System.nanoTime() + 250_000_000L);
    }

    private boolean isSablePlotChunk(LightChunkGetter getter, ChunkPos chunkPos) {
        return LuxCompat.isSablePlotChunk(getter, chunkPos);
    }

    private static int worldgenWorkerCount() {
        int available = Runtime.getRuntime().availableProcessors();
        int configured = Integer.getInteger("lucistarlink.worldgenWorkers", 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Math.min(available - 1, 12));
    }

    private static final class LuxWorldgenThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lucistarlink-worldgen-light-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
