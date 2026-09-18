package dev.lucistarlink.light.engine;

import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.light.LightMaterial;
import dev.lucistarlink.light.LightMaterialCache;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.region.RuntimeRegionState;
import dev.lucistarlink.light.runtime.LuxRelightResult;
import dev.lucistarlink.light.runtime.LuxSectionData;
import dev.lucistarlink.light.runtime.RuntimeRelightOutcome;
import dev.lucistarlink.light.runtime.BlockChangeRecord;
import dev.lucistarlink.light.runtime.RuntimeRegionBatch;
import dev.lucistarlink.light.runtime.RuntimeLightChangeBuffer;
import dev.lucistarlink.test.LuxBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.List;

public final class LuxRelighter {
    private final LuxRegionExtractor extractor;
    private final LightMaterialCache materialCache;
    private final LuxSkyLightEngine skyLightEngine = new LuxSkyLightEngine();
    private final LuxBlockLightEngine blockLightEngine = new LuxBlockLightEngine();
    private final LuxPublishEngine publishEngine = new LuxPublishEngine();
    private final ThreadLocal<RuntimeLightChangeBuffer> runtimeChangeBuffers = ThreadLocal.withInitial(() -> new RuntimeLightChangeBuffer(256));
    private final ThreadLocal<BlockPos.MutableBlockPos> runtimeMaterialPos = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private final ThreadLocal<byte[]> borderScratch = ThreadLocal.withInitial(() -> new byte[0]);
    private final ThreadLocal<java.util.BitSet> reachScratch = ThreadLocal.withInitial(java.util.BitSet::new);
    private final ThreadLocal<Boolean> haloTouchedScratch = ThreadLocal.withInitial(() -> Boolean.TRUE);

    /**
     * Receives cross-region boundary light deltas produced by a region job.
     */
    public interface BoundaryDeltaSink extends BorderDeltaSupport.BoundaryDeltaSink {
    }

    public LuxRelighter(LightMaterialCache materialCache, LuxRegionExtractor extractor) {
        this.materialCache = materialCache;
        this.extractor = extractor;
    }

    /**
     * Publishes a job's results. With {@link LuxFlags#haloPublish} the halo chunks are published too, which is
     * what makes an edit near a region border visible in the neighbouring chunk immediately instead of waiting
     * for that chunk's own region job to run.
     */
    private List<LuxRelightResult> publish(RegionLightData data) {
        return publish(data, true);
    }

    private List<LuxRelightResult> publish(RegionLightData data, boolean allowHalo) {
        return LuxFlags.haloPublish && allowHalo && haloTouchedScratch.get()
                ? publishEngine.publishRegionWithHalo(data)
                : publishEngine.publishRegion(data);
    }

    private LuxRelightResult publishChunk(ChunkPos chunkPos, RegionLightData data, boolean allowHalo) {
        List<LuxRelightResult> results = publish(data, allowHalo);
        List<LuxSectionData> sections = new java.util.ArrayList<>();
        for (LuxRelightResult result : results) {
            sections.addAll(result.sections());
        }
        return new LuxRelightResult(chunkPos, sections);
    }

    /**
     * Re-reads sections that a neighbouring region published into the engine (see
     * {@link RuntimeRegionState#drainExternalSections()}). This image's copies of those cells are older than the
     * engine's, so they must be refreshed before the image is used as a propagation baseline.
     */
    /**
     * Materialises the light of every section a batch can reach (radius 15 blocks horizontally, the whole column
     * vertically for skylight) that has not been materialised yet. Sound because light decays one level per block
     * horizontally, so nothing outside that reach is ever read by the repair, and a section that is never
     * materialised never becomes dirty and therefore never gets published.
     */
    /** Adapts the light getter to the publish engines change detection, so unchanged sections are not re-published. */
    private static LuxPublishEngine.SectionLightSource currentSourceFor(LightChunkGetter getter) {
        if (!(getter.getLevel() instanceof net.minecraft.world.level.Level level)) {
            return LuxPublishEngine.SectionLightSource.NONE;
        }
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        return (sectionPos, layer) -> {
            net.minecraft.world.level.chunk.DataLayer layerData =
                    lightEngine.getLayerListener(layer).getDataLayerData(sectionPos);
            return layerData == null ? null : layerData.getData();
        };
    }

    private void materializeReach(LightChunkGetter getter, RegionLightData data, RuntimeRegionBatch batch) {
        List<BlockChangeRecord> changes = batch.changes();
        if (!LuxFlags.lazyHaloLight || changes.isEmpty()) {
            return;
        }
        if (!(getter.getLevel() instanceof net.minecraft.world.level.Level level)) {
            return;
        }
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        java.util.BitSet reach = reachScratch.get();
        reach.clear();
        RegionBounds bounds = data.bounds;
        for (BlockChangeRecord change : changes) {
            data.markColumnSectionsWithinReach(change.x() - bounds.minBlockX(), change.z() - bounds.minBlockZ(), 15, reach);
        }
        int halo = bounds.haloChunks();
        int chunkCount = bounds.regionChunks() + halo * 2;
        int adopted = 0;
        for (int linear = reach.nextSetBit(0); linear >= 0; linear = reach.nextSetBit(linear + 1)) {
            if (data.isLightMaterialized(linear)) {
                continue;
            }
            net.minecraft.core.SectionPos sectionPos = data.sectionPosFromLinear(linear);
            int localChunkX = sectionPos.x() - (bounds.originChunkX() - halo);
            int localChunkZ = sectionPos.z() - (bounds.originChunkZ() - halo);
            int sectionY = sectionPos.y() - bounds.minSectionY();
            if (localChunkX < 0 || localChunkX >= chunkCount || localChunkZ < 0 || localChunkZ >= chunkCount
                    || sectionY < 0 || sectionY >= bounds.sectionCount()) {
                continue;
            }
            extractor.populateSection(getter, data, sectionPos);
            adoptLayer(lightEngine, data, sectionPos, localChunkX, localChunkZ, sectionY, false);
            adoptLayer(lightEngine, data, sectionPos, localChunkX, localChunkZ, sectionY, true);
            adopted++;
        }
        if (adopted > 0) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.region.lazyMaterialized.sections", adopted);
        }
    }

    private void refreshExternalSections(LightChunkGetter getter, RegionLightData data, RuntimeRegionState state) {
        List<RuntimeRegionState.ExternalSection> external = state.drainExternalSections();
        if (external.isEmpty()) {
            return;
        }
        if (!(getter.getLevel() instanceof net.minecraft.world.level.Level level)) {
            return;
        }
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        RegionBounds bounds = data.bounds;
        int halo = bounds.haloChunks();
        int chunkCount = bounds.regionChunks() + halo * 2;
        int adopted = 0;
        for (RuntimeRegionState.ExternalSection section : external) {
            net.minecraft.core.SectionPos sectionPos = net.minecraft.core.SectionPos.of(section.packedSectionPos());
            int localChunkX = sectionPos.x() - (bounds.originChunkX() - halo);
            int localChunkZ = sectionPos.z() - (bounds.originChunkZ() - halo);
            int sectionY = sectionPos.y() - bounds.minSectionY();
            if (localChunkX < 0 || localChunkX >= chunkCount || localChunkZ < 0 || localChunkZ >= chunkCount
                    || sectionY < 0 || sectionY >= bounds.sectionCount()) {
                continue;
            }
            adoptLayer(lightEngine, data, sectionPos, localChunkX, localChunkZ, sectionY, section.sky());
            adopted++;
        }
        if (adopted > 0) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.region.externalRefresh.sections", adopted);
        }
    }

    private BorderDeltaSupport.BoundaryDeltaSink sinkWrapper(BoundaryDeltaSink sink) {
        return (neighborRegionKey, deltas) -> {
            LuxBenchmarkSupport.count("lucistarlink.runtime.boundary.deltas", deltas.length);
            sink.accept(neighborRegionKey, deltas);
        };
    }

    /**
     * Worldgen border ordering: after a freshly generated chunk computed its light, compare the neighbouring
     * chunks' cells this image covers (the halo) against the light the engine currently stores for them. Where
     * they differ, our chunk changed what the neighbour should see, so that neighbour is marked as needing a
     * light recompute (cleared light-correct flag). The save hook writes that flag out, so the neighbour also
     * relights after a reload instead of keeping stale light forever.
     *
     * <p>Only neighbours whose light actually differs are marked, and the comparison is bounded: one cell layer
     * per side plus the shared column layers, read per section rather than per cell.
     */
    /**
     * Marks a neighbour chunk as light-incorrect when our computed image disagrees with the light the engine
     * stores for it, so the chunk is relit when it is next loaded instead of keeping the disagreement forever.
     *
     * <p>Only meaningful for neighbours we do not publish into: with halo publishing on, every cell our
     * computation changed in a neighbour lives in a dirty section of that neighbour, and those sections are
     * published, so the engine ends up holding exactly our values. With halo publishing off (see
     * {@link LuxFlags#worldgenHaloPublish}) nothing corrects the neighbour in this session, and this is what
     * keeps the *saved* light from being stale. Must run after the image is computed and before publishing.
     */
    private void markLightStaleNeighbours(LightChunkGetter getter, RegionLightData data, ChunkPos coreChunk,
                                          boolean publishedByHalo) {
        if (publishedByHalo || data.bounds.haloChunks() <= 0) {
            return;
        }
        if (!(getter.getLevel() instanceof net.minecraft.world.level.Level level)) {
            return;
        }
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        RegionBounds bounds = data.bounds;
        int minBuildY = bounds.minBuildY();
        int maxBuildY = bounds.maxBuildY();
        int[][] sides = {
                {0, -16}, {0, 16}, {-16, 0}, {16, 0}
        };
        for (int[] side : sides) {
            int neighbourChunkX = coreChunk.x + (side[0] >> 4);
            int neighbourChunkZ = coreChunk.z + (side[1] >> 4);
            if (!isStaleAgainstEngine(lightEngine, data, neighbourChunkX, neighbourChunkZ, side, minBuildY, maxBuildY)) {
                continue;
            }
            LightChunk neighbour = getter.getChunkForLighting(neighbourChunkX, neighbourChunkZ);
            if (neighbour instanceof ChunkAccess neighbourChunk && neighbourChunk.isLightCorrect()) {
                neighbourChunk.setLightCorrect(false);
                LuxBenchmarkSupport.count("lucistarlink.worldgen.neighbourStale.marked");
                LuciStarlink.LOGGER.debug("LuciStarlink marked chunk ({}, {}) as light-stale after generating ({}, {})",
                        neighbourChunkX, neighbourChunkZ, coreChunk.x, coreChunk.z);
            }
        }
    }

    /** True when any cell of the neighbour side layer differs between our computed image and the engine. */
    private boolean isStaleAgainstEngine(net.minecraft.world.level.lighting.LevelLightEngine lightEngine,
                                         RegionLightData data, int neighbourChunkX, int neighbourChunkZ,
                                         int[] side, int minBuildY, int maxBuildY) {
        RegionBounds bounds = data.bounds;
        int halo = bounds.haloChunks();
        int chunkCount = bounds.regionChunks() + halo * 2;
        int localChunkX = neighbourChunkX - (bounds.originChunkX() - halo);
        int localChunkZ = neighbourChunkZ - (bounds.originChunkZ() - halo);
        if (localChunkX < 0 || localChunkX >= chunkCount || localChunkZ < 0 || localChunkZ >= chunkCount) {
            return false;
        }
        boolean horizontal = side[1] != 0;
        for (int sectionY = 0; sectionY < bounds.sectionCount(); sectionY++) {
            net.minecraft.core.SectionPos sectionPos =
                    net.minecraft.core.SectionPos.of(neighbourChunkX, bounds.minSectionY() + sectionY, neighbourChunkZ);
            for (boolean sky : new boolean[]{false, true}) {
                net.minecraft.world.level.chunk.DataLayer engineLayer = lightEngine
                        .getLayerListener(sky ? net.minecraft.world.level.LightLayer.SKY : net.minecraft.world.level.LightLayer.BLOCK)
                        .getDataLayerData(sectionPos);
                if (engineLayer == null) {
                    continue;
                }
                byte[] engineData = engineLayer.getData();
                byte[] imageLight = data.selectArray(sky);
                for (int along = 0; along < 16; along++) {
                    for (int y = sectionY << 4; y < (sectionY << 4) + 16 && y < bounds.heightBlocks(); y++) {
                        int worldY = minBuildY + y;
                        if (worldY < minBuildY || worldY >= maxBuildY) {
                            continue;
                        }
                        int localX;
                        int localZ;
                        if (horizontal) {
                            localX = (localChunkX << 4) + along;
                            localZ = side[1] > 0 ? (localChunkZ << 4) : (localChunkZ << 4) + 15;
                        } else {
                            localX = side[0] > 0 ? (localChunkX << 4) : (localChunkX << 4) + 15;
                            localZ = (localChunkZ << 4) + along;
                        }
                        int nibbleX = side[0] > 0 ? 0 : (side[0] < 0 ? 15 : along);
                        int nibbleZ = side[1] > 0 ? 0 : (side[1] < 0 ? 15 : along);
                        int engineIndex = ((y & 15) << 8) | (nibbleZ << 4) | nibbleX;
                        int engineValue = (engineData[engineIndex >> 1] >>> ((engineIndex & 1) * 4)) & 0xF;
                        int imageValue = imageLight[data.localIndex(localX, y, localZ)] & 0xF;
                        if (engineValue != imageValue) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public LuxRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean enableSky, boolean enableBlock,
                                           int regionChunks, int haloChunks) {
        ChunkPos chunkPos = chunk.getPos();
        long startedAt = LuxBenchmarkSupport.start();
        RegionLightData data = extractChunkData(getter, chunk, regionChunks, haloChunks);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.worldgen.extract", startedAt);
        return relightPreparedChunk(chunkPos, data, enableSky, enableBlock, getter);
    }

    public RegionLightData extractChunkData(LightChunkGetter getter, ChunkAccess chunk, int regionChunks, int haloChunks) {
        RegionBounds bounds = RegionBounds.around(chunk.getPos(), chunk.getHeightAccessorForGeneration(), regionChunks, haloChunks);
        return extractor.extract(getter, bounds, chunk);
    }

    public LuxRelightResult relightPreparedChunk(ChunkPos chunkPos, RegionLightData data, boolean enableSky, boolean enableBlock,
                                                 LightChunkGetter getter) {
        long startedAt = LuxBenchmarkSupport.start();
        if (enableSky) {
            skyLightEngine.compute(data);
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.worldgen.sky", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        if (enableBlock) {
            blockLightEngine.compute(data);
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.worldgen.block", startedAt);
        // the image is final now, so the neighbour comparison below sees real values; with halo publishing on
        // the neighbours we disagree with are exactly the ones being published, so nothing needs marking
        markLightStaleNeighbours(getter, data, chunkPos, LuxFlags.worldgenHaloPublish);
        startedAt = LuxBenchmarkSupport.start();
        List<LuxRelightResult> results = publish(data, LuxFlags.worldgenHaloPublish);
        int coreSections = 0;
        int haloSections = 0;
        for (LuxRelightResult result : results) {
            if (result.chunkPos().equals(chunkPos)) {
                coreSections += result.sections().size();
            } else {
                haloSections += result.sections().size();
            }
        }
        LuxBenchmarkSupport.count("lucistarlink.worldgen.publish.core.sections", coreSections);
        LuxBenchmarkSupport.count("lucistarlink.worldgen.publish.halo.sections", haloSections);
        if (!LuxFlags.worldgenHaloPublish) {
            LuxBenchmarkSupport.count("lucistarlink.worldgen.publish.coreOnly");
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.worldgen.publish", startedAt);
        LuxBenchmarkSupport.count("lucistarlink.worldgen.sections", coreSections + haloSections);
        return new LuxRelightResult(chunkPos, results.stream().flatMap(result -> result.sections().stream()).toList());
    }

    public List<LuxRelightResult> relightRegion(LightChunkGetter getter, ChunkPos anchorChunk, boolean enableSky, boolean enableBlock,
                                                  int regionChunks, int haloChunks) {
        RegionBounds bounds = RegionBounds.around(anchorChunk, getter.getLevel(), regionChunks, haloChunks);
        long startedAt = LuxBenchmarkSupport.start();
        RegionLightData data = extractor.extract(getter, bounds);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.region.extract", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        if (enableSky) {
            skyLightEngine.compute(data);
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.region.sky", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        if (enableBlock) {
            blockLightEngine.compute(data);
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.region.block", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        List<LuxRelightResult> results = publish(data);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.region.publish", startedAt);
        countPublished("lucistarlink.region", results);
        return results;
    }

    /**
     * True when any changed cell is within the light travel radius of the owned chunks border, i.e. when the
     * edit can push light across into a neighbouring chunk. When nothing is near the border the whole halo
     * pipeline (halo publication, neighbour marking, external refresh bookkeeping) can be skipped, which is the
     * common case for interior edits and the difference measured on small-edit workloads.
     */
    private static boolean changesNearBorder(RuntimeRegionBatch batch, RegionBounds bounds) {
        if (bounds.haloChunks() <= 0) {
            return false;
        }
        int margin = 15;
        int coreMinX = bounds.originChunkX() << 4;
        int coreMaxX = coreMinX + bounds.regionChunks() * 16;
        int coreMinZ = bounds.originChunkZ() << 4;
        int coreMaxZ = coreMinZ + bounds.regionChunks() * 16;
        for (BlockChangeRecord change : batch.changes()) {
            int x = change.x();
            int z = change.z();
            if (x < coreMinX || x >= coreMaxX || z < coreMinZ || z >= coreMaxZ) {
                continue;
            }
            if (x - coreMinX < margin || coreMaxX - x <= margin || z - coreMinZ < margin || coreMaxZ - z <= margin) {
                return true;
            }
        }
        return false;
    }

    public RuntimeRelightOutcome relightRuntimeRegion(LightChunkGetter getter, RuntimeRegionState state, LightChunk coreChunk,
                                                         RuntimeRegionBatch batch, boolean enableSky, boolean enableBlock,
                                                         BoundaryDeltaSink deltaSink) {
        RegionLightData data = state.data();
        // cells a neighbouring region published into the engine since this image was last used must be re-read
        // first, otherwise they are stale baselines for the propagation below
        refreshExternalSections(getter, data, state);
        if (!batch.fullRelight()) {
            materializeReach(getter, data, batch);
        }
        haloTouchedScratch.set(changesNearBorder(batch, data.bounds));
        long epochAfterRefresh = state.externalEpoch();
        state.touch();
        publishEngine.setCurrentLightSource(currentSourceFor(getter));
        List<LuxRelightResult> results = computeRuntimeRegion(getter, state, data, coreChunk, batch,
                enableSky, enableBlock, deltaSink);
        if (state.externalEpoch() != epochAfterRefresh) {
            // the engine moved under this job: publishing now could overwrite fresher light with values derived
            // from the old baseline, so re-queue instead (the batch is idempotent)
            LuxBenchmarkSupport.count("lucistarlink.runtime.jobs.rerunBaselineMoved");
            return RuntimeRelightOutcome.stale(results);
        }
        return RuntimeRelightOutcome.publish(results, changesNearBorder(batch, data.bounds));
    }

    private List<LuxRelightResult> computeRuntimeRegion(LightChunkGetter getter, RuntimeRegionState state,
                                                        RegionLightData data, LightChunk coreChunk,
                                                        RuntimeRegionBatch batch, boolean enableSky,
                                                        boolean enableBlock, BoundaryDeltaSink deltaSink) {
        if (!state.initializedFor(coreChunk)) {
            LuxBenchmarkSupport.count(state.initialized()
                    ? "lucistarlink.runtime.region.reload"
                    : "lucistarlink.runtime.region.init");
            long startedAt = LuxBenchmarkSupport.start();
            extractor.populate(getter, data, coreChunk);
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.extract", startedAt);
            if (LuxFlags.runtimeAdoption && adoptEngineLight(getter, data, coreChunk)) {
                LuxBenchmarkSupport.count("lucistarlink.runtime.region.adopted");
                // Adoption yields the engine's current light, which does NOT contain this batch's own changes
                // (the engine is bypassed for them), so apply them on top before publishing. Without this a
                // light source placed in a region that had no runtime job yet never lit up.
                applyAdoptedBatchChanges(getter, data, batch, enableSky, enableBlock);
                applyIncomingBoundaryDeltas(data, batch, enableSky, enableBlock);
                state.markInitialized(coreChunk);
                List<LuxRelightResult> results = publish(data);
                countPublished("lucistarlink.runtime.init", results);
                data.clearDirty();
                return results;
            }
            byte[] borderBefore = deltaSink != null && LuxFlags.boundaryDeltas ? BorderDeltaSupport.snapshotBorder(data, borderScratch.get()) : null;
            startedAt = LuxBenchmarkSupport.start();
            if (enableSky) {
                skyLightEngine.compute(data);
            }
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.sky", startedAt);
            startedAt = LuxBenchmarkSupport.start();
            if (enableBlock) {
                blockLightEngine.compute(data);
            }
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.block", startedAt);
            state.markInitialized(coreChunk);
            startedAt = LuxBenchmarkSupport.start();
            List<LuxRelightResult> results = publish(data);
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.publish", startedAt);
            countPublished("lucistarlink.runtime.init", results);
            if (borderBefore != null) {
            BorderDeltaSupport.emitBoundaryDeltas(data, borderBefore, sinkWrapper(deltaSink));
        }
            data.clearDirty();
            return results;
        }

        if (batch.fullRelight()) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.region.fullRelight");
            byte[] borderBefore = deltaSink != null && LuxFlags.boundaryDeltas ? BorderDeltaSupport.snapshotBorder(data, borderScratch.get()) : null;
            long startedAt = LuxBenchmarkSupport.start();
            extractor.populate(getter, data, coreChunk);
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.full.extract", startedAt);
            startedAt = LuxBenchmarkSupport.start();
            if (enableSky) {
                skyLightEngine.compute(data);
            }
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.full.sky", startedAt);
            startedAt = LuxBenchmarkSupport.start();
            if (enableBlock) {
                blockLightEngine.compute(data);
            }
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.full.block", startedAt);
            startedAt = LuxBenchmarkSupport.start();
            List<LuxRelightResult> results = publish(data);
            LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.full.publish", startedAt);
            countPublished("lucistarlink.runtime.full", results);
            if (borderBefore != null) {
            BorderDeltaSupport.emitBoundaryDeltas(data, borderBefore, sinkWrapper(deltaSink));
        }
            data.clearDirty();
            return results;
        }

        List<BlockChangeRecord> changes = batch.changes();
        boolean hasDeltas = !batch.isEmptyDeltaSet();

        if (changes.isEmpty() && !hasDeltas) {
            return List.of();
        }

        LuxBenchmarkSupport.count("lucistarlink.runtime.region.incremental");
        LuxBenchmarkSupport.count("lucistarlink.runtime.change.records", changes.size());
        data.clearDirty();
        byte[] borderBefore = deltaSink != null && LuxFlags.boundaryDeltas ? BorderDeltaSupport.snapshotBorder(data, borderScratch.get()) : null;
        long startedAt = LuxBenchmarkSupport.start();
        RuntimeLightChangeBuffer runtimeChanges = runtimeChangeBuffers.get();
        runtimeChanges.clear();
        materializeChanges(getter.getLevel(), data, changes, runtimeChanges);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.incremental.materialize", startedAt);
        if (runtimeChanges.isEmpty()) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.change.light_noop", changes.size());
            runtimeChanges.clear();
            applyIncomingBoundaryDeltas(data, batch, enableSky, enableBlock);
            List<LuxRelightResult> results = publish(data);
            countPublished("lucistarlink.runtime.incremental", results);
            if (borderBefore != null) {
            BorderDeltaSupport.emitBoundaryDeltas(data, borderBefore, sinkWrapper(deltaSink));
        }
            data.clearDirty();
            return results;
        }
        startedAt = LuxBenchmarkSupport.start();
        applyMaterialChanges(data, runtimeChanges);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.incremental.materials", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        boolean opacityChanged = runtimeChanges.hasOpacityChange();
        boolean skyChanged = runtimeChanges.hasSkyChange();
        LuxBenchmarkSupport.count(opacityChanged ? "lucistarlink.runtime.opacity.changed" : "lucistarlink.runtime.opacity.unchanged");
        LuxBenchmarkSupport.count(skyChanged ? "lucistarlink.runtime.sky.changed" : "lucistarlink.runtime.sky.unchanged");
        if (enableSky && skyChanged) {
            skyLightEngine.applyRuntimeChanges(data, runtimeChanges);
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.incremental.sky", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        boolean emissionChanged = runtimeChanges.hasEmissionChange();
        LuxBenchmarkSupport.count(emissionChanged ? "lucistarlink.runtime.emission.changed" : "lucistarlink.runtime.emission.unchanged");
        boolean blockAffected = emissionChanged || (opacityChanged && hasNearbyBlockLight(data, runtimeChanges));
        if (enableBlock && blockAffected) {
            if (runtimeChanges.size() > 1 && runtimeChanges.blockFastEligible()) {
                LuxBenchmarkSupport.count("lucistarlink.runtime.block.fastBatch");
                blockLightEngine.applyRuntimeChangesFastEligible(data, runtimeChanges);
            } else {
                blockLightEngine.applyRuntimeChanges(data, runtimeChanges);
            }
        } else if (enableBlock) {
            LuxBenchmarkSupport.count(blockAffected
                    ? "lucistarlink.runtime.block.skipped.disabled"
                    : "lucistarlink.runtime.block.skipped.noAffectedLight");
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.incremental.block", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        applyIncomingBoundaryDeltas(data, batch, enableSky, enableBlock);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.incremental.deltas", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        List<LuxRelightResult> results = publish(data);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.incremental.publish", startedAt);
        countPublished("lucistarlink.runtime.incremental", results);
        if (borderBefore != null) {
            BorderDeltaSupport.emitBoundaryDeltas(data, borderBefore, sinkWrapper(deltaSink));
        }
        data.clearDirty();
        runtimeChanges.clear();
        return results;
    }

    private void applyIncomingBoundaryDeltas(RegionLightData data, RuntimeRegionBatch batch, boolean enableSky, boolean enableBlock) {
        if (batch.isEmptyDeltaSet() || !LuxFlags.boundaryDeltas) {
            return;
        }
        if (enableSky) {
            skyLightEngine.applyBoundaryDeltas(data, batch.boundaryDeltas());
        }
        if (enableBlock) {
            blockLightEngine.applyBoundaryDeltas(data, batch.boundaryDeltas());
        }
    }

    private void materializeChanges(BlockGetter level, RegionLightData data, List<BlockChangeRecord> changes, RuntimeLightChangeBuffer runtimeChanges) {
        BlockPos.MutableBlockPos pos = runtimeMaterialPos.get();
        RegionBounds bounds = data.bounds;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int minBuildY = bounds.minBuildY();
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();
        for (BlockChangeRecord change : changes) {
            int localX = change.x() - minBlockX;
            int localY = change.y() - minBuildY;
            int localZ = change.z() - minBlockZ;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth || localY < 0 || localY >= height) {
                continue;
            }
            pos.set(change.x(), change.y(), change.z());
            int oldMaterial = materialCache.lookupLight(level, change.oldState(), pos);
            int newMaterial = materialCache.lookupLight(level, change.newState(), pos);
            if (LightMaterial.hasSameRuntimeProperties(oldMaterial, newMaterial)) {
                continue;
            }
            runtimeChanges.add(data.localIndex(localX, localY, localZ), oldMaterial, newMaterial);
        }
    }

    /**
     * Applies a batch's block changes on top of an adopted region image. Adoption copies the engine's light,
     * which is current for everything except the changes in this batch (the vanilla light engine is bypassed
     * for them), so they still have to be materialised and repaired exactly like on the incremental path.
     */
    private void applyAdoptedBatchChanges(LightChunkGetter getter, RegionLightData data, RuntimeRegionBatch batch,
                                          boolean enableSky, boolean enableBlock) {
        List<BlockChangeRecord> changes = batch.changes();
        if (changes.isEmpty()) {
            return;
        }
        LuxBenchmarkSupport.count("lucistarlink.runtime.init.adoptedChanges", changes.size());
        long startedAt = LuxBenchmarkSupport.start();
        RuntimeLightChangeBuffer runtimeChanges = runtimeChangeBuffers.get();
        runtimeChanges.clear();
        materializeChanges(getter.getLevel(), data, changes, runtimeChanges);
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.adoptChanges.materialize", startedAt);
        if (runtimeChanges.isEmpty()) {
            runtimeChanges.clear();
            return;
        }
        applyMaterialChanges(data, runtimeChanges);
        startedAt = LuxBenchmarkSupport.start();
        if (enableSky && runtimeChanges.hasSkyChange()) {
            skyLightEngine.applyRuntimeChanges(data, runtimeChanges);
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.adoptChanges.sky", startedAt);
        startedAt = LuxBenchmarkSupport.start();
        boolean blockAffected = runtimeChanges.hasEmissionChange()
                || (runtimeChanges.hasOpacityChange() && hasNearbyBlockLight(data, runtimeChanges));
        if (enableBlock && blockAffected) {
            blockLightEngine.applyRuntimeChanges(data, runtimeChanges);
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.adoptChanges.block", startedAt);
        runtimeChanges.clear();
    }

    private void applyMaterialChanges(RegionLightData data, RuntimeLightChangeBuffer changes) {
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            int index = RuntimeLightChangeBuffer.localIndex(change);
            int newLight = RuntimeLightChangeBuffer.newLight(change);
            data.opacity[index] = (byte) (newLight & 0xF);
            data.emission[index] = (byte) ((newLight >>> 4) & 0xF);
        }
    }

    /**
     * Adoption-backed region initialization: instead of recomputing sky and block light
     * from scratch, mirror the authoritative light already stored by the engine for this
     * fully loaded chunk. The adopted state is exactly the observable state, so runtime
     * edits can proceed incrementally with zero bootstrap light work and zero bootstrap
     * publications. Returns false when adoption is not possible and the caller must fall
     * back to a full compute.
     */
    private boolean adoptEngineLight(LightChunkGetter getter, RegionLightData data, LightChunk coreChunk) {
        if (coreChunk instanceof ChunkAccess chunkAccess && !chunkAccess.isLightCorrect()) {
            LuxBenchmarkSupport.count("lucistarlink.runtime.region.adopt.skippedLightNotCorrect");
            return false;
        }
        if (!(getter.getLevel() instanceof net.minecraft.world.level.Level level)) {
            return false;
        }
        long startedAt = LuxBenchmarkSupport.start();
        try {
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
            RegionBounds bounds = data.bounds;
            int chunkCount = bounds.regionChunks() + bounds.haloChunks() * 2;
            // lazy halo mode: only the owned chunks' light is materialised here; halo light is materialised per
            // job for exactly the sections a change can reach (see materializeReach)
            int firstChunk = LuxFlags.lazyHaloLight ? bounds.haloChunks() : 0;
            int lastChunk = LuxFlags.lazyHaloLight ? bounds.haloChunks() + bounds.regionChunks() : chunkCount;
            for (int sectionY = 0; sectionY < bounds.sectionCount(); sectionY++) {
                for (int chunkZ = firstChunk; chunkZ < lastChunk; chunkZ++) {
                    for (int chunkX = firstChunk; chunkX < lastChunk; chunkX++) {
                        net.minecraft.core.SectionPos sectionPos = net.minecraft.core.SectionPos.of(
                                bounds.originChunkX() - bounds.haloChunks() + chunkX,
                                bounds.minSectionY() + sectionY,
                                bounds.originChunkZ() - bounds.haloChunks() + chunkZ);
                        adoptLayer(lightEngine, data, sectionPos, chunkX, chunkZ, sectionY, false);
                        adoptLayer(lightEngine, data, sectionPos, chunkX, chunkZ, sectionY, true);
                    }
                }
            }
            int adoptedChunks = lastChunk - firstChunk;
            LuxBenchmarkSupport.count("lucistarlink.runtime.region.adopt.sections", adoptedChunks * adoptedChunks * bounds.sectionCount());
            if (!LuxFlags.lazyHaloLight) {
                data.markAllLightMaterialized();
            }
        } catch (Throwable throwable) {
            LuciStarlink.LOGGER.debug("Lux light adoption failed, falling back to full compute", throwable);
            data.resetForReuse();
            return false;
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.stage.runtime.init.adopt", startedAt);
        return true;
    }

    private void adoptLayer(net.minecraft.world.level.lighting.LevelLightEngine lightEngine, RegionLightData data,
                            net.minecraft.core.SectionPos sectionPos, int chunkX, int chunkZ, int sectionY, boolean sky) {
        net.minecraft.world.level.LightLayer layer = sky ? net.minecraft.world.level.LightLayer.SKY : net.minecraft.world.level.LightLayer.BLOCK;
        net.minecraft.world.level.chunk.DataLayer dataLayer = lightEngine.getLayerListener(layer).getDataLayerData(sectionPos);
        if (dataLayer == null) {
            return;
        }
        data.adoptSectionData(chunkX, chunkZ, sectionY, dataLayer.getData(), sky);
        data.markLightMaterialized(data.sectionLinearIndexLocal(chunkX << 4, sectionY << 4, chunkZ << 4));
    }

    private boolean hasNearbyBlockLight(RegionLightData data, RuntimeLightChangeBuffer changes) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int height = data.bounds.heightBlocks();
        int area = data.bounds.area();
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            if (RuntimeLightChangeBuffer.oldOpacity(change) == RuntimeLightChangeBuffer.newOpacity(change)) {
                continue;
            }
            int index = RuntimeLightChangeBuffer.localIndex(change);
            if ((data.blockLight[index] & 0xF) != 0) {
                return true;
            }
            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;
            if (x + 1 < width && (data.blockLight[index + data.offsetPosX] & 0xF) != 0) return true;
            if (x > 0 && (data.blockLight[index + data.offsetNegX] & 0xF) != 0) return true;
            if (z + 1 < depth && (data.blockLight[index + data.offsetPosZ] & 0xF) != 0) return true;
            if (z > 0 && (data.blockLight[index + data.offsetNegZ] & 0xF) != 0) return true;
            if (y + 1 < height && (data.blockLight[index + data.offsetPosY] & 0xF) != 0) return true;
            if (y > 0 && (data.blockLight[index + data.offsetNegY] & 0xF) != 0) return true;
        }
        return false;
    }

    private void countPublished(String prefix, List<LuxRelightResult> results) {
        int sections = 0;
        for (LuxRelightResult result : results) {
            sections += result.sections().size();
        }
        LuxBenchmarkSupport.count(prefix + ".results", results.size());
        LuxBenchmarkSupport.count(prefix + ".sections", sections);
    }
}
