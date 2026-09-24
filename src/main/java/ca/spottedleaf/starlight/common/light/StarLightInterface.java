package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.compat.SableCompat;
import ca.spottedleaf.starlight.common.debug.LuxProfiler;
import ca.spottedleaf.starlight.common.thread.GlobalExecutors;
import ca.spottedleaf.starlight.common.thread.SchedulingUtil;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import ca.spottedleaf.starlight.common.world.ExtendedWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongPriorityQueues;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

public final class StarLightInterface {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("LuciStarlink");

    /** How many burst-settling failures have been logged (see the catch in lucis$flushPendingEditsImpl). */
    private static long LUCIS_FALLBACK_LOGGED;

    public static final TicketType<ChunkPos> CHUNK_WORK_TICKET = TicketType.create("starlight_chunk_work_ticket", (p1, p2) -> Long.compare(p1.toLong(), p2.toLong()));

    /**
     * Can be {@code null}, indicating the light is all empty.
     */
    protected final Level world;
    protected final LightChunkGetter lightAccess;

    protected final ArrayDeque<SkyStarLightEngine> cachedSkyPropagators;
    protected final ArrayDeque<BlockStarLightEngine> cachedBlockPropagators;

    protected final LightQueue lightQueue;

    protected final LayerLightEventListener skyReader;
    protected final LayerLightEventListener blockReader;
    protected final boolean isClientSide;

    protected final int minSection;
    protected final int maxSection;
    protected final int minLightSection;
    protected final int maxLightSection;

    public final LevelLightEngine lightEngine;

    private final boolean hasBlockLight;
    private final boolean hasSkyLight;

    public StarLightInterface(final LightChunkGetter lightAccess, final boolean hasSkyLight, final boolean hasBlockLight, final LevelLightEngine lightEngine) {
        this.lightAccess = lightAccess;
        this.world = lightAccess == null ? null : (Level)lightAccess.getLevel();
        this.cachedSkyPropagators = hasSkyLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.isClientSide = !(this.world instanceof ServerLevel);
        if (this.world == null) {
            this.minSection = -4;
            this.maxSection = 19;
            this.minLightSection = -5;
            this.maxLightSection = 20;
        } else {
            this.minSection = WorldUtil.getMinSection(this.world);
            this.maxSection = WorldUtil.getMaxSection(this.world);
            this.minLightSection = WorldUtil.getMinLightSection(this.world);
            this.maxLightSection = WorldUtil.getMaxLightSection(this.world);
        }
        this.lightEngine = lightEngine;
        this.hasBlockLight = hasBlockLight;
        this.hasSkyLight = hasSkyLight;
        if (this.isClientSide || !GlobalExecutors.ENABLED) {
            this.lightQueue = new SimpleLightQueue(this);
        } else {
            this.lightQueue = new ConcurrentLightQueue(this);
        }
        this.skyReader = !hasSkyLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new LayerLightEventListener() {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void propagateLightSources(final ChunkPos chunkPos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runLightUpdates() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLightEnabled(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());
                if (chunk == null || (!StarLightInterface.this.isClientSide && !chunk.isLightCorrect())) {
                    return null;
                }

                final int sectionY = pos.getY();

                if (sectionY > StarLightInterface.this.maxLightSection || sectionY < StarLightInterface.this.minLightSection) {
                    return null;
                }

//                if (((ExtendedChunk)chunk).scalablelux$getSkyEmptinessMap() == null) {
//                    return null;
//                }

                return ((ExtendedChunk)chunk).scalablelux$getSkyNibbles()[sectionY - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getSkyLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
        this.blockReader = !hasBlockLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new LayerLightEventListener() {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void propagateLightSources(final ChunkPos chunkPos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runLightUpdates() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLightEnabled(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());

                if (chunk == null || pos.getY() < StarLightInterface.this.minLightSection || pos.getY() > StarLightInterface.this.maxLightSection) {
                    return null;
                }

                return ((ExtendedChunk)chunk).scalablelux$getBlockNibbles()[pos.getY() - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getBlockLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
    }

    public boolean hasSkyLight() {
        return this.hasSkyLight;
    }

    public boolean hasBlockLight() {
        return this.hasBlockLight;
    }

    public int getSkyLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasSkyLight) {
            return 0;
        }
        final int x = blockPos.getX();
        int y = blockPos.getY();
        final int z = blockPos.getZ();

        final int minSection = this.minSection;
        final int maxSection = this.maxSection;
        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (chunk == null || (!this.isClientSide && !chunk.isLightCorrect()) || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)) {
            return 15;
        }

        int sectionY = y >> 4;

        if (sectionY > maxLightSection) {
            return 15;
        }

        if (sectionY < minLightSection) {
            sectionY = minLightSection;
            y = sectionY << 4;
        }

        final SWMRNibbleArray[] nibbles = ((ExtendedChunk)chunk).scalablelux$getSkyNibbles();
        final SWMRNibbleArray immediate = nibbles[sectionY - minLightSection];

        if (!immediate.isNullNibbleVisible()) {
            return immediate.getVisible(x, y, z);
        }

        final boolean[] emptinessMap = ((ExtendedChunk)chunk).scalablelux$getSkyEmptinessMap();

        if (emptinessMap == null) {
            return 15;
        }

        // are we above this chunk's lowest empty section?
        int lowestY = minLightSection - 1;
        for (int currY = maxSection; currY >= minSection; --currY) {
            if (emptinessMap[currY - minSection]) {
                continue;
            }

            // should always be full lit here
            lowestY = currY;
            break;
        }

        if (sectionY > lowestY) {
            return 15;
        }

        // this nibble is going to depend solely on the skylight data above it
        // find first non-null data above (there does exist one, as we just found it above)
        for (int currY = sectionY + 1; currY <= maxLightSection; ++currY) {
            final SWMRNibbleArray nibble = nibbles[currY - minLightSection];
            if (!nibble.isNullNibbleVisible()) {
                return nibble.getVisible(x, 0, z);
            }
        }

        // should never reach here
        return 15;
    }

    public int getBlockLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasBlockLight) {
            return 0;
        }
        final int y = blockPos.getY();
        final int cy = y >> 4;

        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (cy < minLightSection || cy > maxLightSection) {
            return 0;
        }

        if (chunk == null) {
            return 0;
        }

        final SWMRNibbleArray nibble = ((ExtendedChunk)chunk).scalablelux$getBlockNibbles()[cy - minLightSection];
        return nibble.getVisible(blockPos.getX(), y, blockPos.getZ());
    }

    public int getRawBrightness(final BlockPos pos, final int ambientDarkness) {
        final ChunkAccess chunk = this.getAnyChunkNow(pos.getX() >> 4, pos.getZ() >> 4);

        final int sky = this.getSkyLightValue(pos, chunk) - ambientDarkness;
        // Don't fetch the block light level if the skylight level is 15, since the value will never be higher.
        if (sky == 15) {
            return 15;
        }
        final int block = this.getBlockLightValue(pos, chunk);
        return Math.max(sky, block);
    }

    public LayerLightEventListener getSkyReader() {
        return this.skyReader;
    }

    public LayerLightEventListener getBlockReader() {
        return this.blockReader;
    }

    public boolean hasSectionSkyLight(SectionPos sectionPos) {
        final ChunkAccess chunk = this.getAnyChunkNow(sectionPos.x(), sectionPos.z());

        if (sectionPos.y() > this.maxLightSection || sectionPos.y() < this.minLightSection) {
            return false;
        }

        return chunk != null && !((ExtendedChunk) chunk).scalablelux$getSkyNibbles()[sectionPos.y() - this.minLightSection].isNullNibbleVisible();
    }

    public boolean hasSectionBlockLight(SectionPos sectionPos) {
        final ChunkAccess chunk = this.getAnyChunkNow(sectionPos.x(), sectionPos.z());

        if (sectionPos.y() > this.maxLightSection || sectionPos.y() < this.minLightSection) {
            return false;
        }

        return chunk != null && !((ExtendedChunk) chunk).scalablelux$getBlockNibbles()[sectionPos.y() - this.minLightSection].isNullNibbleVisible();
    }

    public boolean isClientSide() {
        return this.isClientSide;
    }

    public ChunkAccess getAnyChunkNow(final int chunkX, final int chunkZ) {
        if (this.world == null) {
            // empty world
            return null;
        }
        return ((ExtendedWorld)this.world).scalablelux$getAnyChunkImmediately(chunkX, chunkZ);
    }

    public boolean hasUpdates() {
        // a guaranteed, per-tick, server-thread call site (the harness's barrier and vanilla's tryScheduleUpdate both
        // come through here): the flush point for R3's coalesced edits, so nothing stays buffered across ticks
        if (LUCIS_EDIT_DEBUG) {
            System.out.println("FLUSHDBG hasUpdates pendingChunks=" + this.lucis$pendingEdits.size()
                    + " pendingRecomputes=" + this.lucis$pendingRecomputes.size()
                    + " queueEmpty=" + this.lightQueue.isEmpty());
        }
        this.lucis$flushAllPendingEdits(true);
        return !this.lightQueue.isEmpty();
    }

    /** One-line engine state for the telemetry line and {@code /scalablelux stats}. */
    public String lucisStats() {
        final StringBuilder ret = new StringBuilder(96);
        if (this.lightQueue instanceof ConcurrentLightQueue queue) {
            synchronized (queue) {
                ret.append("tasks=").append(queue.chunkTasks.size());
            }
            ret.append(" dirty=").append(queue.dirtyPos.size());
        } else if (this.lightQueue instanceof SimpleLightQueue queue) {
            synchronized (queue) {
                ret.append("tasks=").append(queue.chunkTasks.size());
            }
            ret.append(" dirty=n/a");
        } else {
            ret.append("tasks=n/a dirty=n/a");
        }
        ret.append(" poolSky=").append(this.cachedSkyPropagators == null ? -1 : this.cachedSkyPropagators.size());
        ret.append(" poolBlock=").append(this.cachedBlockPropagators == null ? -1 : this.cachedBlockPropagators.size());
        return ret.toString();
    }

    public Level getWorld() {
        return this.world;
    }

    public LightChunkGetter getLightAccess() {
        return this.lightAccess;
    }

    protected final SkyStarLightEngine getSkyLightEngine() {
        if (this.cachedSkyPropagators == null) {
            return null;
        }
        final SkyStarLightEngine ret;
        synchronized (this.cachedSkyPropagators) {
            ret = this.cachedSkyPropagators.pollFirst();
        }

        if (ret == null) {
            return new SkyStarLightEngine(this.world);
        }
        return ret;
    }

    protected final void releaseSkyLightEngine(final SkyStarLightEngine engine) {
        if (engine == null) {
            return;
        }
        if (LuxProfiler.enabled()) {
            engine.lucisFlushToProfiler();
        }
        if (this.cachedSkyPropagators == null) {
            return;
        }
        synchronized (this.cachedSkyPropagators) {
            this.cachedSkyPropagators.addFirst(engine);
        }
    }

    protected final BlockStarLightEngine getBlockLightEngine() {
        if (this.cachedBlockPropagators == null) {
            return null;
        }
        final BlockStarLightEngine ret;
        synchronized (this.cachedBlockPropagators) {
            ret = this.cachedBlockPropagators.pollFirst();
        }

        if (ret == null) {
            return new BlockStarLightEngine(this.world);
        }
        return ret;
    }

    protected final void releaseBlockLightEngine(final BlockStarLightEngine engine) {
        if (engine == null) {
            return;
        }
        if (LuxProfiler.enabled()) {
            engine.lucisFlushToProfiler();
        }
        if (this.cachedBlockPropagators == null) {
            return;
        }
        synchronized (this.cachedBlockPropagators) {
            this.cachedBlockPropagators.addFirst(engine);
        }
    }

    /**
     * R2 of the new engine: propagate and install an edit inside the call that made it, buffered per chunk and settled by
     * the windowed recompute below. <b>Default ON</b> since 2026-09-23: this is the configuration the acceptance measured
     * (all four engine-metric cells and the player axis against ScalableLux, 1.x and vanilla), so players get it unless
     * they explicitly set {@code -Dscalablelux.ownEdit=false}.
     */
    private static final boolean LUCIS_OWN_EDIT = !"false".equalsIgnoreCase(System.getProperty("scalablelux.ownEdit", "true"));
    /** Diagnostic trace of one block change's route through this class; off unless asked for by hand. */
    private static final boolean LUCIS_EDIT_DEBUG = Boolean.getBoolean("scalablelux.editDebug");
    /**
     * Dispatch rule, <b>default 16</b>: a buffered burst of at most this many changes in ONE chunk is handed back to the
     * base's asynchronous path instead of being settled inline. Idea: the inline lane's gain is skipping the queue
     * turnaround, and that gain is only worth the cost of a synchronous cache setup + drain + publish per chunk when the
     * burst is big. Measured on 2026-09-24, four routes interleaved twice on {@code block_toggle_border} (95 scattered
     * changes over 19 chunks, ~5 per chunk): inline lane 5.62-6.37 ms, this rule 5.33-5.37 ms, base queue throughout
     * 4.91-5.35 ms, stock ScalableLux 5.06-5.76 ms. The rule therefore removes this engine's worst cell.
     *
     * <p><b>What the earlier "CLOSED" verdict here was based on.</b> It read "border went from 0.43 ms to 4.03 ms with
     * the rule" - but 0.43 ms was measured while the block-light half of a large burst was not being computed at all
     * (the null-position-set defect fixed in 2.0.6), so those two numbers priced skipping the work against doing it.
     * The bulk workloads are unaffected by the rule either way: their per-chunk bursts are 25 changes for sky_hole,
     * 2048 for dense_chunk_patch and 256+ for structure_cube.</p>
     */
    private static final int LUCIS_INLINE_MIN_BURST = Integer.getInteger("scalablelux.inlineMinBurst", 16);
    /** A chunk with at least this many changes in one burst is settled by ONE full relight instead of per-position seeds. */
    private static final boolean LUCIS_BULK_RELIGHT = Boolean.getBoolean("scalablelux.bulkRelight");
    private static final int LUCIS_BULK_MIN_CHANGES = Integer.getInteger("scalablelux.bulkMinChanges", 128);
    /** R5: settle a bulk skylight change by the canonical recompute instead of the seeded BFS (see the sky engine). */
    private static final boolean LUCIS_RECOMPUTE_SKY = !"false".equalsIgnoreCase(System.getProperty("scalablelux.recomputeSky", "true"));
    // The threshold is 4, and the earlier 128 was wrong in a way worth recording: it assumed the windowed recompute only
    // pays for *bulk* bursts. It pays for *small* ones too, because the BFS is not priced per change but per cascade - a
    // handful of block toggles on a chunk border measured ~950 queue pops per change (~65 ns each), while the recompute's
    // cost is set by the y window the burst touches and nothing else. Measured on the same harness, same window:
    //   block_toggle_border:  min=128 4.876 ms | min=16 5.071 | min=4 1.471 | min=1 1.837   (per-chunk bursts ~5 changes)
    //   sky_hole:             min=128 0.614 ms | min=16 0.537 | min=4 0.588 | min=1 1.416 (outlier run)
    // 16 is above the border bursts' size, so it falls back to the BFS there and keeps the old cost; 4 keeps the border
    // chunks on the recompute and is below sky_hole's burst, which is also on the recompute and slightly better for it.
    // A burst smaller than 4 changes stays on the BFS, which is the path the light-source gate exercises.
    private static final int LUCIS_RECOMPUTE_MIN = Integer.getInteger("scalablelux.recomputeMinChanges", 4);

    /**
     * R3: edits wait here per chunk so one engine call handles a whole burst instead of one call per block. Each flush
     * pays a fixed engine cost (setupCaches + seed + consolidated drain), so the size looked like a lever for
     * structure_cube, whose 4096 changes per pass arrive as 16 flushes of 256 - but it is not one: measured 256 vs 1024
     * vs 4096 the structure cell read 4.60/5.03, 4.65/4.51, 5.68/4.61 ms (its own spread is larger than any difference)
     * and dense_chunk_patch preferred 256 outright (1.32/1.29 against 1.42/1.42 and 1.46/1.46). Light fingerprints
     * identical in all six runs, so the bigger buffer does not silently defer work either - it simply buys nothing.
     */
    // 8192, not 256: every flush settles its share with a full cache setup, a drain and a publish, and a drain walks
    // the light that is already there - so splitting one burst into sixteen flushes repeated that walk sixteen times.
    // Measured on dense_chunk_patch (4096 changes per pass): buffer 256 reads apply 20.8 ms / minPass 5.91 ms,
    // buffer 8192 reads apply 2.5 ms / minPass 3.56 ms against ScalableLux0027s 4.32. The tick hook still settles every
    // tick, so a larger buffer costs at most one tick of latency, never correctness.
    private static final int LUCIS_PENDING_FLUSH_SIZE = Integer.getInteger("scalablelux.pendingFlushSize", 8192);
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongOpenHashSet> lucis$pendingEdits =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    /**
     * R5: chunks whose skylight a bulk burst asked to recompute, with the y range the changes touched, drained at the
     * settle points (see the flush). The range is what makes the settle a window instead of the whole chunk: a light
     * change cannot travel more than 15 levels/steps away from where it happened.
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> lucis$pendingRecomputes =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /**
     * Whether the 3x3 neighbourhood of a chunk is loaded and past LIGHT (SL's own {@code canUseChunk} semantics). The
     * inline path propagates across chunk borders, so it must not run while a neighbour is still generating: taking it
     * anyway surfaced a lock-protocol fault inside the base queue's {@code getOrCreateChunkTasks} during a
     * border + profiler run (2026-09-22), i.e. the inline lane was touching a queue whose chunks were mid-generation.
     */
    private boolean lucis$neighbourhoodReady(final int chunkX, final int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final ChunkAccess neighbour = this.getAnyChunkNow(chunkX + dx, chunkZ + dz);
                if (neighbour == null || !neighbour.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Hoisted per-chunk state for the inline lane: the guards in there (thread check, chunk lookup, status test, the
     * chunk-switch flush) measured ~60 ns of the ~700 ns per change the apply phase costs on structure_cube, and a burst
     * arrives one chunk at a time - so while the chunk stays the same, none of them have to run again.
     */
    private long lucis$currentEditChunk = Long.MIN_VALUE;

    /**
     * Whether the inline lane should <b>hold</b> a burst instead of settling it when the edit moves to the next chunk:
     * the rule that turns a line of small edits along a chunk border into one drain per three chunks rather than one
     * drain per chunk. Off unless asked for, because the plain "settle what I have when the chunk changes" is what the
     * bulk workloads are tuned against, and a chunk whose burst is already large is never held.
     */
    private static final boolean LUCIS_HOLD_SCATTERED =
            !"false".equalsIgnoreCase(System.getProperty("scalablelux.holdScattered", "false"));
    private static final int LUCIS_SCATTER_MAX_CHUNKS = Integer.getInteger("scalablelux.scatterMaxChunks", 64);
    private static final int LUCIS_SCATTER_MAX_PER_CHUNK = Integer.getInteger("scalablelux.scatterMaxPerChunk", 16);

    private boolean lucis$holdScattered(final long leavingKey) {
        if (!LUCIS_HOLD_SCATTERED || this.lucis$pendingEdits.size() >= LUCIS_SCATTER_MAX_CHUNKS) {
            return false;
        }
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet leaving = this.lucis$pendingEdits.get(leavingKey);

        return leaving != null && leaving.size() <= LUCIS_SCATTER_MAX_PER_CHUNK;
    }

    private boolean lucis$ownEditInline(final BlockPos pos) {
        if (!(this.world instanceof ServerLevel serverLevel)
                || !serverLevel.getChunkSource().chunkMap.mainThreadExecutor.isSameThread()) {
            // the pooled engine instances are thread-confined: only the server thread may run this
            LuxProfiler.ownEditRejThread++;
            return false;
        }
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        final long key = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet pending;

        if (key == this.lucis$currentEditChunk) {
            pending = this.lucis$pendingEdits.get(key);
            if (pending == null) {
                // LANDMINE found in review: every flush drains the buffers (hasUpdates() runs each tick), and when it did,
                // this cached key stayed valid while its buffer was gone - the next edit of the same chunk took this
                // branch, got null and would have thrown. Re-check the chunk instead of trusting the cache.
                this.lucis$currentEditChunk = Long.MIN_VALUE;
            }
        }
        if (this.lucis$currentEditChunk != key) {
            final ChunkAccess chunk = this.getAnyChunkNow(chunkX, chunkZ);
            // Note: an earlier version also required the whole 3x3 neighbourhood to be loaded and past LIGHT. That guard
            // is gone because its reason is gone: the crashes it was meant to prevent turned out to be a pre-existing
            // StampedLock race in this queue's getOrCreateChunkTasks (unlocking an optimistic-read stamp), reproduced with
            // the inline path switched OFF. The guard also proved too strict - getAnyChunkNow returns null for chunks that
            // are only ticket-held, so it silently disabled the inline path on the border workload entirely.
            if (chunk == null) { LuxProfiler.ownEditRejChunk++; if (LUCIS_EDIT_DEBUG) { System.out.println("EDITDBG inline reject chunk-null " + chunkX + "," + chunkZ); } return false; }
            if (!chunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)) { LuxProfiler.ownEditRejStatus++; if (LUCIS_EDIT_DEBUG) { System.out.println("EDITDBG inline reject status " + chunk.getPersistedStatus()); } return false; }
            final boolean holdScattered = this.lucis$holdScattered(this.lucis$currentEditChunk);

            if (holdScattered) {
                // Scattered small edits (a few changes in a chunk, then the next chunk, ...) are the shape
                // block_toggle_border is made of, and settling them one chunk at a time is the expensive way to do it:
                // each settle pays a cache setup and a drain, and each drain has to refill from neighbours that the
                // *next* chunk's drain is about to clear again. Holding them until the settle point lets the group
                // rule put three chunks of a line into one drain. Bulk bursts never take this path - a chunk whose
                // burst is large is settled at once, exactly as before.
                this.lucis$currentEditChunk = key;
                pending = this.lucis$pendingEdits.computeIfAbsent(key, ignored -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
            } else {
            // A new chunk means the previous burst is over: settle everything else first (edits usually arrive chunk by
            // chunk, so this is the cheap case), then buffer this position under its own chunk.
            this.lucis$flushPendingEdits(key, false);
            this.lucis$currentEditChunk = key;
            pending = this.lucis$pendingEdits.computeIfAbsent(key, ignored -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
            }
        } else {
            // the cache is valid and the buffer exists: nothing to re-check, nothing to flush
            pending = this.lucis$pendingEdits.get(key);
        }
        pending.add(pos.asLong());
        if (pending.size() >= LUCIS_PENDING_FLUSH_SIZE) {
            this.lucis$flushAllPendingEdits(false);
            this.lucis$currentEditChunk = Long.MIN_VALUE; // the flush drained the buffers: re-check on the next edit
        }
        return true;
    }

    /**
     * Applies every buffered edit burst except {@code keepKey} (pass -1 to apply all), one engine call per chunk.
     * Server thread only; a call from any other thread is ignored (the buffer stays for the next server-thread point).
     */
    /**
     * Applies every buffered edit burst, one engine call per chunk. Server thread only; a call from any other thread
     * is ignored and the buffer stays for the next server-thread point.
     *
     * <p>Called from {@code ServerLevel.tick} (see ServerWorldMixin) so that a buffered edit is never left waiting for
     * an unrelated server-thread call: without that tick hook the only settle point was {@code hasUpdates()}, which the
     * light thread also reaches (and is refused), and which vanilla only reaches from the server thread when the
     * engine's own queue has work - work the inline lane deliberately does not create.</p>
     */
    public void lucisFlushPendingEdits() {
        this.lucis$flushAllPendingEdits(true);
    }

    private void lucis$flushPendingEdits(final long keepKey, final boolean settle) {
        this.lucis$flushPendingEditsImpl(true, keepKey, settle);
    }

    /** Applies every buffered burst, keeping nothing back: see {@link #lucis$flushPendingEditsImpl} for why "keep
     *  nothing" is its own entry point rather than {@code keepKey = -1}. */
    private void lucis$flushAllPendingEdits(final boolean settle) {
        this.lucis$flushPendingEditsImpl(false, 0L, settle);
    }

    /**
     * @param keepOne when true, the one chunk {@code keepKey} is left buffered (it is the chunk whose burst is still
     *                arriving); when false the {@code keepKey} argument is ignored entirely. The flag exists because
     *                the previous form passed {@code -1} as "keep nothing" — and a chunk key of -1 is not a sentinel,
     *                it is the real key of chunk (-1,-1) ({@code (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32)} with
     *                x = z = -1). Every buffered edit in that one chunk was therefore skipped by every flush that was
     *                supposed to apply it: a light source placed there never lit up, live or after a save, while the
     *                same placement one chunk away worked. It is the chunk players stand in at the origin, so it is
     *                exactly where the first player builds.
     */
    private void lucis$flushPendingEditsImpl(final boolean keepOne, final long keepKey, final boolean settle) {
        final long lucisSettleT0 = System.nanoTime();

        try {
        this.lucis$flushPendingEditsBody(keepOne, keepKey, settle);
        } finally {
            // Unconditional: whoever reached this settle (the tick hook or hasUpdates) pays for it, and on
            // dense_chunk_patch the harness sees ~4.4 ms a pass between the end of apply and the end of the wait that
            // no other counter covers.
            LuxProfiler.lucisSettle(System.nanoTime() - lucisSettleT0);
        }
    }

    private void lucis$flushPendingEditsBody(final boolean keepOne, final long keepKey, final boolean settle) {
        // NOTE: the recompute queue lives on after the edit buffer is drained - the 256-change flush empties it long
        // before the settle points arrive, and returning early here silently skipped every deferred recompute (the sky
        // half of a bulk burst was simply never done: faster, and wrong). Check both sets.
        if (this.lucis$pendingEdits.isEmpty() && this.lucis$pendingRecomputes.isEmpty()) {
            return;
        }
        if (LUCIS_EDIT_DEBUG) {
            System.out.println("FLUSHDBG enter thread=" + Thread.currentThread().getName()
                    + " pending=" + this.lucis$pendingEdits.size()
                    + " recomputes=" + this.lucis$pendingRecomputes.size()
                    + " keepKey=" + keepKey + " settle=" + settle);
        }
        if (!(this.world instanceof ServerLevel serverLevel)
                || !serverLevel.getChunkSource().chunkMap.mainThreadExecutor.isSameThread()) {
            if (LUCIS_EDIT_DEBUG) {
                System.out.println("FLUSHDBG exit: not the main thread (" + Thread.currentThread().getName() + ")"
                        + " pending=" + this.lucis$pendingEdits.size());
            }
            return;
        }
        // Dispatch rule: a burst of a few changes in one chunk is cheaper on the base path, where the engine's own
        // thread settles it, than in the inline lane, where the server thread pays a cache setup, a drain and a publish
        // per chunk. Measured on `block_toggle_border` (95 scattered changes over 19 chunks, ~5 per chunk), all three
        // routes interleaved twice: inline lane 5.62-6.37 ms, base queue 5.33-5.37 ms, stock ScalableLux 5.06-5.76 ms -
        // so the small-burst case is the one the lane must NOT take. The bulk workloads are unaffected because their
        // per-chunk bursts are orders of magnitude larger (dense 2048, sky_hole 25, structure 256+), and a burst that
        // is not one chunk's is never dispatched. An older comment here claimed the opposite (that this rule turned
        // border "from 0.43 ms into 4.03 ms"): those two numbers were both measured while the block-light half of a
        // large burst was not being computed at all, so 0.43 ms was the price of skipping the work.
        if (LUCIS_INLINE_MIN_BURST > 0 && this.lucis$pendingEdits.size() == 1) {
            int total = 0;
            for (final it.unimi.dsi.fastutil.longs.LongOpenHashSet pending : this.lucis$pendingEdits.values()) {
                total += pending.size();
            }
            if (total <= LUCIS_INLINE_MIN_BURST) {
                final long[] keysToQueue = this.lucis$pendingEdits.keySet().toLongArray();
                for (final long key : keysToQueue) {
                    final it.unimi.dsi.fastutil.longs.LongOpenHashSet positions = this.lucis$pendingEdits.remove(key);
                    if (positions == null) {
                        continue;
                    }
                    for (final long change : positions) {
                        this.lightQueue.queueBlockChange(BlockPos.of(change));
                    }
                }
                if (LuxProfiler.enabled()) {
                    LuxProfiler.ownEditSmallBursts++;
                }
                return;
            }
        }
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        // bursts on the ordinary path are collected here and settled in cache-window groups after the loop: several
        // chunks along one border then share one cache setup, one drain and one publish (see lucis$settleGroups)
        final it.unimi.dsi.fastutil.objects.ObjectArrayList<LucisChunkBurst> grouped =
                new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
        try {
            if (!this.lucis$pendingEdits.isEmpty()) {
            final it.unimi.dsi.fastutil.longs.LongIterator keys = this.lucis$pendingEdits.keySet().iterator();
            while (keys.hasNext()) {
                final long key = keys.nextLong();
                if (keepOne && key == keepKey) {
                    continue;
                }
                final it.unimi.dsi.fastutil.longs.LongOpenHashSet positions = this.lucis$pendingEdits.get(key);
                keys.remove();
                if (positions == null || positions.isEmpty()) {
                    if (LUCIS_EDIT_DEBUG) {
                        System.out.println("FLUSHDBG drop key=" + key + " positions="
                                + (positions == null ? "null" : "empty"));
                    }
                    continue;
                }
                if (LUCIS_EDIT_DEBUG) {
                    System.out.println("FLUSHDBG apply chunk=" + CoordinateUtils.getChunkX(key) + ","
                            + CoordinateUtils.getChunkZ(key) + " n=" + positions.size());
                }
                final int chunkX = CoordinateUtils.getChunkX(key);
                final int chunkZ = CoordinateUtils.getChunkZ(key);
                final long lucisT0 = System.nanoTime();
                try {
                    // R5: a bulk skylight change is settled by ONE canonical recompute per chunk per burst, not per flush
                    // (the buffer flushes every 256 changes, so a per-flush recompute would rebuild the same chunk 16
                    // times on structure_cube). The sky half is therefore deferred to the settle points - hasUpdates()
                    // and syncFuture(), which is where "the engine must be settled now" is asked - while the block half
                    // runs here as always.
                    final ChunkAccess chunkNow = this.getAnyChunkNow(chunkX, chunkZ);
                    final boolean deferSky = LUCIS_RECOMPUTE_SKY && chunkNow != null
                            && positions.size() >= LUCIS_RECOMPUTE_MIN;

                    // Packed longs, never boxed BlockPos: this list is built for every flush and a bulk burst holds tens
                    // of thousands of positions, which JFR showed as heavy young-generation churn in this engine and no
                    // churn at all in the base on the same workload. It is consumed on both paths now (block and sky),
                    // including when the sky half is deferred - see the note below about the null that used to be here.
                    final long[] seedPositions = toSeedPositions(positions);
                    final int seedCount = seedPositions.length;

                    if (deferSky) {
                        // remember the y range the burst touched: the window is built from it
                        final int[] range = this.lucis$pendingRecomputes.computeIfAbsent(key, ignored -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
                        final it.unimi.dsi.fastutil.longs.LongIterator changedIt = positions.iterator();

                        while (changedIt.hasNext()) {
                            final int changedY = BlockPos.getY(changedIt.nextLong());

                            if (changedY < range[0]) {
                                range[0] = changedY;
                            }
                            if (changedY > range[1]) {
                                range[1] = changedY;
                            }
                        }
                        // The block half runs here (through the grouped settle, so it shares a cache window and a drain
                        // with its neighbours); the sky half is left to the deferred recompute.
                        grouped.add(new LucisChunkBurst(chunkX, chunkZ, seedPositions, seedCount, true));
                    } else {
                    final ChunkAccess bulkChunk = LUCIS_BULK_RELIGHT && positions.size() >= LUCIS_BULK_MIN_CHANGES
                            ? this.getAnyChunkNow(chunkX, chunkZ) : null;
                    if (bulkChunk != null) {
                        // L3's batched half: a chunk with many changes in one burst is cheaper as ONE full relight than
                        // as N per-position seeds - the same routine the chunk-load path uses, so correctness comes from
                        // the base. structure_cube puts 4096 changes inside a single section, which is the case this
                        // exists for; the base and our per-edit path both pay ~1.2 us per seed there.
                        if (skyEngine != null) {
                            skyEngine.lightChunk(this.lightAccess, bulkChunk, true);
                        }
                        if (blockEngine != null) {
                            blockEngine.lightChunk(this.lightAccess, bulkChunk, true);
                        }
                        if (LuxProfiler.enabled()) {
                            LuxProfiler.ownEditBulkRelights++;
                        }
                    } else {
                        // Collected rather than settled here. The base settles one chunk at a time (setup caches ->
                        // seed -> drain -> publish -> destroy) and pays a full drain per chunk; a burst along a chunk
                        // border is many small changes spread over several chunks, so it paid that drain for each of
                        // them. Chunks that share one cache window are settled together after the loop instead.
                        grouped.add(new LucisChunkBurst(chunkX, chunkZ, seedPositions, seedCount, false));
                    }
                    }
                    if (LuxProfiler.enabled()) {
                        LuxProfiler.ownEditNanos += System.nanoTime() - lucisT0;
                        LuxProfiler.ownEditInline++;
                        LuxProfiler.ownEditBatched += positions.size();
                    }
                } catch (Throwable t) {
                    if (LuxProfiler.enabled()) {
                        LuxProfiler.ownEditFallback++;
                    }
                    // Never swallow this quietly again: the counter above is invisible unless the profiler is on, and a
                    // failure here means the burst's light is left stale (it is what hid a null position set behind the
                    // deferred-sky path for several releases). Log the first one, then every 1024th, so a systematic
                    // failure is loud without flooding a busy log.
                    if (LUCIS_FALLBACK_LOGGED == 0L || LuxProfiler.ownEditFallback % 1024L == 0L) {
                        LUCIS_FALLBACK_LOGGED++;
                        LOGGER.error("LuciStarlink: settling a buffered edit burst failed ("
                                + LUCIS_FALLBACK_LOGGED + ". occurrence); the light of those changes is stale", t);
                    }
                }
            }
            }
            if (!grouped.isEmpty()) {
                this.lucis$settleGroups(skyEngine, blockEngine, grouped);
            }
            if (settle && !this.lucis$pendingRecomputes.isEmpty()) {
                // R5: the deferred sky settles, one per chunk per burst, over the y window the burst touched (see the sky
                // engine for what it does and why it can only be right there). The settle points are
                // hasUpdates()/syncFuture(), i.e. exactly the moments at which something is about to ask whether the
                // engine has finished.
                for (final it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<int[]> recomputeEntry
                        : this.lucis$pendingRecomputes.long2ObjectEntrySet()) {
                    final long key = recomputeEntry.getLongKey();
                    final int chunkX = CoordinateUtils.getChunkX(key);
                    final int chunkZ = CoordinateUtils.getChunkZ(key);
                    final ChunkAccess chunk = this.getAnyChunkNow(chunkX, chunkZ);

                    if (chunk == null || skyEngine == null) {
                        continue;
                    }
                    final int[] range = recomputeEntry.getValue();

                    if (range[0] > range[1]) {
                        continue; // no y range recorded for this chunk
                    }
                    final long lucisRecT0 = System.nanoTime();

                    skyEngine.setupCaches(this.lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
                    try {
                        // the settle pushes both directions, so both propagations have to run (decrease first, as the
                        // engine does everywhere else) before the section is published
                        skyEngine.settleSkyWindow(this.lightAccess, chunk, range[0], range[1]);
                        skyEngine.performLightDecrease(this.lightAccess);
                        skyEngine.performLightIncrease(this.lightAccess);
                        skyEngine.updateVisible(this.lightAccess);
                    } finally {
                        skyEngine.destroyCaches();
                    }
                    if (LuxProfiler.enabled()) {
                        LuxProfiler.ownEditRecomputes++;
                        LuxProfiler.ownEditRecomputeNanos += System.nanoTime() - lucisRecT0;
                    } else {
                        LuxProfiler.lucisRecompute(System.nanoTime() - lucisRecT0);
                    }
                }
                this.lucis$pendingRecomputes.clear();
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    /** One chunk's ordinary-path burst, waiting for a cache window to be settled in. Positions stay packed as
     *  {@code BlockPos.asLong} so nothing is boxed on the way from the edit buffer to the engine. */
    private record LucisChunkBurst(int chunkX, int chunkZ, long[] positions, int positionCount, boolean skyDeferred) {}

    /**
     * Settles the collected bursts, several chunks per cache setup.
     *
     * <p><b>The rule that keeps this exact.</b> The engine's caches cover the centre chunk plus two chunks in every
     * direction, and a single change can move light at most one chunk away (a level is 0..15 and each propagation
     * step costs one). So a group may contain any chunks that lie within <b>±1 chunk of the centre</b>: their
     * propagation then reaches at most two chunks out, which is still inside the cache — the same bound the base's
     * per-chunk routine has, because there the centre <i>is</i> the edited chunk.</p>
     *
     * <p><b>The order that keeps it correct.</b> setup caches → seed every chunk in the group → <i>one</i> drain →
     * <i>one</i> publish → destroy. A drain with cleared caches does nothing at all (2.0.3 shipped that and a placed
     * torch lit one cell); publishing before the drain leaves the propagated light invisible (2.0.2/2.0.3 shipped
     * that as well). Both mistakes are only possible if the drain is moved out of the cache window, which is why the
     * seeding half is a separate entry point now and the drain is not.</p>
     *
     * <p>Chunks the caches refused to bind (not lit yet, not usable) are skipped exactly as the base skips them.</p>
     */
    private void lucis$settleGroups(final SkyStarLightEngine skyEngine, final BlockStarLightEngine blockEngine,
                                    final it.unimi.dsi.fastutil.objects.ObjectArrayList<LucisChunkBurst> pending) {
        if (skyEngine == null && blockEngine == null) {
            return;
        }
        final it.unimi.dsi.fastutil.objects.ObjectArrayList<LucisChunkBurst> remaining =
                new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(pending);

        while (!remaining.isEmpty()) {
            // the centre that keeps the most chunks: a burst along one border is a line of chunks, and only a centre
            // inside it turns three of them into one group (a rule of "anchor + its +-1 neighbours" makes groups of two)
            int bestCentre = 0;
            int bestCount = -1;

            for (int centre = 0; centre < remaining.size(); centre++) {
                final LucisChunkBurst candidate = remaining.get(centre);
                int count = 0;

                for (final LucisChunkBurst other : remaining) {
                    if (Math.abs(other.chunkX() - candidate.chunkX()) <= 1
                            && Math.abs(other.chunkZ() - candidate.chunkZ()) <= 1) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    bestCentre = centre;
                }
            }
            final LucisChunkBurst centre = remaining.get(bestCentre);
            final it.unimi.dsi.fastutil.objects.ObjectArrayList<LucisChunkBurst> group =
                    new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(bestCount);

            for (int i = remaining.size() - 1; i >= 0; i--) {
                final LucisChunkBurst burst = remaining.get(i);

                if (Math.abs(burst.chunkX() - centre.chunkX()) <= 1 && Math.abs(burst.chunkZ() - centre.chunkZ()) <= 1) {
                    group.add(burst);
                    remaining.remove(i);
                }
            }
            this.lucis$settleGroup(skyEngine, blockEngine, group, centre.chunkX(), centre.chunkZ());
        }
    }

    private void lucis$settleGroup(final SkyStarLightEngine skyEngine, final BlockStarLightEngine blockEngine,
                                   final it.unimi.dsi.fastutil.objects.ObjectArrayList<LucisChunkBurst> group,
                                   final int centerChunkX, final int centerChunkZ) {
        final int centerX = centerChunkX * 16 + 7;
        final int centerZ = centerChunkZ * 16 + 7;

        final long lucisSetupT0 = System.nanoTime();

        if (skyEngine != null) {
            skyEngine.setupCaches(this.lightAccess, centerX, 128, centerZ, true, true);
        }
        if (blockEngine != null) {
            blockEngine.setupCaches(this.lightAccess, centerX, 128, centerZ, true, true);
        }
        final long lucisT0 = System.nanoTime();
        int skySeeded = 0;
        try {
            for (final LucisChunkBurst burst : group) {
                // a deferred burst leaves its sky half to the windowed recompute, so only the block half is seeded here
                if (skyEngine != null && !burst.skyDeferred() && skyEngine.isChunkInCache(burst.chunkX(), burst.chunkZ())) {
                    skyEngine.seedChanges(this.lightAccess, burst.positions(), burst.positionCount());
                    skySeeded++;
                }
                if (blockEngine != null && blockEngine.isChunkInCache(burst.chunkX(), burst.chunkZ())) {
                    blockEngine.seedChanges(this.lightAccess, burst.positions(), burst.positionCount());
                }
            }
            final long lucisT1 = System.nanoTime();
            // one drain and one publish for the whole group, with the caches still up: the drain reads neighbours
            // through them and the publish is what makes the propagation visible to the client and to a save.
            // Timed separately on purpose: the base's per-chunk task reads 1.78 ms of decrease and 0.16 ms of publish
            // on this workload while this lane's combined settle reads 5.7 ms, and only a split can say which half is
            // the 3x.
            long skyDec = 0L;
            long skyVis = 0L;
            long blkDec = 0L;
            long blkVis = 0L;

            if (skyEngine != null) {
                final long t0 = System.nanoTime();
                skyEngine.performLightDecrease(this.lightAccess);
                final long t1 = System.nanoTime();
                skyEngine.updateVisible(this.lightAccess);
                final long t2 = System.nanoTime();

                skyDec = t1 - t0;
                skyVis = t2 - t1;
            }
            if (blockEngine != null) {
                final long t0 = System.nanoTime();
                blockEngine.performLightDecrease(this.lightAccess);
                final long t1 = System.nanoTime();
                blockEngine.updateVisible(this.lightAccess);
                final long t2 = System.nanoTime();

                blkDec = t1 - t0;
                blkVis = t2 - t1;
            }
            final long lucisT3 = System.nanoTime();

            // Unconditional on purpose: six clock reads per group, ~19 groups in a border pass. The phase split is the
            // whole question on that cell (our lane settles the same work as the base queue but reads 25% slower), and
            // a split that only exists with the pop counters on cannot answer it - those counters cost ~3 ms a pass.
            LuxProfiler.lucisGroupPhases(lucisT0 - lucisSetupT0, lucisT1 - lucisT0,
                    skyDec, skyVis, blkDec, blkVis, group.size(), skySeeded);
        } finally {
            if (skyEngine != null) {
                skyEngine.destroyCaches();
            }
            if (blockEngine != null) {
                blockEngine.destroyCaches();
            }
        }
    }

    public LightQueue.ChunkTasks blockChange(final BlockPos pos) {
        if (this.world == null || pos.getY() < WorldUtil.getMinBlockY(this.world) || pos.getY() > WorldUtil.getMaxBlockY(this.world)) { // empty world
            return null;
        }
        if (SableCompat.isSablePlotChunk(this.world, pos.getX() >> 4, pos.getZ() >> 4)) {
            // Sable's plots carry their own per-plot light engine; leave them alone (see SableCompat)
            return null;
        }
        if (LUCIS_OWN_EDIT && this.lucis$ownEditInline(pos)) {
            // handled here: propagated, installed and published; nothing was queued
            if (LUCIS_EDIT_DEBUG) {
                System.out.println("EDITDBG blockChange " + pos + " -> inline lane");
            }
            return null;
        }

        if (LUCIS_EDIT_DEBUG) {
            System.out.println("EDITDBG blockChange " + pos + " -> engine queue");
        }
        return this.lightQueue.queueBlockChange(pos);
    }

    public LightQueue.ChunkTasks sectionChange(final SectionPos pos, final boolean newEmptyValue) {
        if (this.world == null) { // empty world
            return null;
        }
        if (SableCompat.isSablePlotChunk(this.world, pos.x(), pos.z())) {
            return null;
        }

        return this.lightQueue.queueSectionChange(pos, newEmptyValue);
    }

    public void forceLoadInChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        if (SableCompat.isSablePlotChunk(this.world, chunk.getPos().x, chunk.getPos().z)) { // Sable plots carry their own engine
            return;
        }
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void loadInChunk(final int chunkX, final int chunkZ, final Boolean[] emptySections) {
        if (SableCompat.isSablePlotChunk(this.world, chunkX, chunkZ)) { // Sable plots carry their own engine
            return;
        }
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void lightChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        if (SableCompat.isSablePlotChunk(this.world, chunk.getPos().x, chunk.getPos().z)) { // Sable plots carry their own engine
            return;
        }
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.light(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.light(this.lightAccess, chunk, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void relightChunks(final Set<ChunkPos> chunks, final Consumer<ChunkPos> chunkLightCallback,
                              final IntConsumer onComplete) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.relightChunks(this.lightAccess, chunks, blockEngine == null ? chunkLightCallback : null,
                        blockEngine == null ? onComplete : null);
            }
            if (blockEngine != null) {
                blockEngine.relightChunks(this.lightAccess, chunks, chunkLightCallback, onComplete);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void checkChunkEdges(final int chunkX, final int chunkZ) {
        this.checkSkyEdges(chunkX, chunkZ);
        this.checkBlockEdges(chunkX, chunkZ);
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
        } finally {
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
        } finally {
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void scheduleChunkLight(final ChunkPos pos, final Runnable run) {
        this.lightQueue.queueChunkLighting(pos, run);
    }

    public CompletableFuture<Void> syncFuture(final int chunkX, final int chunkZ) {
        this.lucis$flushAllPendingEdits(true);
        return this.lightQueue.getChunkSyncFuture(chunkX, chunkZ).thenApply(Function.identity());
    }

    public void propagateChanges() {
        if (this.lightQueue.isEmpty()) {
            return;
        }

        if (this.lightQueue instanceof ConcurrentLightQueue) {
            this.schedulePropagation0((ThreadedLevelLightEngine) this.lightEngine);
            return;
        }

        SimpleLightQueue queue = (SimpleLightQueue) this.lightQueue;

        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            LightQueue.ChunkTasks task;
            while ((task = queue.removeFirstTask()) != null) {
                handleUpdateInternal(task, skyEngine, blockEngine);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public boolean needsScheduling() {
        if (this.lightQueue instanceof ConcurrentLightQueue concurrentLightQueue) {
            return !concurrentLightQueue.dirtyPos.isEmpty();
        } else {
            return this.hasUpdates();
        }
    }

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);
    private static final CompletableFuture<Void> COMPLETED_FUTURE = CompletableFuture.completedFuture(null);
    private final int instanceId = INSTANCE_COUNTER.getAndIncrement();

    private void schedulePropagation0(ThreadedLevelLightEngine threadedLevelLightEngine) {
        ConcurrentLightQueue queue = (ConcurrentLightQueue) this.lightQueue;
        while (true) {
            final long pos;
            LongPriorityQueue dirtyPos = queue.dirtyPos;
            synchronized (dirtyPos) {
                if (dirtyPos.isEmpty()) break;
                pos = dirtyPos.dequeueLong();
            }
            SchedulingUtil.scheduleTask(
                    this.instanceId,
                    () -> {
                        try {
                            final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
                            final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

                            LightQueue.ChunkTasks tasks = queue.takeTask(pos);
                            if (tasks != null) {
                                try {
                                    handleUpdateInternal(tasks, skyEngine, blockEngine);
                                } finally {
                                    this.releaseSkyLightEngine(skyEngine);
                                    this.releaseBlockLightEngine(blockEngine);
                                }

                                threadedLevelLightEngine.tryScheduleUpdate();
                            }
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    },
                    CoordinateUtils.getChunkX(pos),
                    CoordinateUtils.getChunkZ(pos),
                    2
            );
        }
    }

//    /**
//     * Only relevant on server lighting with scaling enabled, best-effort check if the queue is dirty.
//     */
//    public boolean isQueueDirty() {
//        return this.lightQueue.queueDirty;
//    }

    private void handleUpdateInternal(LightQueue.ChunkTasks task, SkyStarLightEngine skyEngine, BlockStarLightEngine blockEngine) { // keep indentation
        if (task.lightTasks != null) {
            for (final Runnable run : task.lightTasks) {
                run.run();
            }
        }

        final long coordinate = task.chunkCoordinate;
        final int chunkX = CoordinateUtils.getChunkX(coordinate);
        final int chunkZ = CoordinateUtils.getChunkZ(coordinate);

        final Set<BlockPos> positions = task.changedPositions;
        final Boolean[] sectionChanges = task.changedSectionSet;

        if (skyEngine != null && (!positions.isEmpty() || sectionChanges != null)) {
            skyEngine.blocksChangedInChunk(this.lightAccess, chunkX, chunkZ, positions, sectionChanges);
        }
        if (blockEngine != null && (!positions.isEmpty() || sectionChanges != null)) {
            blockEngine.blocksChangedInChunk(this.lightAccess, chunkX, chunkZ, positions, sectionChanges);
        }

        if (skyEngine != null && task.queuedEdgeChecksSky != null) {
            skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, task.queuedEdgeChecksSky);
        }
        if (blockEngine != null && task.queuedEdgeChecksBlock != null) {
            blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, task.queuedEdgeChecksBlock);
        }

        task.onComplete.complete(null);
    }


    public static final class ConcurrentLightQueue implements LightQueue {

        protected final StampedLock tasksLock = new StampedLock();
        protected final Long2ObjectOpenHashMap<ChunkTasks> chunkTasks = new Long2ObjectOpenHashMap<>() {
            @Override
            protected void rehash(int newN) {
                if (n < newN) {
                    super.rehash(newN);
                }
            }
        };
        protected final StarLightInterface manager;
        protected final LongPriorityQueue dirtyPos = LongPriorityQueues.synchronize(new LongArrayFIFOQueue());

        public ConcurrentLightQueue(final StarLightInterface manager) {
            this.manager = manager;
        }

        @Override
        public boolean isEmpty() {
            return this.chunkTasks.isEmpty();
        }

        @Override
        public synchronized LightQueue.ChunkTasks queueBlockChange(final BlockPos pos) {
            return this.enqueueImpl(CoordinateUtils.getChunkKey(pos), tasks -> tasks.changedPositions.add(pos.immutable()));
        }

        @Override
        public synchronized LightQueue.ChunkTasks queueSectionChange(final SectionPos pos, final boolean newEmptyValue) {
            return this.enqueueImpl(CoordinateUtils.getChunkKey(pos), tasks -> {
                if (tasks.changedSectionSet == null) {
                    tasks.changedSectionSet = new Boolean[this.manager.maxSection - this.manager.minSection + 1];
                }
                tasks.changedSectionSet[pos.getY() - this.manager.minSection] = Boolean.valueOf(newEmptyValue);
            });
        }

        @Override
        public synchronized LightQueue.ChunkTasks queueChunkLighting(final ChunkPos pos, final Runnable lightTask) {
            return this.enqueueImpl(CoordinateUtils.getChunkKey(pos), tasks -> {
                if (tasks.lightTasks == null) {
                    tasks.lightTasks = new ArrayList<>();
                }
                tasks.lightTasks.add(lightTask);
            });
        }

        @Override
        public synchronized LightQueue.ChunkTasks queueChunkSkylightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            return this.enqueueImpl(CoordinateUtils.getChunkKey(pos), tasks -> {
                ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksSky;
                if (queuedEdges == null) {
                    queuedEdges = tasks.queuedEdgeChecksSky = new ShortOpenHashSet();
                }
                queuedEdges.addAll(sections);
            });
        }

        @Override
        public synchronized LightQueue.ChunkTasks queueChunkBlocklightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            return this.enqueueImpl(CoordinateUtils.getChunkKey(pos), tasks -> {
                ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksBlock;
                if (queuedEdges == null) {
                    queuedEdges = tasks.queuedEdgeChecksBlock = new ShortOpenHashSet();
                }
                queuedEdges.addAll(sections);
            });
        }

        @Override
        public CompletableFuture<Void> getChunkSyncFuture(final int chunkX, final int chunkZ) {
            final ChunkTasks tasks = this.getChunkTasksOrNull(CoordinateUtils.getChunkKey(chunkX, chunkZ));
            if (tasks == null) {
                return CompletableFuture.completedFuture(null);
            } else {
                return tasks.onComplete;
            }
        }

        public ChunkTasks takeTask(long key) {
            ChunkTasks tasks;
            long stamp = this.tasksLock.writeLock();
            try {
                tasks = this.chunkTasks.remove(key);
            } finally {
                this.tasksLock.unlockWrite(stamp);
            }
            Objects.requireNonNull(tasks);
            synchronized (tasks) {
                tasks.isExecuting = true;
            }
            return tasks;
        }

        private ChunkTasks enqueueImpl(long key, Consumer<ChunkTasks> action) {
            retry:
            while (true) {
                final ChunkTasks tasks = this.getOrCreateChunkTasks(key);
                synchronized (tasks) {
                    if (tasks.isExecuting) {
                        continue retry;
                    }
                    action.accept(tasks);
                    if (!tasks.isQueued) {
                        tasks.isQueued = true;
                        this.dirtyPos.enqueue(key);
                    }
                    return tasks;
                }
            }
        }

        private ChunkTasks getChunkTasksOrNull(long key) {
            long stamp = this.tasksLock.tryOptimisticRead();
            if (stamp != 0L) {
                try {
                    ChunkTasks tasks = this.chunkTasks.get(key);
                    if (this.tasksLock.validate(stamp)) {
                        return tasks;
                    }
                    // fall through
                } catch (Throwable ignored) {
                    // fall through
                }
            }

            stamp = this.tasksLock.readLock();
            try {
                return this.chunkTasks.get(key);
            } finally {
                this.tasksLock.unlockRead(stamp);
            }
        }

        private ChunkTasks getOrCreateChunkTasks(long key) {
            long stamp = this.tasksLock.tryOptimisticRead();
            ChunkTasks tasks;
            boolean tryReadAgain = true;
            if (stamp != 0L) {
                try {
                    tasks = this.chunkTasks.get(key);
                    if (this.tasksLock.validate(stamp)) {
                        tryReadAgain = false;
                        if (tasks != null) {
                            return tasks;
                        }
                    }
                    // fall through
                } catch (Throwable ignored) {
                    // fall through
                }
            }
            long writeStamp;
            if (tryReadAgain) {
                // THE READ LOCK THE BRANCH BELOW ASSUMES: it calls unlockRead(stamp) and tryConvertToWriteLock(stamp),
                // both of which require a real read stamp. Before this line, `stamp` was still the value from
                // tryOptimisticRead - which is not an ownership token - so unlockRead threw IllegalMonitorStateException
                // whenever the optimistic read was invalidated by a concurrent writer and a task already existed
                // (a real, intermittent race, reproduced here on block_toggle_border / chunk-generation traffic).
                stamp = this.tasksLock.readLock();
                try {
                    tasks = this.chunkTasks.get(key);
                } catch (Throwable t) {
                    t.printStackTrace();
                    this.tasksLock.unlockRead(stamp);
                    throw t;
                }
                if (tasks != null) {
                    this.tasksLock.unlockRead(stamp);
                    return tasks;
                }
                tasks = new ChunkTasks(key); // move creation out of write lock region
                writeStamp = this.tasksLock.tryConvertToWriteLock(stamp);
                if (writeStamp == 0L) {
                    this.tasksLock.unlockRead(stamp);
                    writeStamp = this.tasksLock.writeLock();
                }
            } else {
                tasks = new ChunkTasks(key); // move creation out of write lock region
                writeStamp = this.tasksLock.writeLock();
            }
            try {
                ChunkTasks inMap = this.chunkTasks.putIfAbsent(key, tasks);
                if (inMap != null) {
                    tasks = inMap; // return the correct thing
                }
            } finally {
                this.tasksLock.unlockWrite(writeStamp);
            }
            return tasks;
        }
    }

    public static final class SimpleLightQueue implements LightQueue {
        protected final Long2ObjectLinkedOpenHashMap<ChunkTasks> chunkTasks = new Long2ObjectLinkedOpenHashMap<>();
        protected final StarLightInterface manager;
        protected volatile boolean queueDirty = false;

        public SimpleLightQueue(final StarLightInterface manager) {
            this.manager = manager;
        }

        public synchronized boolean isEmpty() {
            return this.chunkTasks.isEmpty();
        }

        public synchronized LightQueue.ChunkTasks queueBlockChange(final BlockPos pos) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);
            tasks.changedPositions.add(pos.immutable());
            this.queueDirty = true;
            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueSectionChange(final SectionPos pos, final boolean newEmptyValue) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            if (tasks.changedSectionSet == null) {
                tasks.changedSectionSet = new Boolean[this.manager.maxSection - this.manager.minSection + 1];
            }
            tasks.changedSectionSet[pos.getY() - this.manager.minSection] = Boolean.valueOf(newEmptyValue);

            this.queueDirty = true;
            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueChunkLighting(final ChunkPos pos, final Runnable lightTask) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);
            if (tasks.lightTasks == null) {
                tasks.lightTasks = new ArrayList<>();
            }
            tasks.lightTasks.add(lightTask);

            this.queueDirty = true;
            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueChunkSkylightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksSky;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksSky = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            this.queueDirty = true;
            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueChunkBlocklightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(CoordinateUtils.getChunkKey(pos), ChunkTasks::new);

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksBlock;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksBlock = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            this.queueDirty = true;
            return tasks;
        }

        public synchronized CompletableFuture<Void> getChunkSyncFuture(final int chunkX, final int chunkZ) {
            final ChunkTasks tasks = this.chunkTasks.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
            if (tasks == null) {
                return CompletableFuture.completedFuture(null);
            } else {
                return tasks.onComplete;
            }
        }

        public void removeChunk(final ChunkPos pos) {
            final ChunkTasks tasks;
            synchronized (this) {
                tasks = this.chunkTasks.remove(CoordinateUtils.getChunkKey(pos));
            }
            if (tasks != null) {
                tasks.onComplete.complete(null);
            }
            this.queueDirty = true;
        }

        public synchronized ChunkTasks removeFirstTask() {
            if (this.chunkTasks.isEmpty()) {
                return null;
            }
            return this.chunkTasks.removeFirst();
        }
    }

    public static sealed interface LightQueue permits SimpleLightQueue, ConcurrentLightQueue {
        boolean isEmpty();

        ChunkTasks queueBlockChange(BlockPos pos);

        ChunkTasks queueSectionChange(SectionPos pos, boolean newEmptyValue);

        ChunkTasks queueChunkLighting(ChunkPos pos, Runnable lightTask);

        ChunkTasks queueChunkSkylightEdgeCheck(SectionPos pos, ShortCollection sections);

        ChunkTasks queueChunkBlocklightEdgeCheck(SectionPos pos, ShortCollection sections);

        CompletableFuture<Void> getChunkSyncFuture(int chunkX, int chunkZ);

        public static final class ChunkTasks {
            public final Set<BlockPos> changedPositions = new ObjectOpenHashSet<>();
            public Boolean[] changedSectionSet;
            public ShortOpenHashSet queuedEdgeChecksSky;
            public ShortOpenHashSet queuedEdgeChecksBlock;
            public List<Runnable> lightTasks;

            public boolean isTicketAdded = false;
            public final CompletableFuture<Void> onComplete = new CompletableFuture<>();

            public boolean isQueued = false;
            public boolean isExecuting = false;

            public final long chunkCoordinate;

            public ChunkTasks(final long chunkCoordinate) {
                this.chunkCoordinate = chunkCoordinate;
            }
        }
    }

    /**
     * Materialises the packed positions a consumer that still speaks {@code Set<BlockPos>} needs, <b>minus the changes
     * that provably cannot move block light</b>.
     *
     * <p>The skip rule: a cell whose block light is already 0 that becomes fully opaque (opacity 15) changes nothing for
     * block light. It emits nothing (level 0), so it is not a source for its neighbours, and relaying light through it
     * would have needed a level above 0 to pass on. Its own value stays 0, and no neighbour's computed value depends on
     * whether the cell is air-with-0 or stone. That is the shape a bulk fill has on every "place" pass - structure_cube
     * places 4096 stone blocks - and block seeding there costs ~1.2 us per position.</p>
     */
    private long[] toSeedPositions(final it.unimi.dsi.fastutil.longs.LongOpenHashSet packed) {
        final long[] ret = new long[packed.size()];
        final it.unimi.dsi.fastutil.longs.LongIterator it = packed.iterator();
        final net.minecraft.world.level.lighting.LayerLightEventListener blockLight = this.world == null
                ? null
                : this.world.getChunkSource().getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int size = 0;
        int skipped = 0;

        while (it.hasNext()) {
            final long packedPos = it.nextLong();

            if (blockLight != null) {
                mutable.set(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));
                if (blockLight.getLightValue(mutable) == 0) {
                    final net.minecraft.world.level.block.state.BlockState state = this.world.getBlockState(mutable);
                    final int opacity = ((ca.spottedleaf.starlight.common.blockstate.ExtendedAbstractBlockState) state)
                            .scalablelux$getOpacityIfCached();

                    // The emission test is not optional, and leaving it out was a real defect: glowstone, sea lanterns and
                    // the other opaque light sources are opacity 15 AND light sources, so "already dark and now fully
                    // opaque" described them exactly - their own cell's light was skipped, the light never appeared in a
                    // live world, and a save taken before anything else relit the chunk stored it dark. Stone (what the
                    // rule exists for: a bulk fill) emits nothing and is still skipped.
                    if (opacity == 15 && state.getLightEmission(this.world, mutable) == 0) {
                        skipped++;
                        continue; // provably a no-op for block light
                    }
                }
            }
            ret[size++] = packedPos;
        }
        if (LuxProfiler.enabled()) {
            LuxProfiler.ownEditBlockSkipped += skipped;
        }
        // A tail shorter than the array means the filter dropped positions; the callers carry the count.
        return size == ret.length ? ret : java.util.Arrays.copyOf(ret, size);
    }
}
