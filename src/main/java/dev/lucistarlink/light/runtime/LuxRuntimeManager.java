package dev.lucistarlink.light.runtime;

import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.config.LuxConfig;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.engine.LuxRelighter;
import dev.lucistarlink.light.region.OwnedRegionCache;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionOwnerTable;
import dev.lucistarlink.light.region.RuntimeRegionState;
import dev.lucistarlink.light.runtime.RuntimeRelightOutcome;
import dev.lucistarlink.test.LuxBenchmarkSupport;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class LuxRuntimeManager implements AutoCloseable {
    private static final int MAX_RUNTIME_REGION_SUBMITS_PER_TICK = Integer.getInteger("lucistarlink.runtime.maxSubmitsPerTick", 0);
    private static final int MAX_RUNTIME_PENDING_RECORDS = Integer.getInteger("lucistarlink.runtime.maxPendingRecords", 131_072);
    private static final long FULL_RELIGHT_COALESCE_NANOS = Long.getLong("lucistarlink.runtime.fullRelightCoalesceNanos", 5_000_000_000L);
    private static final int RUNTIME_REGION_CHUNKS = Math.max(1, Math.min(Integer.getInteger("lucistarlink.runtimeRegionChunks", 1), 16));

    /**
     * Coalescing is a short-lived dedup window, so its bookkeeping must not outlive it:
     * expired entries are swept with a per-tick budget and the map is reset outright if a
     * bulk edit ever registers more regions than the cap. Losing an entry only costs an
     * extra incremental region job, never correctness.
     */
    private static final int MAX_RELIGHT_COALESCE_ENTRIES = Math.max(1024,
            Integer.getInteger("lucistarlink.runtime.maxCoalesceEntries", 65_536));
    private static final int RELIGHT_COALESCE_SWEEP_BUDGET = Math.max(256,
            Integer.getInteger("lucistarlink.runtime.coalesceSweepBudget", 8_192));
    /** Batches at or below this many change records are executed inline on the tick thread. */
    private static final int INLINE_BATCH_MAX_CHANGES = Integer.getInteger("lucistarlink.runtime.inlineBatchChanges", 8);
    private static final long MEMORY_TELEMETRY_INTERVAL_NANOS =
            Long.getLong("lucistarlink.runtime.memoryTelemetryNanos", 30_000_000_000L);

    // With dense-incremental routing, record-backed batches never degrade into full
    // relights; only the explicit bulk-write path (which carries no records) does.
    private final int fullRelightChangeThreshold = LuxFlags.denseIncremental
            ? Integer.MAX_VALUE
            : Math.max(1, Integer.getInteger("lucistarlink.runtime.fullRelightChangeThreshold", 2048));

    private final RuntimeUpdateQueue updateQueue = new RuntimeUpdateQueue(MAX_RUNTIME_PENDING_RECORDS, fullRelightChangeThreshold);
    private final RegionOwnerTable ownerTable = new RegionOwnerTable();
    private final OwnedRegionCache regionCache = new OwnedRegionCache();
    private final LuxScheduler scheduler = new LuxScheduler(runtimeWorkerCount());
    private final ConcurrentLinkedQueue<RuntimeCommit> commitQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ownerIds = new AtomicLong();
    private final HashMap<Long, RuntimeRegionBatch> drainedBatches = new HashMap<>();
    private final HashMap<Long, RuntimeRegionBatch> pendingBatchesByRegion = new HashMap<>();
    private final Object pendingBatchesLock = new Object();
    private final ConcurrentHashMap<Long, Long> fullRelightCoalesceUntil = new ConcurrentHashMap<>();
    private volatile long nextTelemetryNanos;
    private volatile boolean closed;

    public boolean enqueue(BlockChangeRecord record) {
        return record != null && enqueue(record.x(), record.y(), record.z(), record.oldState(), record.newState());
    }

    public boolean enqueue(int x, int y, int z, BlockState oldState, BlockState newState) {
        if (closed) {
            return false;
        }
        long regionKey = regionKey(x >> 4, z >> 4, RUNTIME_REGION_CHUNKS);
        if (isFullRelightCoalescing(regionKey)) {
            if (!updateQueue.enqueueFullRelight(regionKey, 1)) {
                LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.coalesceTableFull");
                return false;
            }
            markFullRelightCoalescing(regionKey);
            return true;
        }
        boolean accepted = updateQueue.enqueue(regionKey, x, y, z, oldState, newState);
        if (accepted && updateQueue.hasFullRelight(regionKey)) {
            markFullRelightCoalescing(regionKey);
        }
        return accepted;
    }

    public boolean enqueueFullRelight(long regionKey, long originalChangeCount) {
        if (closed) {
            return false;
        }
        if (!updateQueue.enqueueFullRelight(regionKey, Math.max(1, originalChangeCount))) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.coalesceTableFull");
            return false;
        }
        markFullRelightCoalescing(regionKey);
        return true;
    }

    public void enqueueBoundaryDeltas(long regionKey, long[] deltas) {
        if (closed || deltas == null || deltas.length == 0) {
            return;
        }
        updateQueue.enqueueBoundaryDeltas(regionKey, deltas);
    }

    public boolean canAcceptMoreWork() {
        return !closed && updateQueue.hasCapacity();
    }

    public void tick(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LuxRelighter relighter,
                     int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            return;
        }
        scheduleQueuedRegions(lightEngine, getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
        flushCommits(lightEngine);
        regionCache.trimToSize(LuxConfig.maxCachedRegions, LuxConfig.maxCachedRegionBytes);
        sweepExpiredCoalescingEntries();
        logMemoryTelemetry();
    }

    /**
     * True when the engine may still change this chunk's light: its region has queued changes, a job for it is
     * in flight, or a computed result is waiting for the next tick to be published. Used by the save hook to
     * decide whether the saved light data can be trusted - a chunk saved while work is pending would otherwise
     * come back with that light permanently missing (nothing relights a light-correct chunk on load).
     */
    public boolean hasPendingWorkFor(int chunkX, int chunkZ) {
        if (closed) {
            return false;
        }
        long regionKey = regionKey(chunkX, chunkZ, RUNTIME_REGION_CHUNKS);
        if (updateQueue.hasRegion(regionKey) || scheduledRegions.contains(regionKey)) {
            return true;
        }
        synchronized (pendingBatchesLock) {
            if (pendingBatchesByRegion.containsKey(regionKey)) {
                return true;
            }
        }
        for (RuntimeCommit commit : commitQueue) {
            if (commit.result().isForChunk(chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verbose-only view of the structures that hold memory in this manager, so a server admin can watch
     * them stay bounded instead of guessing. The region cache is the big one: every entry pins four byte
     * planes of the region volume (which is why it is trimmed by bytes, not by entry count).
     */
    /** One-line engine status, shared by the periodic log and the /lucistarlink status command. */
    public String statusLine() {
        int pendingBatchCount;
        synchronized (pendingBatchesLock) {
            pendingBatchCount = pendingBatchesByRegion.size();
        }
        return String.format(java.util.Locale.ROOT,
                "region cache %d/%d regions (%d/%d MiB), coalescing entries %d, queued changes %d, queued regions %d, "
                        + "pending batches %d, commits %d, scheduled %d; halo sections published %d, external sections "
                        + "marked %d/refreshed %d, worldgen neighbour-stale marked %d, baseline re-runs %d, "
                        + "adopted-batch changes %d, save forced "
                        + "light-incorrect: pending %d global %d",
                regionCache.size(), LuxConfig.maxCachedRegions,
                regionCache.cachedBytes() >> 20, LuxConfig.maxCachedRegionBytes >> 20,
                fullRelightCoalesceUntil.size(),
                updateQueue.pendingRecordCount(), updateQueue.regionCount(),
                pendingBatchCount, commitQueue.size(), scheduledRegions.size(),
                LuxBenchmarkSupport.countValue("lucistarlink.publish.halo.sections"),
                LuxBenchmarkSupport.countValue("lucistarlink.runtime.region.externalMarked.sections"),
                LuxBenchmarkSupport.countValue("lucistarlink.runtime.region.externalRefresh.sections"),
                LuxBenchmarkSupport.countValue("lucistarlink.worldgen.neighbourStale.marked"),
                LuxBenchmarkSupport.countValue("lucistarlink.runtime.jobs.rerunBaselineMoved"),
                LuxBenchmarkSupport.countValue("lucistarlink.runtime.init.adoptedChanges"),
                LuxBenchmarkSupport.countValue("lucistarlink.save.forcedLightIncorrect.pendingWork"),
                LuxBenchmarkSupport.countValue("lucistarlink.save.forcedLightIncorrect.global"));
    }


    private void logMemoryTelemetry() {
        if (!LuxConfig.verboseLogging) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextTelemetryNanos) {
            return;
        }
        nextTelemetryNanos = now + MEMORY_TELEMETRY_INTERVAL_NANOS;
        int pendingBatchCount;
        synchronized (pendingBatchesLock) {
            pendingBatchCount = pendingBatchesByRegion.size();
        }
        LuciStarlink.LOGGER.info("LuciStarlink memory: {}", statusLine());
    }

    /**
     * Publishes results that were already computed. Called when runtime updates are switched off, so those
     * commits release their chunk references instead of sitting in the queue forever (nothing would drain
     * them once {@link #tick} stops being invoked).
     */
    public void flushPendingCommits(ThreadedLevelLightEngine lightEngine) {
        if (!commitQueue.isEmpty()) {
            flushCommits(lightEngine);
        }
    }

    public boolean hasPendingWork() {
        return !updateQueue.isEmpty()
                || !commitQueue.isEmpty()
                || !scheduledRegions.isEmpty()
                || scheduler.hasPendingWork();
    }

    private void scheduleQueuedRegions(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LuxRelighter relighter,
                                       int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (updateQueue.isEmpty() && pendingBatchesEmpty()) {
            return;
        }

        drainedBatches.clear();
        int drained = updateQueue.drainTo(drainedBatches);
        LuxBenchmarkSupport.count("lucistarlink.runtime.drain.records", drained);
        int scheduled = 0;
        int maxSubmits = runtimeSubmitBudget();
        ArrayList<ScheduledRegionBatch> selected = new ArrayList<>(Math.min(maxSubmits, drainedBatches.size() + 1));
        synchronized (pendingBatchesLock) {
            for (Map.Entry<Long, RuntimeRegionBatch> entry : drainedBatches.entrySet()) {
                pendingBatchesByRegion.merge(entry.getKey(), entry.getValue(), LuxRuntimeManager::mergeBatches);
            }
            drainedBatches.clear();
            LuxBenchmarkSupport.count("lucistarlink.runtime.drain.regions", pendingBatchesByRegion.size());

            Iterator<Map.Entry<Long, RuntimeRegionBatch>> iterator = pendingBatchesByRegion.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, RuntimeRegionBatch> entry = iterator.next();
                if (scheduled >= maxSubmits) {
                    LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.batchLimit");
                    break;
                }
                if (scheduledRegions.contains(entry.getKey())) {
                    continue;
                }
                RuntimeRegionBatch batch = entry.getValue();
                iterator.remove();
                selected.add(new ScheduledRegionBatch(entry.getKey(), batch));
                scheduled++;
            }
        }

        for (ScheduledRegionBatch batch : selected) {
            scheduleRegion(lightEngine, batch.regionKey(), batch.batch(), getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
        }
    }

    private boolean pendingBatchesEmpty() {
        synchronized (pendingBatchesLock) {
            return pendingBatchesByRegion.isEmpty();
        }
    }

    private void scheduleRegion(ThreadedLevelLightEngine lightEngine, long regionKey, RuntimeRegionBatch batch, LightChunkGetter getter,
                                LuxRelighter relighter, int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            requeue(regionKey, batch);
            return;
        }

        ChunkPos anchor = new ChunkPos(ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
        LightChunk coreChunk = getter.getChunkForLighting(anchor.x, anchor.z);
        if (coreChunk == null) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.missingChunk");
            requeue(regionKey, batch);
            return;
        }
        if (!scheduledRegions.add(regionKey)) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.alreadyScheduled");
            requeue(regionKey, batch);
            return;
        }

        long ownerId = ownerIds.incrementAndGet();
        if (!ownerTable.tryAcquire(regionKey, ownerId)) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.ownerBusy");
            scheduledRegions.remove(regionKey);
            requeue(regionKey, batch);
            return;
        }

        long prepStartedAt = LuxBenchmarkSupport.start();
        RegionBounds bounds = RegionBounds.around(anchor, getter.getLevel(), regionChunks, haloChunks);
        HashMap<Long, LightChunk> expectedChunks = captureExpectedChunks(getter, bounds);
        RuntimeRegionState ownedState = regionCache.getOrCreate(bounds);
        ownedState.touch();
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.jobPrep", prepStartedAt);
        boolean inlinePublish = LuxFlags.inlineRuntime;
        LuxBenchmarkSupport.count("lucistarlink.runtime.jobs.runtime.submit");
        LuxBenchmarkSupport.count(batch.fullRelight() ? "lucistarlink.runtime.jobs.fullRelight" : "lucistarlink.runtime.jobs.incremental");
        LuxBenchmarkSupport.count("lucistarlink.runtime.jobs.changeRecords", batch.queuedChangeCount());
        Runnable jobBody = () -> {
            long jobStartedAt = LuxBenchmarkSupport.start();
            try {
                RuntimeRelightOutcome outcome = relighter.relightRuntimeRegion(getter, ownedState, coreChunk,
                        batch, enableSky, enableBlock, this::enqueueBoundaryDeltas);
                if (closed) {
                    return;
                }
                if (outcome.baselineMoved()) {
                    // a neighbour published into these chunks while we computed: re-run rather than publish
                    // values derived from a stale baseline
                    requeue(regionKey, batch);
                    return;
                }
                long postComputeAt = LuxBenchmarkSupport.start();
                List<LuxRelightResult> results = outcome.results();
                LuxBenchmarkSupport.count("lucistarlink.runtime.jobs.results", results.size());
                if (outcome.haloTouched()) {
                    markExternalSections(results, bounds, ownedState);
                }
                if (inlinePublish) {
                    LuxLightPublisher publisher = (LuxLightPublisher) lightEngine;
                    for (LuxRelightResult result : results) {
                        LuxBenchmarkSupport.count("lucistarlink.runtime.jobs.sections", result.sections().size());
                        LightChunk expectedChunk = expectedChunks.get(ChunkPos.asLong(result.chunkPos().x, result.chunkPos().z));
                        if (expectedChunk == null) {
                            LuxBenchmarkSupport.count("lucistarlink.runtime.commit.skippedMissingExpectedChunk");
                            continue;
                        }
                        publisher.lucistarlink$publish(result, expectedChunk);
                    }
                    lightEngine.tryScheduleUpdate();
                    return;
                }
                for (LuxRelightResult result : results) {
                    LuxBenchmarkSupport.count("lucistarlink.runtime.jobs.sections", result.sections().size());
                    LightChunk expectedChunk = expectedChunks.get(ChunkPos.asLong(result.chunkPos().x, result.chunkPos().z));
                    if (expectedChunk == null) {
                        LuxBenchmarkSupport.count("lucistarlink.runtime.commit.skippedMissingExpectedChunk");
                        continue;
                    }
                    commitQueue.add(new RuntimeCommit(result, expectedChunk));
                }
            } finally {
                LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.job.runtime", jobStartedAt);
                ownerTable.release(regionKey, ownerId);
                scheduledRegions.remove(regionKey);
            }
        };

        // Small batches run inline on the calling (tick) thread: the per-edit pipeline cost (worker handoff,
        // commit queue, mailbox wakeup) dominates for tiny change sets, which is exactly the small-edit
        // workload class. Larger batches keep going to workers.
        if (!batch.fullRelight() && batch.queuedChangeCount() <= INLINE_BATCH_MAX_CHANGES) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.jobs.inlineExecuted");
            jobBody.run();
            return;
        }

        boolean submitted = scheduler.submit(new LuxJob(jobBody));
        if (!submitted) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.submitRefused");
            ownerTable.release(regionKey, ownerId);
            scheduledRegions.remove(regionKey);
            requeue(regionKey, batch);
        }
    }

    private void requeue(long regionKey, RuntimeRegionBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (batch.fullRelight()) {
            if (!updateQueue.enqueueFullRelight(regionKey, batch.originalChangeCount())) {
                LuxBenchmarkSupport.count("lucistarlink.runtime.requeued.fullRelightDropped");
                return;
            }
            markFullRelightCoalescing(regionKey);
        } else {
            updateQueue.enqueueAll(regionKey, batch.changes());
        }
    }

    /**
     * Tells every other region whose image overlaps a chunk this job published into that those sections changed
     * in the engine, so it re-reads them before using its image again. Without it such an image would keep
     * pre-publish values and could re-raise stale light across the border - the failure mode the old
     * cross-region delta prototype hit.
     *
     * <p>Only regions that are actually cached and initialized are marked, and only the exact sections that were
     * published, so the mark cost is proportional to the light that really moved.
     */
    private void markExternalSections(List<LuxRelightResult> results, RegionBounds bounds, RuntimeRegionState ownState) {
        if (!LuxFlags.haloPublish || bounds.haloChunks() <= 0) {
            return;
        }
        int halo = bounds.haloChunks();
        for (LuxRelightResult result : results) {
            ChunkPos chunkPos = result.chunkPos();
            if (result.sections().isEmpty()) {
                continue;
            }
            // every region image that covers this chunk: origins within halo chunks of it
            int minOriginX = Math.floorDiv(chunkPos.x - halo, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
            int maxOriginX = Math.floorDiv(chunkPos.x + halo, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
            int minOriginZ = Math.floorDiv(chunkPos.z - halo, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
            int maxOriginZ = Math.floorDiv(chunkPos.z + halo, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
            for (int originX = minOriginX; originX <= maxOriginX; originX += RUNTIME_REGION_CHUNKS) {
                for (int originZ = minOriginZ; originZ <= maxOriginZ; originZ += RUNTIME_REGION_CHUNKS) {
                    if (originX == bounds.originChunkX() && originZ == bounds.originChunkZ()) {
                        continue;
                    }
                    RuntimeRegionState owner = regionCache.getInitialized(RegionBounds.regionKey(originX, originZ));
                    if (owner == null || owner == ownState) {
                        continue;
                    }
                    for (LuxSectionData section : result.sections()) {
                        owner.markExternalSection(section.sectionPos().asLong(),
                                section.layer() == net.minecraft.world.level.LightLayer.SKY);
                    }
                    LuxBenchmarkSupport.count("lucistarlink.runtime.region.externalMarked.sections", result.sections().size());
                    LuxBenchmarkSupport.count("lucistarlink.runtime.region.externalMarked.regions");
                }
            }
        }
    }

    private HashMap<Long, LightChunk> captureExpectedChunks(LightChunkGetter getter, RegionBounds bounds) {
        int regionChunks = bounds.regionChunks();
        int haloChunks = LuxFlags.haloPublish ? bounds.haloChunks() : 0;
        // halo chunks are published too, so they need the same chunk identity check on publish as the core
        HashMap<Long, LightChunk> expectedChunks = new HashMap<>((regionChunks + haloChunks * 2) ^ 2);
        int minChunkX = bounds.originChunkX() - haloChunks;
        int minChunkZ = bounds.originChunkZ() - haloChunks;
        int maxChunkX = bounds.originChunkX() + regionChunks + haloChunks;
        int maxChunkZ = bounds.originChunkZ() + regionChunks + haloChunks;
        for (int chunkZ = minChunkZ; chunkZ < maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX < maxChunkX; chunkX++) {
                LightChunk chunk = getter.getChunkForLighting(chunkX, chunkZ);
                if (chunk != null) {
                    expectedChunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
                }
            }
        }
        return expectedChunks;
    }

    private boolean isFullRelightCoalescing(long regionKey) {
        Long until = fullRelightCoalesceUntil.get(regionKey);
        if (until == null) {
            return false;
        }
        if (System.nanoTime() <= until) {
            return true;
        }
        fullRelightCoalesceUntil.remove(regionKey, until);
        return false;
    }

    private void markFullRelightCoalescing(long regionKey) {
        fullRelightCoalesceUntil.put(regionKey, System.nanoTime() + FULL_RELIGHT_COALESCE_NANOS);
    }

    private void sweepExpiredCoalescingEntries() {
        int size = fullRelightCoalesceUntil.size();
        if (size == 0) {
            return;
        }
        if (size > MAX_RELIGHT_COALESCE_ENTRIES) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.coalesce.reset", size);
            fullRelightCoalesceUntil.clear();
            return;
        }
        long now = System.nanoTime();
        int budget = RELIGHT_COALESCE_SWEEP_BUDGET;
        Iterator<Map.Entry<Long, Long>> iterator = fullRelightCoalesceUntil.entrySet().iterator();
        while (budget-- > 0 && iterator.hasNext()) {
            if (now > iterator.next().getValue()) {
                iterator.remove();
            }
        }
    }

    private static RuntimeRegionBatch mergeBatches(RuntimeRegionBatch existing, RuntimeRegionBatch incoming) {
        if (existing.fullRelight() || incoming.fullRelight()) {
            return RuntimeRegionBatch.fullRelight(existing.originalChangeCount() + incoming.originalChangeCount());
        }
        ArrayList<BlockChangeRecord> merged = new ArrayList<>(existing.queuedChangeCount() + incoming.queuedChangeCount());
        merged.addAll(existing.changes());
        merged.addAll(incoming.changes());
        long[] deltas = existing.boundaryDeltas();
        long[] incomingDeltas = incoming.boundaryDeltas();
        if (deltas != null && incomingDeltas != null) {
            long[] combined = new long[deltas.length + incomingDeltas.length];
            System.arraycopy(deltas, 0, combined, 0, deltas.length);
            System.arraycopy(incomingDeltas, 0, combined, deltas.length, incomingDeltas.length);
            deltas = combined;
        } else if (incomingDeltas != null) {
            deltas = incomingDeltas;
        }
        return new RuntimeRegionBatch(merged, false, existing.originalChangeCount() + incoming.originalChangeCount(), deltas);
    }

    private void flushCommits(ThreadedLevelLightEngine lightEngine) {
        LuxLightPublisher publisher = (LuxLightPublisher) lightEngine;
        boolean any = false;
        RuntimeCommit commit;
        while ((commit = commitQueue.poll()) != null) {
            LuxRelightResult result = commit.result();
            any = true;
            LuxBenchmarkSupport.count("lucistarlink.runtime.commit.results");
            LuxBenchmarkSupport.count("lucistarlink.runtime.commit.sections", result.sections().size());
            publisher.lucistarlink$publish(result, commit.expectedChunk());
        }
        if (any) {
            lightEngine.tryScheduleUpdate();
        }
    }

    private long regionKey(int chunkX, int chunkZ, int regionChunks) {
        int originChunkX = Math.floorDiv(chunkX, regionChunks) * regionChunks;
        int originChunkZ = Math.floorDiv(chunkZ, regionChunks) * regionChunks;
        return RegionBounds.regionKey(originChunkX, originChunkZ);
    }

    private static int runtimeWorkerCount() {
        int configured = Integer.getInteger("lucistarlink.runtimeWorkers", 0);
        if (configured > 0) {
            return configured;
        }
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(available / 2, 4));
    }

    private static int runtimeSubmitBudget() {
        int configured = MAX_RUNTIME_REGION_SUBMITS_PER_TICK;
        if (configured > 0) {
            return Math.max(1, Math.min(LuxConfig.maxBatchChunks, configured));
        }
        return Math.max(1, LuxConfig.maxBatchChunks);
    }

    @Override
    public void close() {
        closed = true;
        scheduler.close();
        updateQueue.clear();
        commitQueue.clear();
        synchronized (pendingBatchesLock) {
            pendingBatchesByRegion.clear();
            drainedBatches.clear();
        }
        scheduledRegions.clear();
        ownerTable.clear();
        regionCache.clear();
        fullRelightCoalesceUntil.clear();
    }

    private record ScheduledRegionBatch(long regionKey, RuntimeRegionBatch batch) {
    }

    private record RuntimeCommit(LuxRelightResult result, LightChunk expectedChunk) {
    }
}
