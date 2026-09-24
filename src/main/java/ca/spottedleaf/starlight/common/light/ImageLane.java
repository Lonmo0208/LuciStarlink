package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.debug.LuxProfiler;
import ca.spottedleaf.starlight.common.light.image.ImageBlockLightEngine;
import ca.spottedleaf.starlight.common.light.image.ImageMaterial;
import ca.spottedleaf.starlight.common.light.image.ImageMaterialCache;
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

    private static final int REGION_CHUNKS = 1;
    private static final int HALO_CHUNKS = 1;
    // Routing thresholds, property-tunable so the shape question can be probed without a rebuild (the border
    // attempt-cost split ran with imageLaneMaxChanges=0: the lane enabled but never taking traffic).
    private static final int LANE_MAX_CHANGES = Integer.getInteger("scalablelux.imageLaneMaxChanges", 2048);
    private static final int LANE_MIN_CHANGES_PER_CHUNK = Integer.getInteger("scalablelux.imageLaneMinPerChunk", 64);
    private static final long CACHE_BYTE_BUDGET = 128L * 1024L * 1024L;

    private static final ConcurrentHashMap<StarLightInterface, ImageLane> LANES = new ConcurrentHashMap<>();

    private final StarLightInterface owner;
    private final OwnedRegionImageCache cache = new OwnedRegionImageCache();
    private final ImageBlockLightEngine engine = new ImageBlockLightEngine();
    private final ImageMaterialCache materialCache = new ImageMaterialCache();
    private final Long2ObjectOpenHashMap<RuntimeLightChangeBuffer> pending = new Long2ObjectOpenHashMap<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet staleRegions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap qualifyCache = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** Chunk keys the current settle took over; consulted by covers() after the buffer is drained. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet lastHandled = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int worldgenDepth;
    private long capturesSinceSettle;

    /** Live-world evidence that the lane is the one doing the work (surfaced through stats). */
    public long settleCount;
    public long capturedChanges;
    public long packedSections;
    public long initializedRegions;

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
        return "settles=" + this.settleCount + " captured=" + this.capturedChanges
                + " packed=" + this.packedSections + " regions=" + this.initializedRegions
                + " pending=" + this.pending.size() + " cached=" + this.cache.size();
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
            return;
        }
        if (!LightEngine.hasDifferentLightProperties(level, pos, oldState, newState)) {
            return;
        }

        final ImageRegionBounds bounds = ImageRegionBounds.around(chunkPos, level, REGION_CHUNKS, HALO_CHUNKS);
        final int localX = pos.getX() - bounds.minBlockX();
        final int localY = pos.getY() - bounds.minBuildY();
        final int localZ = pos.getZ() - bounds.minBlockZ();

        if (localX < 0 || localX >= bounds.widthBlocks() || localZ < 0 || localZ >= bounds.depthBlocks()
                || localY < 0 || localY >= bounds.heightBlocks()) {
            return;
        }

        final int index = localX + localZ * bounds.widthBlocks() + localY * bounds.area();
        final int oldPacked = this.materialCache.lookupLight(level, oldState, pos);
        final int newPacked = this.materialCache.lookupLight(level, newState, pos);

        if (ImageMaterial.hasSameLight(oldPacked, newPacked)) {
            return;
        }

        this.pending.computeIfAbsent(bounds.coreRegionKey(), key -> new RuntimeLightChangeBuffer())
                .addMaterial(index, oldPacked, newPacked);
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
     * regions the lane applies). A region qualifies only when its burst is CONCENTRATED: some chunk carries at
     * least {@link #LANE_MIN_CHANGES_PER_CHUNK} changes and the region stays under {@link #LANE_MAX_CHANGES}.
     * The lane's per-region fixed cost (pack, publish, re-adopts, ~0.5-1 ms) only pays for concentrated traffic;
     * measured on the harness shapes: dense (2048 in one chunk) wins by ~25% through the lane, while border
     * (~5 per chunk over 19 chunks), tiny bursts (sky_hole's 25) and huge ones (structure's 4096, above the cap)
     * are cheaper on the grouped nibble path.
     */
    private boolean regionQualifies(final ImageRegionBounds bounds, final RuntimeLightChangeBuffer buffered) {
        if (buffered == null || buffered.isEmpty() || buffered.size() > LANE_MAX_CHANGES) {
            return false;
        }
        // decision cache: the buffer only grows between settles, so (size -> verdict) is a sound memo and
        // hasUpdates() polls during the wait must not rescan the whole buffer every call
        final long cacheKey = bounds.coreRegionKey();
        final long cached = this.qualifyCache.get(cacheKey);
        if (cached != 0L && (cached >>> 1) == buffered.size()) {
            return (cached & 1L) != 0L;
        }
        final int minChunkX = bounds.minBlockX() >> 4, minChunkZ = bounds.minBlockZ() >> 4;
        final int width = bounds.widthBlocks();
        final int depth = bounds.depthBlocks();
        final int area = bounds.area();

        // count changes per chunk; the cap bounds the sweep
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap perChunk =
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        for (int i = 0; i < buffered.size(); i++) {
            final int index = RuntimeLightChangeBuffer.localIndex(buffered.get(i));
            final int x = index % width, z = (index / width) % depth, y = index / area;
            final long chunkKey = ImageRegionBounds.regionKey(minChunkX + (x >> 4), minChunkZ + (z >> 4));
            perChunk.addTo(chunkKey, 1);
        }
        boolean verdict = false;
        for (final it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e : perChunk.long2IntEntrySet()) {
            if (e.getIntValue() >= LANE_MIN_CHANGES_PER_CHUNK) {
                verdict = true;
                break;
            }
        }
        this.qualifyCache.put(cacheKey, ((long) buffered.size() << 1) | (verdict ? 1L : 0L));
        return verdict;
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
        return this.regionQualifies(bounds, this.pending.get(bounds.coreRegionKey()));
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
            if (!this.regionQualifies(bounds, buffer)) {
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
                this.initialize(data, level);
                state.markInitialized(level);
                this.initializedRegions++;
            }

            // sections a neighbouring region's job published since our last settle: re-adopt before computing
            for (final RuntimeRegionImageState.ExternalSection external : state.drainExternalSections()) {
                this.adoptSection(data, external.packedSectionPos());
            }

            this.engine.applyChanges(data, buffer);
            this.settleCount++;
            this.capturedChanges += buffer.size();
            this.packDirty(data, entry.getLongKey());
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
        final ImageRegionBounds bounds = data.bounds;
        final int minChunkX = bounds.minBlockX() >> 4;
        final int minChunkZ = bounds.minBlockZ() >> 4;
        final int chunksX = bounds.widthBlocks() >> 4;
        final int chunksZ = bounds.depthBlocks() >> 4;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int cz = 0; cz < chunksZ; cz++) {
            for (int cx = 0; cx < chunksX; cx++) {
                final int chunkX = minChunkX + cx;
                final int chunkZ = minChunkZ + cz;
                final ChunkAccess chunk = this.owner.getAnyChunkNow(chunkX, chunkZ);

                if (chunk == null) {
                    continue; // unloaded halo reads as air/dark: the base engine's own cache-miss semantics
                }

                final SWMRNibbleArray[] nibbles = ((ExtendedChunk) chunk).scalablelux$getBlockNibbles();
                final LevelChunkSection[] sections = chunk.getSections();

                for (int sectionIndex = 0; sectionIndex < nibbles.length && sectionIndex < bounds.sectionCount(); sectionIndex++) {
                    final SWMRNibbleArray nibble = nibbles[sectionIndex];
                    final int sectionY = bounds.minSectionY() + sectionIndex;

                    // light: mirror the authoritative nibbles (absent storage reads as zero)
                    if (nibble != null && nibble.isInitialisedUpdating()) {
                        data.adoptSectionData(cx, cz, sectionIndex, nibble.storageUpdating, false);
                    }
                    data.markLightMaterialized(data.sectionLinearIndex(chunkX << 4, sectionY, chunkZ << 4));

                    // materials: air sections are zero already; anything else is read cell by cell, once
                    final LevelChunkSection section = sectionIndex < sections.length ? sections[sectionIndex] : null;
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    this.extractMaterials(data, level, chunkX, chunkZ, sectionY, section, pos);
                }
            }
        }
        data.clearDirty();
    }

    private void extractMaterials(final ImageRegionData data, final ServerLevel level,
                                  final int chunkX, final int chunkZ, final int sectionY,
                                  final LevelChunkSection section, final BlockPos.MutableBlockPos pos) {
        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;
        final int baseY = sectionY << 4;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(baseX + x, baseY + y, baseZ + z);
                    final BlockState state = section.getBlockState(x, y, z);
                    final int packed = this.materialCache.lookupLight(level, state, pos);
                    final int index = data.index(baseX + x, baseY + y, baseZ + z);

                    data.opacity[index] = ImageMaterial.opacity(packed);
                    data.emission[index] = ImageMaterial.emission(packed);
                }
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
            final int baseY = sectionIndex << 4;
            final int width = data.bounds.widthBlocks();
            final int area = data.bounds.area();
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
        final int minX = (chunkX << 4) - data.bounds.minBlockX();
        final int minZ = (chunkZ << 4) - data.bounds.minBlockZ();
        final int baseY = sectionIndex << 4;
        final int width = data.bounds.widthBlocks();
        final int area = data.bounds.area();
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
        }

        for (int y = 0; y < 16; y++) {
            final int yBase = (baseY + y) * area;
            for (int z = 0; z < 16; z++) {
                final int rowBase = yBase + (minZ + z) * width + minX;
                final int packedBase = (y << 8) | (z << 4);
                for (int x = 0; x < 16; x++) {
                    nibble.set(packedBase | x, light[rowBase + x] & 0xF);
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
