package ca.spottedleaf.starlight.common.light.vanillainterface;

import ca.spottedleaf.starlight.common.debug.LuxProfiler;
import ca.spottedleaf.starlight.common.integration.v0.ChunkSystemHooks;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ThreadedLevelLightEngineVanillaInterface extends ThreadedLevelLightEngine implements StarLightLightingProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ThreadedLevelLightEngineVanillaInterface.class);

    protected final StarLightInterface lightEngine;
    protected final LongOpenHashSet lightingEnabledChunks = new LongOpenHashSet();
    protected final Long2ObjectOpenHashMap<SWMRNibbleArray[]> blockLightMap = new Long2ObjectOpenHashMap<>();
    protected final Long2ObjectOpenHashMap<SWMRNibbleArray[]> skyLightMap = new Long2ObjectOpenHashMap<>();

    private final Long2IntOpenHashMap scalablelux$chunksBeingWorkedOn = new Long2IntOpenHashMap();

    // --- Lucis idea A: batch a bulk edit's per-change bookkeeping -------------
    // A bulk write (structure paste, /fill, worldedit) calls checkBlock once per block, but nearly all
    // of those calls share one section and repeat the same work: a chunk lookup, the "is this chunk lit"
    // checks, a lambda allocation, and the per-chunk ticket path. Measured on structure_cube: 666 ns per
    // call, 2.7 ms of an 8.7 ms pass, while the actual propagation is only ~0.9 ms (docs/PORT-LUCIS-IDEAS.md §5).
    //
    // Light results cannot be observed between enqueue and flush: the queue only *records* positions, the
    // propagation happens in runLightUpdates/propagateChanges, and every method that can observe or drain
    // the queue flushes first (see lucis$flushBatch call sites). Order is preserved - a batch for a new
    // section is flushed before the new section's first position is buffered.
    // Default OFF: the first cut (batch the scheduling checks) measured neutral - 0.9954 at n=3+3 on
    // structure_cube, and the counters show why (the queue insert itself dominates, not the checks).
    // The switch stays so the next iteration can be A/B'd against it without a rebuild.
    private static final int LUCIS_BATCH_LIMIT = Integer.getInteger("scalablelux.batchLimit", 1);

    private long lucis$batchSection = Long.MIN_VALUE;
    private final ObjectArrayList<BlockPos> lucis$batchPositions = new ObjectArrayList<>();

    public ThreadedLevelLightEngineVanillaInterface(
            final LightChunkGetter lightChunkGetter,
            final ChunkMap chunkMap,
            final boolean hasSkyLight,
            final ProcessorMailbox<Runnable> processorMailbox,
            final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> processorHandle
    ) {
        super(lightChunkGetter, chunkMap, hasSkyLight, processorMailbox, processorHandle);

        // avoid ClassCastException in cases where custom LightChunkGetters do not return a Level from getLevel()
        if (lightChunkGetter.getLevel() instanceof Level) {
            this.lightEngine = new StarLightInterface(lightChunkGetter, hasSkyLight, true, this);
        } else {
            this.lightEngine = new StarLightInterface(null, hasSkyLight, true, this);
        }
    }

    private static final int LUCIS_QUEUE_DROPPED = 0;
    private static final int LUCIS_QUEUE_ENQUEUED = 1;
    private static final int LUCIS_QUEUE_DEFERRED = 2;

    private int scalablelux$queueTaskForSection(final int chunkX, final int chunkY, final int chunkZ,
                                                 final Supplier<StarLightInterface.LightQueue.ChunkTasks> runnable) {
        if (LuxProfiler.enabled()) {
            LuxProfiler.queueTaskCalls++;
        }
        final ServerLevel world = (ServerLevel)this.scalablelux$getLightEngine().getWorld();

        final ChunkAccess center = this.scalablelux$getLightEngine().getAnyChunkNow(chunkX, chunkZ);
        if (center == null || !center.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)) {
            // do not accept updates in unlit chunks, unless we might be generating a chunk. thanks to the amazing
            // chunk scheduling, we could be lighting and generating a chunk at the same time
            if (LuxProfiler.enabled()) {
                LuxProfiler.queueTaskNotReady++;
            }
            return LUCIS_QUEUE_DROPPED;
        }

        if (!ChunkSystemHooks.isNonFullTicket() && center.getPersistedStatus() != ChunkStatus.FULL) { // TODO check if getHighestGeneratedStatus() is a better idea
            // do not keep chunk loaded, we are probably in a gen thread
            // if we proceed to add a ticket the chunk will be loaded, which is not what we want (avoid cascading gen)
            if (LuxProfiler.enabled()) {
                LuxProfiler.queueTaskInline++;
            }
            runnable.get();
            return LUCIS_QUEUE_ENQUEUED;
        }

        if (!ChunkSystemHooks.isTicketThreadSafe() && !world.getChunkSource().chunkMap.mainThreadExecutor.isSameThread()) {
            // ticket logic is not safe to run off-main, re-schedule
            if (LuxProfiler.enabled()) {
                LuxProfiler.queueTaskRescheduled++;
            }
            world.getChunkSource().chunkMap.mainThreadExecutor.execute(() -> {
                this.scalablelux$queueTaskForSection(chunkX, chunkY, chunkZ, runnable);
            });
            return LUCIS_QUEUE_DEFERRED;
        }

        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);

        final StarLightInterface.LightQueue.ChunkTasks updateFuture = runnable.get();

        if (updateFuture == null) {
            // not scheduled
            if (LuxProfiler.enabled()) {
                LuxProfiler.queueTaskNotScheduled++;
            }
            return LUCIS_QUEUE_DROPPED;
        }

        if (updateFuture.isTicketAdded) {
            // ticket already added
            if (LuxProfiler.enabled()) {
                LuxProfiler.queueTaskAlreadyAdded++;
            }
            return LUCIS_QUEUE_ENQUEUED;
        }
        updateFuture.isTicketAdded = true;
        if (LuxProfiler.enabled()) {
            LuxProfiler.queueTaskTicketAdds++;
        }

        final int references;
        synchronized (this.scalablelux$chunksBeingWorkedOn) {
            references = this.scalablelux$chunksBeingWorkedOn.addTo(key, 1);
        }
        if (references == 0) {
            final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            ChunkSystemHooks.addLightTicket(world, pos);
        }

        Consumer<Void> cleanup = (final Void ignore) -> {
            synchronized (this.scalablelux$chunksBeingWorkedOn) {
                final int newReferences = this.scalablelux$chunksBeingWorkedOn.addTo(key, -1);
                if (newReferences == 1) {
                    this.scalablelux$chunksBeingWorkedOn.remove(key);

                    // ticket rm inside synchronized to avoid a race
                    final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                    ChunkSystemHooks.removeLightTicket(world, pos);
                }
            }
        };
        CompletableFuture<Void> future;
        if (ChunkSystemHooks.isTicketThreadSafe()) {
            future = updateFuture.onComplete.thenAccept(cleanup);
        } else {
            future = updateFuture.onComplete.thenAcceptAsync(cleanup, world.getChunkSource().chunkMap.mainThreadExecutor);
        }
        future.whenComplete((final Void ignore, final Throwable thr) -> {
            if (thr != null) {
                LOGGER.error("Failed to remove ticket level for post chunk task " + new ChunkPos(chunkX, chunkZ), thr);
            }
        });
        return LUCIS_QUEUE_ENQUEUED;
    }

    @Override
    public void checkBlock(BlockPos pos) {
        // Redirect scheduling call away from the vanilla light engine, as well as enforce
        // that chunk neighbors are loaded before the processing can occur

        if (LUCIS_BATCH_LIMIT <= 1) { // batching off: one change, one full scheduling pass
            this.lucis$queueOne(pos);
            return;
        }
        if (LuxProfiler.enabled() && LuxProfiler.sample()) {
            LuxProfiler.checkBlockCalls++;
            final long t0 = System.nanoTime();
            this.lucis$buffer(pos);
            LuxProfiler.checkBlockSampled++;
            LuxProfiler.checkBlockSampledNanos += System.nanoTime() - t0;
            LuxProfiler.maybePrint();
            return;
        }
        if (LuxProfiler.enabled()) {
            LuxProfiler.checkBlockCalls++;
        }
        this.lucis$buffer(pos);
    }

    private void lucis$queueOne(final BlockPos pos) {
        final BlockPos posCopy = pos.immutable();
        this.scalablelux$queueTaskForSection(posCopy.getX() >> 4, posCopy.getY() >> 4, posCopy.getZ() >> 4, () -> {
            return this.lightEngine.blockChange(posCopy);
        });
    }

    private void lucis$buffer(final BlockPos pos) {
        final long section = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (section != this.lucis$batchSection) {
            // a new section: everything buffered so far is older, so it goes first (order preserved)
            this.lucis$flushBatch();
            this.lucis$batchSection = section;
        }
        this.lucis$batchPositions.add(pos.immutable());
        if (this.lucis$batchPositions.size() >= LUCIS_BATCH_LIMIT) {
            this.lucis$flushBatch();
        }
    }

    /**
     * Enqueues every buffered position. The first one takes the full scheduling path (chunk lookup, lit
     * checks, ticket bookkeeping, queue call); the rest are in the same section - and therefore the same
     * chunk column with the same answer to those checks - so they only need the queue call, which is where
     * the per-change cost collapses.
     */
    private void lucis$flushBatch() {
        final int size = this.lucis$batchPositions.size();
        if (size == 0) {
            return;
        }
        final BlockPos first = this.lucis$batchPositions.get(0);
        final int result = this.scalablelux$queueTaskForSection(first.getX() >> 4, first.getY() >> 4, first.getZ() >> 4, () -> {
            return this.lightEngine.blockChange(first);
        });
        if (result == LUCIS_QUEUE_ENQUEUED) {
            for (int i = 1; i < size; i++) {
                this.lightEngine.blockChange(this.lucis$batchPositions.get(i));
            }
        } else if (result == LUCIS_QUEUE_DEFERRED) {
            // the first change had to bounce to another thread, so the rest must not be enqueued here
            for (int i = 1; i < size; i++) {
                final BlockPos pos = this.lucis$batchPositions.get(i);
                this.scalablelux$queueTaskForSection(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, () -> {
                    return this.lightEngine.blockChange(pos);
                });
            }
        }
        this.lucis$batchPositions.clear();
    }

    @Override
    public boolean hasLightWork() {
        this.lucis$flushBatch();
        return CommonLightEngineUtils.hasLightWork(this);
    }

    @Override
    public int runLightUpdates() {
        this.lucis$flushBatch();
        return CommonLightEngineUtils.runLightUpdates(this);
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean sectionEmpty) {
        // Redirect to schedule for our own logic, as well as ensure 1 radius neighbors are loaded
        // Note: Our scheduling logic will discard this call if the chunk is not lit, unloaded, or not at LIGHT stage yet.

        if (LuxProfiler.enabled()) {
            LuxProfiler.sectionStatusCalls++;
        }
        this.lucis$flushBatch();
        this.scalablelux$queueTaskForSection(pos.getX(), pos.getY(), pos.getZ(), () -> {
            return this.lightEngine.sectionChange(pos, sectionEmpty);
        });
    }

    @Override
    public void setLightEnabled(ChunkPos pos, boolean enable) {
        // Avoid messing with the vanilla light engine state
        // light impl does not need to do this
        this.lucis$flushBatch();
    }

    @Override
    public void propagateLightSources(ChunkPos pos) {
        // Avoid messing with the vanilla light engine state

        // handled by light()
    }

    @Override
    public LayerLightEventListener getLayerListener(LightLayer layer) {
        return CommonLightEngineUtils.getLayerListener(this, layer);
    }

    @Override
    public String getDebugData(LightLayer layer, SectionPos pos) {
        return CommonLightEngineUtils.getDebugData(this, layer, pos);
    }

    @Override
    public LayerLightSectionStorage.SectionType getDebugSectionType(LightLayer layer, SectionPos pos) {
        return CommonLightEngineUtils.getDebugSectionType(this, layer, pos);
    }

    @Override
    public void queueSectionData(LightLayer layer, SectionPos pos, @Nullable DataLayer data) {
        // Light data is now attached to chunks, and this means we need to hook into chunk loading logic
        // to load the data rather than rely on this call. This call also would mess with the vanilla light engine state.

        // load hooks inside ChunkSerializer
    }

    @Override
    public void retainData(ChunkPos pos, boolean retain) {
        // Avoid messing with the vanilla light engine state

        // light impl does not need to do this
    }

    @Override
    public int getRawBrightness(BlockPos pos, int skyDampen) {
        return CommonLightEngineUtils.getRawBrightness(this, pos, skyDampen);
    }

    @Override
    public boolean lightOnInSection(SectionPos sectionPos) {
        return CommonLightEngineUtils.lightOnInSection(this, sectionPos);
    }

    @Override
    public int getLightSectionCount() {
        return super.getLightSectionCount(); // use vanilla impl
    }

    @Override
    public int getMinLightSection() {
        return super.getMinLightSection(); // use vanilla impl
    }

    @Override
    public int getMaxLightSection() {
        return super.getMaxLightSection(); // use vanilla impl
    }

    @Override
    public void updateSectionStatus(BlockPos pos, boolean sectionEmpty) {
        super.updateSectionStatus(pos, sectionEmpty); // use vanilla impl
    }

    @Override
    public StarLightInterface scalablelux$getLightEngine() {
        return this.lightEngine;
    }

    @Override
    public LongOpenHashSet scalablelux$getLightingEnabledChunks() {
        return this.lightingEnabledChunks;
    }

    @Override
    public Long2ObjectOpenHashMap<SWMRNibbleArray[]> scalablelux$getBlockLightMap() {
        return this.blockLightMap;
    }

    @Override
    public Long2ObjectOpenHashMap<SWMRNibbleArray[]> scalablelux$getSkyLightMap() {
        return this.skyLightMap;
    }

    @Override
    public void close() {
        this.lucis$flushBatch();
        LuxProfiler.print();
        super.close();
    }

    @Override
    protected void updateChunkStatus(ChunkPos pos) {
        // Avoid messing with the vanilla light engine state
    }

    @Override
    public CompletableFuture<ChunkAccess> initializeLight(ChunkAccess chunk, boolean lighted) {
        // Starlight does not have to do this

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean lit) {
        // Route to new logic to either light or just load the data
        this.lucis$flushBatch();

        final ChunkPos chunkPos = chunk.getPos();

        return CompletableFuture.supplyAsync(() -> {
            final Boolean[] emptySections = StarLightEngine.getEmptySectionsForChunk(chunk);
            if (!lit) {
                chunk.setLightCorrect(false);
                this.scalablelux$getLightEngine().lightChunk(chunk, emptySections);
                chunk.setLightCorrect(true);
            } else {
                this.scalablelux$getLightEngine().forceLoadInChunk(chunk, emptySections);
                // can't really force the chunk to be edged checked, as we need neighbouring chunks - but we don't have
                // them, so if it's not loaded then i guess we can't do edge checks. later loads of the chunk should
                // catch what we miss here.
                this.scalablelux$getLightEngine().checkChunkEdges(chunkPos.x, chunkPos.z);
            }

//            this.chunkMap.releaseLightTicket(chunkPos); // vanilla 1.21 no longer does this
            return chunk;
        }, (runnable) -> {
            this.scalablelux$getLightEngine().scheduleChunkLight(chunkPos, runnable);
            this.tryScheduleUpdate();
        }).whenComplete((final ChunkAccess c, final Throwable throwable) -> {
            if (throwable != null) {
                LOGGER.error("Failed to light chunk " + chunkPos, throwable);
            }
        });
    }

    @Override
    public void tryScheduleUpdate() {
        super.tryScheduleUpdate();
    }

    @Override
    public CompletableFuture<?> waitForPendingTasks(int chunkX, int chunkZ) {
        this.lucis$flushBatch();
        return this.lightEngine.syncFuture(chunkX, chunkZ);
    }
}
