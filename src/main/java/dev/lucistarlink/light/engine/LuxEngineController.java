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
    private static final int RUNTIME_REGION_CHUNKS = Math.max(1, Math.min(Integer.getInteger("lucistarlink.runtimeRegionChunks", 1), 16));

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
            Integer.getInteger("lucistarlink.maxInflightWorldgenTasks", Math.max(2, worldgenWorkerCount() * 2)));

    private final LightMaterialCache materialCache = new LightMaterialCache();
    private final LuxRelighter relighter = new LuxRelighter(materialCache, new LuxRegionExtractor(materialCache));
    private final LuxRuntimeManager runtimeManager = new LuxRuntimeManager();
    private final ExecutorService worldgenWorkers = Executors.newFixedThreadPool(worldgenWorkerCount(), new LuxWorldgenThreadFactory());
    private final AtomicInteger pendingWorldgenTasks = new AtomicInteger();
    private final AtomicInteger inflightWorldgenTasks = new AtomicInteger();
    private final ThreadLocal<Integer> worldgenWriteDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<RuntimeBulkScope> runtimeBulkScope = new ThreadLocal<>();
    private final AtomicLong runtimeBackpressureUntilNanos = new AtomicLong();
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
        Integer override = Integer.getInteger("lucistarlink.runtimeHaloChunks");
        int configured = override != null ? override : LuxConfig.runtimeHaloChunks;
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

        RegionLightData data;
        long extractStartedAt = LuxBenchmarkSupport.start();
        data = relighter.extractChunkData(getter, chunk, 1, 1);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.worldgen.extract", extractStartedAt);

        if (!tryReserveWorldgenSlot()) {
            LuxBenchmarkSupport.count("lucistarlink.worldgen.inflight.inlineFallback");
            return CompletableFuture.completedFuture(computeWorldgenLight(chunkPos, data, 0L, getter));
        }

        pendingWorldgenTasks.incrementAndGet();
        long submittedAt = LuxBenchmarkSupport.start();
        CompletableFuture<LuxRelightResult> future;
        try {
            future = CompletableFuture.supplyAsync(() -> computeWorldgenLight(chunkPos, data, submittedAt, getter), worldgenWorkers);
        } catch (RejectedExecutionException rejected) {
            releaseWorldgenSlot();
            pendingWorldgenTasks.decrementAndGet();
            return CompletableFuture.completedFuture(computeWorldgenLight(chunkPos, data, 0L, getter));
        }
        return future.whenComplete((result, throwable) -> {
            releaseWorldgenSlot();
            pendingWorldgenTasks.decrementAndGet();
        });
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

    public LuxRelightResult relightAtBlock(LightChunkGetter getter, BlockPos pos) {
        if (!shouldHandleBlockChange(getter, pos)) {
            return new LuxRelightResult(new ChunkPos(pos), java.util.List.of());
        }

        LightChunk chunk = getter.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk instanceof ChunkAccess chunkAccess) {
            return relightChunk(getter, chunkAccess, true);
        }
        return new LuxRelightResult(new ChunkPos(pos), java.util.List.of());
    }

    public boolean shouldHandleBlockChange(BlockPos pos) {
        return enabled()
                && LuxConfig.enableRuntime
                && pos != null
                && !isWorldgenWriteSuppressed()
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
        if (!shouldHandleBlockChange(level, pos)) {
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
        return true;
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
        int minRegionChunkX = Math.floorDiv(minChunkX, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        int maxRegionChunkX = Math.floorDiv(maxChunkX, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        int minRegionChunkZ = Math.floorDiv(minChunkZ, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        int maxRegionChunkZ = Math.floorDiv(maxChunkZ, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        for (int regionChunkZ = minRegionChunkZ; regionChunkZ <= maxRegionChunkZ; regionChunkZ += RUNTIME_REGION_CHUNKS) {
            for (int regionChunkX = minRegionChunkX; regionChunkX <= maxRegionChunkX; regionChunkX += RUNTIME_REGION_CHUNKS) {
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
        if (!enabled() || !LuxConfig.enableRuntime) {
            runtimeManager.flushPendingCommits(lightEngine);
            return;
        }
        reapStaleBulkScope();
        runtimeManager.tick(lightEngine, getter, relighter, RUNTIME_REGION_CHUNKS, runtimeHaloChunks(),
                LuxConfig.enableSky, LuxConfig.enableBlock);
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
        worldgenWriteDepth.remove();
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
        worldgenWriteDepth.set(worldgenWriteDepth.get() + 1);
    }

    public void endWorldgenWrite() {
        int depth = worldgenWriteDepth.get() - 1;
        if (depth <= 0) {
            worldgenWriteDepth.remove();
        } else {
            worldgenWriteDepth.set(depth);
        }
    }

    private boolean isWorldgenWriteActive() {
        return worldgenWriteDepth.get() > 0;
    }

    private boolean isWorldgenWriteSuppressed() {
        return isWorldgenWriteActive();
    }

    private static int regionCount(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        int minRegionX = Math.floorDiv(minChunkX, RUNTIME_REGION_CHUNKS);
        int maxRegionX = Math.floorDiv(maxChunkX, RUNTIME_REGION_CHUNKS);
        int minRegionZ = Math.floorDiv(minChunkZ, RUNTIME_REGION_CHUNKS);
        int maxRegionZ = Math.floorDiv(maxChunkZ, RUNTIME_REGION_CHUNKS);
        return (maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1);
    }

    private static long regionKeyForBlock(BlockPos pos) {
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        int originChunkX = Math.floorDiv(chunkX, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        int originChunkZ = Math.floorDiv(chunkZ, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
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
        return System.nanoTime() < runtimeBackpressureUntilNanos.get();
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
