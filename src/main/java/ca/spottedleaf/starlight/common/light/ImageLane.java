package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.debug.LuxProfiler;
import ca.spottedleaf.starlight.common.light.image.ImageBlockLightEngine;
import ca.spottedleaf.starlight.common.light.image.ImageMaterial;
import ca.spottedleaf.starlight.common.light.image.ImageMaterialCache;
import ca.spottedleaf.starlight.common.light.image.ImageMaterialPlanes;
import ca.spottedleaf.starlight.common.light.image.ImageRegionBounds;
import ca.spottedleaf.starlight.common.light.image.ImageRegionData;
import ca.spottedleaf.starlight.common.light.image.OwnedRegionImageCache;
import ca.spottedleaf.starlight.common.light.image.RuntimeLightChangeBuffer;
import ca.spottedleaf.starlight.common.light.image.RuntimeRegionImageState;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The image lane's controller: captures block-material deltas at {@code setBlock} time, keeps the region images
 * (flat byte planes over a one-chunk core plus a one-chunk halo, 1.x's region shape), and settles buffered bursts
 * through {@link ImageBlockLightEngine} - synchronously on the server thread, no queue.
 *
 * <p><b>Ownership boundaries.</b> The nibbles stay the published truth: every settle packs its dirty sections back
 * into the SWMR updating layers and raises {@code onLightUpdate}, so saves and the client see exactly what the base
 * engine would have written. The region images are derived state, rebuilt lazily from the nibbles and the chunk
 * sections; anything that writes block light outside this lane (the base queue's tasks, chunk-load relights,
 * worldgen light stages) reaches {@link #onEngineBlockWrite}, which evicts the affected regions so the next settle
 * re-adopts from the nibbles. Worldgen writes never enter the capture funnel when bracketed, and the light of
 * not-yet-LIGHT chunks is adopted as dark, matching the base engine's own cache-miss semantics.
 *
 * <p><b>Package note:</b> this class lives in {@code light} (not {@code light.image}) because packing writes the
 * SWMR storage arrays directly, and those are protected to the engine's own package.
 */
public final class ImageLane {

    /** Off unless asked for; the fuse in docs/IMAGE-LANE-PLAN.md decides when it flips. */
    public static final boolean ENABLED = Boolean.getBoolean("scalablelux.imageLane");
    /** Per-settle trace (region, changes, materialized sections); off unless asked for. */
    public static final boolean LANE_DEBUG = Boolean.getBoolean("scalablelux.imageLaneDebug");

    // Region tile size: 4x4 core chunks plus a 1-chunk halo. The tile decides how many region settles a scattered
    // burst pays: border walks ~19 chunks, which at 1-chunk tiles meant 19 inits + 19 packs + 19 publishes a pass
    // (~0.7 ms of pure tax); at 4x4 the same walk collapses into 2-4 regions. Bigger tiles cost memory (4 planes of
    // (4+2)^2*16*384 bytes ≈ 8.8 MB each) and a longer first-init, both amortized over the tile lifetime.
    private static final int REGION_CHUNKS = Integer.getInteger("scalablelux.imageLaneRegionChunks", 1);
    private static final int HALO_CHUNKS = Integer.getInteger("scalablelux.imageLaneHaloChunks", 1);
    // Routing thresholds, property-tunable so the shape question can be probed without a rebuild (the border
    // attempt-cost split ran with imageLaneMaxChanges=0: the lane enabled but never taking traffic).
    private static final int LANE_MAX_CHANGES = Integer.getInteger("scalablelux.imageLaneMaxChanges", 2048);
    /** Below this, a single-region burst stays on the synchronous nibble path (sky_hole's shape). */
    private static final int LANE_MIN_CHANGES = Integer.getInteger("scalablelux.imageLaneMinChanges", 64);
    private static final long CACHE_BYTE_BUDGET = 128L * 1024L * 1024L;

    private static final ConcurrentHashMap<StarLightInterface, ImageLane> LANES = new ConcurrentHashMap<>();

    private final StarLightInterface owner;
    private final OwnedRegionImageCache cache = new OwnedRegionImageCache();
    private final ImageBlockLightEngine engine = new ImageBlockLightEngine();
    private final ImageMaterialCache materialCache = new ImageMaterialCache();
    private final Long2ObjectOpenHashMap<RuntimeLightChangeBuffer> pending = new Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet staleRegions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** Chunk keys the current settle took over; consulted by covers() after the buffer is drained. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet lastHandled = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int worldgenDepth;
    private long capturesSinceSettle;
    private long lastCaptureChunkKey = Long.MIN_VALUE;
    private ImageRegionBounds lastCaptureBounds;

    /** Live-world evidence that the lane is the one doing the work (surfaced through stats). */
    public long settleCount;
    public long capturedChanges;
    public long packedSections;
    public long materializedSections;
    public long matzThisSettle;
    public long initializedRegions;
    public long captureAttempts;
    public long captureAccepted;

    private ImageLane(final StarLightInterface owner) {
        this.owner = owner;
    }

    public static ImageLane forLevel(final StarLightInterface owner) {
        return LANES.computeIfAbsent(owner, o -> new ImageLane(o));
    }

    /** The lane for this level, or null when the lane is disabled, the provider is not ours, or this is a client world. */
    public static ImageLane laneOrNull(final ServerLevel level) {
        if (!ENABLED || !(level.getLightEngine() instanceof StarLightLightingProvider provider)) {
            return null;
        }
        return provider.scalablelux$getLightEngine().lucis$getImageLane();
    }

    public String laneStats() {
        return "attempts=" + this.captureAttempts + " accepted=" + this.captureAccepted + " settles=" + this.settleCount + " captured=" + this.capturedChanges
                + " packed=" + this.packedSections + " regions=" + this.initializedRegions
                + " pending=" + this.pending.size() + " cached=" + this.cache.size()
                + " matzSecs=" + this.materializedSections;
    }

    // capture (server thread, from ServerLevel.onBlockStateChange)
    // ------------------------------------------------------------------------------------------------------------

    public void beginWorldgenWrite() {
        this.worldgenDepth++;
    }

    public void endWorldgenWrite() {
        if (this.worldgenDepth > 0) {
            this.worldgenDepth--;
        }
    }

    public void onBlockStateChange(final ServerLevel level, final BlockPos pos,
                                   final BlockState oldState, final BlockState newState) {
        final long lucisCaptureT0 = System.nanoTime();
        try {
        this.onBlockStateChangeInner(level, pos, oldState, newState);
        } finally {
            LuxProfiler.laneCaptureNanos += System.nanoTime() - lucisCaptureT0;
        }
    }

    private void onBlockStateChangeInner(final ServerLevel level, final BlockPos pos,
                                         final BlockState oldState, final BlockState newState) {
        this.captureAttempts++;
        // captures land in plain thread-unsafe maps, and worldgen workers reach vanilla block writes too:
        // anything off the server main thread is not lane traffic
        if (!(this.owner.world instanceof ServerLevel serverLevel)
                || !serverLevel.getChunkSource().chunkMap.mainThreadExecutor.isSameThread()) {
            return;
        }
        if (this.worldgenDepth > 0) {
            return;
        }
        final ChunkPos chunkPos = new ChunkPos(pos);
        final ChunkAccess chunk = this.owner.getAnyChunkNow(chunkPos.x, chunkPos.z);

        if (chunk == null || !chunk.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.LIGHT)) {
            this.lastCaptureChunkKey = Long.MIN_VALUE;
            return;
        }
        if (!LightEngine.hasDifferentLightProperties(level, pos, oldState, newState)) {
            return;
        }

        // dense bursts arrive chunk-by-chunk (2048 changes in one chunk), so the per-change bounds construction
        // - a record allocation plus four level calls - is hoisted behind a chunk-key check
        final long chunkKey = ImageRegionBounds.regionKey(chunkPos.x, chunkPos.z);
        ImageRegionBounds bounds = this.lastCaptureChunkKey == chunkKey ? this.lastCaptureBounds : null;

        if (bounds == null) {
            bounds = ImageRegionBounds.around(chunkPos, level, REGION_CHUNKS, HALO_CHUNKS);
            this.lastCaptureBounds = bounds;
            this.lastCaptureChunkKey = chunkKey;
        }
        final int localX = pos.getX() - bounds.minBlockX();
        final int localY = pos.getY() - bounds.minBuildY();
        final int localZ = pos.getZ() - bounds.minBlockZ();

        if (localX < 0 || localX >= bounds.widthBlocks() || localZ < 0 || localZ >= bounds.depthBlocks()
                || localY < 0 || localY >= bounds.heightBlocks()) {
            return;
        }

        final int index = (localY + 1) * bounds.paddedArea() + (localZ + 1) * bounds.paddedWidth() + (localX + 1);
        final int oldPacked = this.materialCache.lookupLight(level, oldState, pos);
        final int newPacked = this.materialCache.lookupLight(level, newState, pos);

        if (ImageMaterial.hasSameLight(oldPacked, newPacked)) {
            return;
        }

        this.pending.computeIfAbsent(bounds.coreRegionKey(), key -> new RuntimeLightChangeBuffer())
                .addMaterial(index, oldPacked, newPacked);
        this.captureAccepted++;
        this.capturesSinceSettle++;
    }

    // ------------------------------------------------------------------------------------------------------------
    // settle (server thread, from the flush)
    // ------------------------------------------------------------------------------------------------------------

    public boolean hasPending() {
        return !this.pending.isEmpty();
    }

    /**
     * The routing predicate, shared by the flush (which chunks' block half the lane takes) and the settle (which
     * regions the lane applies). A region qualifies when its whole burst stays under {@link #LANE_MAX_CHANGES}:
     * the lane then owns EVERY captured change in it. There is deliberately no per-chunk floor any more: a region
     * whose burst is small would otherwise be dispatched to the base queue, and the base task executes on the
     * light thread with seeds captured BEFORE the lane's settle - its decrease wave zeroes the lane's field in
     * its window and rebuilds only its own seeds' contribution (measured: the gate's patch read 1/0/0 after the
     * lane had packed 15s). Correctness first: the lane handles what it captured, the base queue never touches
     * a lane-adopted region. structure_cube (4096 > the cap) stays on the grouped nibble path, which is
     * synchronous in the same flush and therefore race-free.
     */
    private boolean regionQualifies(final ImageRegionBounds bounds, final RuntimeLightChangeBuffer buffered,
                                    final long flushTotalIgnored) {
        if (buffered == null || buffered.isEmpty() || buffered.size() > LANE_MAX_CHANGES) {
            return false;
        }

        // THE rule, and it took a diagnostic to find it: the lane takes a flush only when the flush carries at
        // least LANE_MIN_CHANGES captured changes in this region or in total. Per-region rules alone kept letting
        // sky_hole's worldgen neighbours in (59 regions of 1-6 changes each: 172 settles, 2208 section
        // extractions, while the workload's own burst is 25 changes), and the lane's per-region machinery -
        // settle, pack, publish, external re-adopts - costs more than the grouped nibble path saves on a tiny
        // burst. Border carries 95 (lane wins there, 3.9 against ~4.5), dense 2048 (lane wins), sky_hole 25 and
        // its neighbours under 64 (nibble wins). This is not the queue: the grouped nibble path runs inline in
        // the same flush, so the async overwrite exclusive ownership exists to prevent cannot happen.
        //
        // A region the lane adopted earlier can be handed BACK: the skip branch drops its state and marks it
        // stale, so nothing stale is ever read and the nibble path owns the changes from then on. Keeping it
        // lane-owned forever was an earlier rule, and it is what made sky_hole stay slow once a warmup burst had
        // adopted its region.
        //
        // The rule is PER REGION, and the two measurements that pinned it down: a flush total wide enough to admit
        // border (95 changes over 19 regions, ~5 each) makes the lane pay its per-region machinery - and now that
        // materialization decodes its indices correctly, that machinery costs 6.7 ms a pass against the grouped
        // nibble path's ~4.5 - while the region rule keeps the lane for what it is actually good at: dense's 2048
        // changes in ONE chunk (2.8-3.4 ms against ScalableLux's 3.9). sky_hole's 25 changes per region and
        // border's ~5 take the nibble path.
        if (buffered.size() < LANE_MIN_CHANGES) {
            return false;
        }

        // Neighbourhood must be past LIGHT. A region whose 3x3 neighbourhood is still generating takes worldgen
        // writes (features into loaded chunks fire the capture funnel), and lan-ing those costs the lane's
        // per-region machinery while the base engine is already handling that chunk's light - measured as 1.2-1.7
        // ms of materialization plus 0.7-1.0 ms of external re-adopts PER PASS on sky_hole, whose box sits inside
        // the harness's still-generating ring. Generating chunks stay on the base engine's own path, exactly as
        // they are with the lane off.
        final int tileX = (int) (bounds.coreRegionKey() & 0xFFFFFFFFL);
        final int tileZ = (int) (bounds.coreRegionKey() >> 32);

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final ChunkAccess neighbour = this.owner.getAnyChunkNow(tileX + dx, tileZ + dz);

                if (neighbour == null
                        || !neighbour.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.LIGHT)) {
                    return false;
                }
            }
        }

        return true;
    }

    public boolean covers(final int chunkX, final int chunkZ) {
        final long key = ca.spottedleaf.starlight.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ);
        if (this.lastHandled.contains(key)) {
            return true; // settled this flush: the buffer is drained but the lane owns this chunk's block half
        }
        if (this.pending.isEmpty()) {
            return false;
        }
        final ServerLevel level = (ServerLevel) this.owner.world;
        final ImageRegionBounds bounds =
                ImageRegionBounds.around(new ChunkPos(chunkX, chunkZ), level, REGION_CHUNKS, HALO_CHUNKS);

        // The SAME flush total the settle uses. Passing 0 here made a region whose own buffer is under the
        // minimum report "not covered" while the settle's flushTotal let it through - so the flush seeded the
        // block half on the nibble path AND the lane settled it: both paths did the work (border 4.3 -> 7.7-12.0,
        // dense 2.8 -> 10.9 in the same window).
        long flushTotal = 0L;

        for (final RuntimeLightChangeBuffer buffered : this.pending.values()) {
            flushTotal += buffered.size();
        }

        return this.regionQualifies(bounds, this.pending.get(bounds.coreRegionKey()), flushTotal);
    }

    public void settle() {
        LuxProfiler.laneSettlePolls++;
        // Cheap gate first: the harness barrier polls hasUpdates() in a spin loop, and this settle sits behind
        // every poll. With no captures since the last settle there is nothing to decide - the attempt itself
        // measured 4-5 ms a pass on border (40-50 us per change) purely from being polled thousands of times.
        if (this.capturesSinceSettle == 0L) {
            return;
        }
        final long lucisSettleT0 = System.nanoTime();
        try {
            this.settleInner();
        } finally {
            LuxProfiler.laneSettleNanos += System.nanoTime() - lucisSettleT0;
            LuxProfiler.laneSettleRuns++;
        }
    }

    private void settleInner() {
        this.lastHandled.clear();
        // hasUpdates() is also asked from the light thread, where none of this may run; the buffer simply waits
        // for the next server-thread settle point (the tick hook guarantees one per tick)
        if (!(this.owner.world instanceof ServerLevel serverLevel)
                || !serverLevel.getChunkSource().chunkMap.mainThreadExecutor.isSameThread()) {
            return;
        }
        final ServerLevel level = (ServerLevel) this.owner.world;
        final it.unimi.dsi.fastutil.longs.LongArrayList handledKeys = new it.unimi.dsi.fastutil.longs.LongArrayList();
        final it.unimi.dsi.fastutil.longs.LongArrayList skippedKeys = new it.unimi.dsi.fastutil.longs.LongArrayList();

        // The flush's total captured changes, which is what decides whether the lane is worth using at all. The
        // sky_hole diagnostic is why this exists: with only per-region rules the lane was settling 59 different
        // regions of 1-6 changes each (worldgen writes near the box) - 172 settles and 2208 section extractions -
        // while the workload's own burst is 25 changes. Border carries 95 (lane wins), dense 2048 (lane wins),
        // sky_hole 25 and its worldgen neighbours under 64 (nibble path wins). One number, both shapes.
        long totalCaptured = 0L;

        for (final RuntimeLightChangeBuffer buffered : this.pending.values()) {
            totalCaptured += buffered.size();
        }
        final long flushTotal = totalCaptured;

        for (final it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<RuntimeLightChangeBuffer> entry
                : this.pending.long2ObjectEntrySet()) {
            final RuntimeLightChangeBuffer buffer = entry.getValue();

            if (buffer.isEmpty()) {
                skippedKeys.add(entry.getLongKey());
                continue;
            }

            final ImageRegionBounds bounds = this.boundsFor(level, entry.getLongKey());

            // routing: a region whose burst is not concentrated stays entirely on the grouped nibble path. The
            // nibble path applies those changes in this very flush (their pendingEdits entries were never
            // removed), so the lane buffer entries are dead weight AND would poison the region planes - drop
            // them and mark the region stale, forcing a full re-adopt before any future qualifying settle.
            // Destructive on purpose: a buffer kept across passes GROWS (95 toggles a pass), its per-chunk
            // counts eventually cross the floor, and the region then qualifies on its own dead entries
            // mid-window (measured: a 21 ms re-adopt inside a measured pass). The dirty gate above keeps the
            // hasUpdates polling cheap, so destruction costs nothing here.
            if (!this.regionQualifies(bounds, buffer, flushTotal)) {
                skippedKeys.add(entry.getLongKey());
                continue;
            }

            final RuntimeRegionImageState state;

            if (this.staleRegions.remove(entry.getLongKey())) {
                // skipped settles let the nibble path apply changes we never mirrored: re-adopt everything
                this.cache.remove(entry.getLongKey());
                state = this.cache.getOrCreate(bounds);
            } else {
                state = this.cache.getOrCreate(bounds);
            }
            final ImageRegionData data = state.data();

            if (!state.initialized()) {
                // a fresh region materializes nothing eagerly; the sections its changes can reach are adopted
                // by materializeForChanges below, and stay current through the external-mark re-adopts
                state.markInitialized(level);
                this.initializedRegions++;
            }

            // sections a neighbouring region's job published since our last settle: re-adopt before computing
            final long lucisExtT0 = System.nanoTime();
            for (final RuntimeRegionImageState.ExternalSection external : state.drainExternalSections()) {
                this.adoptSection(data, external.packedSectionPos());
            }
            LuxProfiler.laneExternalNanos += System.nanoTime() - lucisExtT0;

            final long lucisMatzT0 = System.nanoTime();
            this.matzThisSettle = 0L;
            this.materializeForChanges(data, level, buffer);
            LuxProfiler.laneMaterializeNanos += System.nanoTime() - lucisMatzT0;
            if (LANE_DEBUG) {
            System.out.println("SETTLEDBG region=" + entry.getLongKey() + " changes=" + buffer.size()
                    + " matzSections=" + this.matzThisSettle + " cached=" + this.cache.size()
                    + " totalMatz=" + this.materializedSections);
            }

            final long lucisBfsT0 = System.nanoTime();
            this.engine.applyChanges(data, buffer);
            LuxProfiler.laneBfsNanos += System.nanoTime() - lucisBfsT0;

            this.settleCount++;
            this.capturedChanges += buffer.size();

            final long lucisPackT0 = System.nanoTime();
            this.packDirty(data, entry.getLongKey());
            LuxProfiler.lanePackNanos += System.nanoTime() - lucisPackT0;

            if (this.settleCount == 1) {
                // TEMP diagnostic: did the BFS light the region it was handed?
                int emitters = 0;
                int lit10 = 0;
                int maxLight = 0;
                int airCells = 0;
                for (int i = 0; i < data.paddedVolume; i++) {
                    final int em = data.emission[i] & 0xF;
                    if (em >= 14) {
                        emitters++;
                    }
                    final int levelV = data.blockLight[i] & 0xF;
                    if (levelV > maxLight) {
                        maxLight = levelV;
                    }
                    if (levelV >= 10) {
                        lit10++;
                    }
                    if (data.opacity[i] == 0 && em == 0) {
                        airCells++;
                    }
                }
                System.out.println("LANEDUMP key=" + entry.getLongKey() + " changes=" + buffer.size()
                        + " emitters14=" + emitters + " lit10=" + lit10 + " maxLight=" + maxLight
                        + " airCells=" + airCells + " opacityMaxAtEmitters="
                        + data.opacity[data.blockLight.length - 1]);
                System.out.println("LANEDUMP2 opacity0-15hist:");
                final int[] hist = new int[16];
                for (int i = 0; i < data.paddedVolume; i++) {
                    hist[data.opacity[i] & 0xF]++;
                }
                for (int v = 0; v < 16; v++) {
                    if (hist[v] > 0) {
                        System.out.println("  opacity=" + v + " cells=" + hist[v]);
                    }
                }
                // TEMP: region row vs packed nibble row along the emitter line (world y=-37, z=-16)
                final ChunkAccess dumpChunk = this.owner.getAnyChunkNow(0, -1);
                if (dumpChunk != null) {
                    final SWMRNibbleArray[] dn = ((ExtendedChunk) dumpChunk).scalablelux$getBlockNibbles();
                    final SWMRNibbleArray dNib = dn[1];
                    final int ly = 27, lz = 0, rowY = ly + 1;
                    final StringBuilder regionRow = new StringBuilder("LANEDUMP region-row x=12..27: ");
                    final StringBuilder nibbleRow = new StringBuilder("LANEDUMP nibble-row x=12..27: ");
                    for (int x = 12; x <= 27; x++) {
                        final int ri = (rowY) * data.paddedArea + (lz + 1) * data.paddedWidth + (x + 1);
                        regionRow.append(data.blockLight[ri] & 0xF).append(',');
                        if (dNib != null && dNib.isInitialisedUpdating()) {
                            nibbleRow.append(dNib.getUpdating((ly & 15) << 8 | (lz & 15) << 4 | (x & 15))).append(',');
                        }
                    }
                    System.out.println(regionRow.toString());
                    System.out.println(nibbleRow.toString());
                }
            }
            buffer.clear();

            // every chunk of this region is now lane-owned for the flush that follows
            final int minChunkX = bounds.minBlockX() >> 4, minChunkZ = bounds.minBlockZ() >> 4;
            for (int dz = -HALO_CHUNKS; dz < bounds.regionChunks() + HALO_CHUNKS; dz++) {
                for (int dx = -HALO_CHUNKS; dx < bounds.regionChunks() + HALO_CHUNKS; dx++) {
                    this.lastHandled.add(ca.spottedleaf.starlight.common.util.CoordinateUtils.getChunkKey(
                            minChunkX + dx, minChunkZ + dz));
                }
            }
            handledKeys.add(entry.getLongKey());
        }

        // removals happen after the iteration: a map.remove inside the fastutil entry iteration corrupts the
        // iterator and crashes with an out-of-bounds table index (measured, the r1 crash)
        for (int i = 0; i < handledKeys.size(); i++) {
            this.pending.remove(handledKeys.getLong(i));
        }
        for (int i = 0; i < skippedKeys.size(); i++) {
            final long key = skippedKeys.getLong(i);
            final RuntimeLightChangeBuffer dropped = this.pending.remove(key);

            if (dropped != null && !dropped.isEmpty()) {
                // the nibble path owns these changes; the region's planes fall behind the world
                this.cache.remove(key);
                this.staleRegions.add(key);
            }
        }
        this.capturesSinceSettle = 0L;
        this.cache.trimToSize(-1, CACHE_BYTE_BUDGET);
    }

    private ImageRegionBounds boundsFor(final ServerLevel level, final long regionKey) {
        final int chunkX = (int) regionKey;
        final int chunkZ = (int) (regionKey >> 32);
        return ImageRegionBounds.around(new ChunkPos(chunkX, chunkZ), level, REGION_CHUNKS, HALO_CHUNKS);
    }

    // ------------------------------------------------------------------------------------------------------------
    // region init: materials from the chunk sections, light from the nibbles
    // ------------------------------------------------------------------------------------------------------------

    private void initialize(final ImageRegionData data, final ServerLevel level) {
        // Lazy materialization: a fresh region adopts NOTHING. Each settle materializes only the sections within
        // 16 blocks of its own changes (light travels ≤15, so the BFS can never reach an unmaterialized cell), and
        // materialized sections stay current across settles through the external-mark re-adopts. This is what
        // makes scattered traffic affordable: border's 19 chunks materialize ~60 sections instead of eagerly
        // adopting all 216 + extracting hundreds of thousands of block states.
        data.clearDirty();
    }

    /** Materializes (adopts light + extracts materials) every section within reach of the buffered changes. */
    private void materializeForChanges(final ImageRegionData data, final ServerLevel level,
                                       final RuntimeLightChangeBuffer buffer) {
        final BitSet reach = new BitSet(data.sectionsPerPlane * ((data.bounds.sectionCount() + 31) >> 5) * 32);
        final int pw = data.paddedWidth;
        final int pd = data.paddedDepth;
        final int area = data.paddedArea;
        final byte[] light = data.blockLight;

        for (int i = 0; i < buffer.size(); i++) {
            // the buffer's indices are PADDED (the moat is one cell thick): decode with the PADDED strides, then
            // subtract the pad on every axis to get the real-local coordinate the reach box is built from
            final int index = RuntimeLightChangeBuffer.localIndex(buffer.get(i));
            final int x = index % pw - 1, z = (index / pw) % pd - 1, y = index / area - 1;
            data.markSectionsWithinReach(x, y, z, 16, reach);
        }

        final int minChunkX = data.bounds.minBlockX() >> 4;
        final int minChunkZ = data.bounds.minBlockZ() >> 4;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int linear = reach.nextSetBit(0); linear >= 0; linear = reach.nextSetBit(linear + 1)) {
            if (data.isLightMaterialized(linear)) {
                continue;
            }
            final int sectionY = data.bounds.minSectionY() + linear / data.sectionsPerPlane;
            final int rem = linear % data.sectionsPerPlane;
            final int chunkX = minChunkX + (rem % data.sectionWidth);
            final int chunkZ = minChunkZ + (rem / data.sectionWidth);
            final int sectionIndex = linear / data.sectionsPerPlane;
            this.materializeSection(data, level, chunkX, chunkZ, sectionY, sectionIndex, pos);
            data.markLightMaterialized(linear);
            this.materializedSections++;
            this.matzThisSettle++;
        }
    }

    /** Adopts one section's light from the nibbles and extracts its materials from the chunk's states. */
    private void materializeSection(final ImageRegionData data, final ServerLevel level,
                                    final int chunkX, final int chunkZ, final int sectionY, final int sectionIndex,
                                    final BlockPos.MutableBlockPos pos) {
        final int cx = chunkX - (data.bounds.minBlockX() >> 4);
        final int cz = chunkZ - (data.bounds.minBlockZ() >> 4);
        final ChunkAccess chunk = this.owner.getAnyChunkNow(chunkX, chunkZ);

        if (chunk == null) {
            // unloaded halo reads as air/dark: the base engine's own cache-miss semantics. Marked materialized
            // so we do not retry every settle; when the chunk loads, the base engine's light() fires the
            // external-mark path and the section re-adopts.
            data.markLightMaterialized(data.sectionLinearIndex(chunkX << 4, sectionY, chunkZ << 4));
            return;
        }

        final SWMRNibbleArray[] nibbles = ((ExtendedChunk) chunk).scalablelux$getBlockNibbles();
        final LevelChunkSection[] sections = chunk.getSections();

        // light: mirror the authoritative nibbles (absent storage reads as zero)
        final SWMRNibbleArray nibble = sectionIndex < nibbles.length ? nibbles[sectionIndex] : null;
        if (nibble != null && nibble.isInitialisedUpdating()) {
            data.adoptSectionData(cx, cz, sectionIndex, nibble.storageUpdating, false);
        }

        // materials: air sections are zero already; anything else comes from the CHUNK-LEVEL material plane,
        // which is extracted once per section and reused by every region that needs it (a scattered change
        // reaches sections that up to nine neighbouring regions materialized separately, and each pass reaches
        // new y bands - measured as ~760 section extractions a pass on border, ~10 ms of the settle)
        final LevelChunkSection section = sectionIndex < sections.length ? sections[sectionIndex] : null;
        if (section != null && !section.hasOnlyAir()) {
            final ImageMaterialPlanes.Plane plane = ImageMaterialPlanes.get(level, section, chunkX, sectionY, chunkZ,
                    this.materialCache, pos);
            this.copyPlane(data, plane, chunkX, chunkZ, sectionIndex);
        }
    }

    /** Copies a plane's 4096 cells into the region's padded planes. Two array-level loops, no per-cell lookups. */
    private void copyPlane(final ImageRegionData data, final ImageMaterialPlanes.Plane plane,
                           final int chunkX, final int chunkZ, final int localSection) {
        final int baseX = (chunkX << 4) + 1;
        final int baseZ = (chunkZ << 4) + 1;
        final int baseY = (localSection << 4) + 1;
        final int width = data.paddedWidth;
        final int area = data.paddedArea;
        final byte[] opacity = data.opacity;
        final byte[] emission = data.emission;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                final int rowBase = (baseY + y) * area + (baseZ + z) * width + baseX;
                final int planeBase = (y << 8) | (z << 4);

                System.arraycopy(plane.opacity, planeBase, opacity, rowBase, 16);
                System.arraycopy(plane.emission, planeBase, emission, rowBase, 16);
            }
        }
    }


    /** Re-adopts one section's light from the authoritative nibbles (external invalidation). */
    private void adoptSection(final ImageRegionData data, final long packedSectionPos) {
        final SectionPos sp = SectionPos.of(packedSectionPos);
        final ChunkAccess chunk = this.owner.getAnyChunkNow(sp.x(), sp.z());

        if (chunk == null) {
            return;
        }
        final SWMRNibbleArray[] nibbles = ((ExtendedChunk) chunk).scalablelux$getBlockNibbles();
        final int sectionIndex = sp.y() - data.bounds.minSectionY();

        if (sectionIndex < 0 || sectionIndex >= nibbles.length) {
            return;
        }
        final SWMRNibbleArray nibble = nibbles[sectionIndex];

        if (nibble != null && nibble.isInitialisedUpdating()) {
            data.adoptSectionData(sp.x() - (data.bounds.minBlockX() >> 4),
                    sp.z() - (data.bounds.minBlockZ() >> 4), sectionIndex, nibble.storageUpdating, false);
        } else {
            // the section's light was reset on the engine side: clear our copy
            final int minX = (sp.x() << 4) - data.bounds.minBlockX();
            final int minZ = (sp.z() << 4) - data.bounds.minBlockZ();
            final int baseY = (sectionIndex << 4) + 1;
            final int width = data.paddedWidth;
            final int area = data.paddedArea;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    final int rowBase = (baseY + y) * area + (minZ + z) * width + minX;
                    java.util.Arrays.fill(data.blockLight, rowBase, rowBase + 16, (byte) 0);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // pack: dirty sections back into the nibbles, then the publish callback
    // ------------------------------------------------------------------------------------------------------------

    private void packDirty(final ImageRegionData data, final long ownerRegionKey) {
        final BitSet dirty = data.dirtyBlockSections;

        for (int linear = dirty.nextSetBit(0); linear >= 0; linear = dirty.nextSetBit(linear + 1)) {
            final SectionPos sp = data.sectionPosFromLinear(linear);
            final ChunkAccess chunk = this.owner.getAnyChunkNow(sp.x(), sp.z());

            if (chunk != null) {
                final SWMRNibbleArray[] nibbles = ((ExtendedChunk) chunk).scalablelux$getBlockNibbles();
                final int sectionIndex = sp.y() - data.bounds.minSectionY();

                if (sectionIndex >= 0 && sectionIndex < nibbles.length) {
                    final SWMRNibbleArray nibble = nibbles[sectionIndex];

                    if (nibble != null && this.writeSection(nibble, data, sp.x(), sp.z(), sectionIndex)) {
                        // the reader side (client packets, saves) reads the visible layer: sync it before publishing
                        this.packedSections++;
                        nibble.updateVisible();
                        this.owner.lightAccess.onLightUpdate(LightLayer.BLOCK, sp);
                    }
                }
            }

            // the home region of this section (its own core tile) must re-adopt before its next job
            final long homeKey = ImageRegionBounds.regionKey(sp.x(), sp.z());
            if (homeKey != ownerRegionKey) {
                final RuntimeRegionImageState home = this.cache.getInitialized(homeKey);

                if (home != null) {
                    home.markExternalSection(sp.asLong(), false);
                }
            }
        }
        dirty.clear();
    }

    /** Writes the region's light for one section through the nibble's own API (which allocates for null storage and
     * keeps the SWMR dirty state); returns whether anything was written. The caller then syncs the visible layer. */
    private boolean writeSection(final SWMRNibbleArray nibble, final ImageRegionData data,
                                 final int chunkX, final int chunkZ, final int sectionIndex) {
            final int minX = (chunkX << 4) - data.bounds.minBlockX() + 1;
            final int minZ = (chunkZ << 4) - data.bounds.minBlockZ() + 1;
            final int baseY = (sectionIndex << 4) + 1;
            final int width = data.paddedWidth;
            final int area = data.paddedArea;
        final byte[] light = data.blockLight;

        boolean any = false;
        for (int y = 0; y < 16 && !any; y++) {
            final int yBase = (baseY + y) * area;
            for (int z = 0; z < 16 && !any; z++) {
                final int rowBase = yBase + (minZ + z) * width + minX;
                for (int x = 0; x < 16; x++) {
                    if ((light[rowBase + x] & 0xF) != 0) {
                        any = true;
                        break;
                    }
                }
            }
        }

        if (!any) {
            // all dark in the region: if the section holds no storage, there is nothing to write and nothing to
            // allocate; if it does hold storage, the zeroing below still has to run
            if (!nibble.isInitialisedUpdating()) {
                return false;
            }
        } else if (!nibble.isInitialisedUpdating()) {
            // allocate the storage and mark the array dirty through the owning API; everything below then
            // overwrites the whole 2048-byte array, so the one-cell write is purely the allocation handle
            nibble.set(0, light[baseY * area + minZ * width + minX] & 0xF);
        }

        // paired direct write: the section's cells are fully owned by the region, so each packed byte carries two
        // region levels (even x low, odd x high - the inverse of adoptSectionData). One array pass instead of 4096
        // set() calls measured as the difference between a 2-3 ms and a ~0.5 ms pack on dense_chunk_patch.
        final byte[] packed = nibble.storageUpdating;
        for (int y = 0; y < 16; y++) {
            final int yBase = (baseY + y) * area;
            for (int z = 0; z < 16; z++) {
                final int rowBase = yBase + (minZ + z) * width + minX;
                final int packedRow = ((y << 8) | (z << 4)) >> 1;
                for (int x = 0; x < 16; x += 2) {
                    packed[packedRow + (x >> 1)] =
                            (byte) ((light[rowBase + x] & 0xF) | ((light[rowBase + x + 1] & 0xF) << 4));
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------
    // external invalidation: anything that writes block light outside this lane marks sections for re-adopt
    // ------------------------------------------------------------------------------------------------------------

    /** Called from the base engine's write paths (any thread): this chunk's block light changed underneath us. */
    public static void onEngineBlockWrite(final int chunkX, final int chunkZ) {
        // Mark, don't evict. The first cut evicted the nine overlapping regions, and worldgen's light stages -
        // which call this for every chunk they light - kept re-initializing the workload's region from scratch
        // (~8 ms a pop), which showed up as 23 ms passes on sky_hole. An external mark instead re-adopts just the
        // written chunk's sections at the next settle (~0.5 ms), which is all the correctness requires.
        for (final ImageLane lane : LANES.values()) {
            lane.markEngineWrite(chunkX, chunkZ);
        }
    }

    private void markEngineWrite(final int chunkX, final int chunkZ) {
        // the chunk's own tile and the eight neighbours whose halo covers it
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int tileX = Math.floorDiv(chunkX + dx, REGION_CHUNKS);
                final int tileZ = Math.floorDiv(chunkZ + dz, REGION_CHUNKS);
                final RuntimeRegionImageState state =
                        this.cache.getInitialized(ImageRegionBounds.regionKey(tileX, tileZ));

                if (state == null) {
                    continue;
                }
                for (int sectionY = this.owner.minSection; sectionY <= this.owner.maxSection; sectionY++) {
                    state.markExternalSection(SectionPos.of(chunkX, sectionY, chunkZ).asLong(), false);
                }
            }
        }
    }
}
