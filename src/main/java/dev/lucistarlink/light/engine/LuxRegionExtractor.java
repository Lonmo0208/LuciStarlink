package dev.lucistarlink.light.engine;

import dev.lucistarlink.light.LuxConstants;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.LightMaterial;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.LightMaterialCache;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.test.LuxBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.Arrays;

public final class LuxRegionExtractor {
    private final LightMaterialCache materialCache;
    private final ThreadLocal<ChunkScratch> chunkScratch = ThreadLocal.withInitial(ChunkScratch::new);

    public LuxRegionExtractor(LightMaterialCache materialCache) {
        this.materialCache = materialCache;
    }

    public RegionLightData extract(LightChunkGetter getter, RegionBounds bounds) {
        return extract(getter, bounds, null);
    }

    public RegionLightData extract(LightChunkGetter getter, RegionBounds bounds, LightChunk coreChunk) {
        RegionLightData data = new RegionLightData(bounds);
        populate(getter, data, coreChunk);
        return data;
    }

    public void populate(LightChunkGetter getter, RegionLightData data) {
        populate(getter, data, null);
    }

    /** Extracts the materials of a single section from the world (lazy halo mode). */
    public void populateSection(LightChunkGetter getter, RegionLightData data, net.minecraft.core.SectionPos sectionPos) {
        RegionBounds bounds = data.bounds;
        int halo = bounds.haloChunks();
        int chunkCount = bounds.regionChunks() + halo * 2;
        int localChunkX = sectionPos.x() - (bounds.originChunkX() - halo);
        int localChunkZ = sectionPos.z() - (bounds.originChunkZ() - halo);
        int sectionY = sectionPos.y() - bounds.minSectionY();
        if (localChunkX < 0 || localChunkX >= chunkCount || localChunkZ < 0 || localChunkZ >= chunkCount
                || sectionY < 0 || sectionY >= bounds.sectionCount()) {
            return;
        }
        BlockGetter level = getter.getLevel();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int blockXStart = Math.max(bounds.minBlockX(), sectionPos.x() << 4);
        int blockXEnd = Math.min(bounds.maxBlockXExclusive(), (sectionPos.x() << 4) + 16);
        int blockZStart = Math.max(bounds.minBlockZ(), sectionPos.z() << 4);
        int blockZEnd = Math.min(bounds.maxBlockZExclusive(), (sectionPos.z() << 4) + 16);
        LightChunk chunk = getter.getChunkForLighting(sectionPos.x(), sectionPos.z());
        if (chunk instanceof ChunkAccess chunkAccess) {
            LevelChunkSection[] sections = chunkAccess.getSections();
            int index = sectionPos.y() - chunkAccess.getMinSection();
            if (index >= 0 && index < sections.length) {
                populateSectionCells(level, data, mutable, sections[index], blockXStart, blockXEnd,
                        Math.max(bounds.minBuildY(), sectionPos.y() << 4),
                        Math.min(bounds.maxBuildY(), (sectionPos.y() << 4) + 16), blockZStart, blockZEnd);
            }
        } else {
            populateFallback(level, data, mutable, chunk, Blocks.AIR.defaultBlockState(),
                    blockXStart, blockXEnd, blockZStart, blockZEnd);
        }
    }

    public void populate(LightChunkGetter getter, RegionLightData data, LightChunk coreChunk) {
        data.beginFullPopulate();
        BlockGetter level = getter.getLevel();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        RegionBounds bounds = data.bounds;
        int minChunkX = bounds.minBlockX() >> 4;
        int maxChunkX = (bounds.maxBlockXExclusive() - 1) >> 4;
        int minChunkZ = bounds.minBlockZ() >> 4;
        int maxChunkZ = (bounds.maxBlockZExclusive() - 1) >> 4;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int maxBlockX = bounds.maxBlockXExclusive();
        int maxBlockZ = bounds.maxBlockZExclusive();
        int width = bounds.widthBlocks();
        int area = bounds.area();
        int chunkWidth = maxChunkX - minChunkX + 1;
        int chunkCount = chunkWidth * (maxChunkZ - minChunkZ + 1);
        if (LuxFlags.lazyHaloLight) {
            minChunkX = Math.max(minChunkX, bounds.originChunkX());
            maxChunkX = Math.min(maxChunkX, bounds.originChunkX() + bounds.regionChunks() - 1);
            minChunkZ = Math.max(minChunkZ, bounds.originChunkZ());
            maxChunkZ = Math.min(maxChunkZ, bounds.originChunkZ() + bounds.regionChunks() - 1);
            chunkWidth = maxChunkX - minChunkX + 1;
            chunkCount = chunkWidth * (maxChunkZ - minChunkZ + 1);
        }
        ChunkScratch scratch = chunkScratch.get();
        LightChunk[] chunks = scratch.chunks(chunkCount);
        boolean[] chunkResolved = scratch.resolved(chunkCount);

        int coreMinBlockX = bounds.originChunkX() << 4;
        int coreMaxBlockX = coreMinBlockX + bounds.regionChunks() * 16;
        int coreMinBlockZ = bounds.originChunkZ() << 4;
        int coreMaxBlockZ = coreMinBlockZ + bounds.regionChunks() * 16;

        try {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                int blockZStart = Math.max(minBlockZ, chunkZ << 4);
                int blockZEnd = Math.min(maxBlockZ, (chunkZ << 4) + 16);
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    int chunkIndex = (chunkX - minChunkX) + (chunkZ - minChunkZ) * chunkWidth;
                    LightChunk chunk = chunks[chunkIndex];
                    if (!chunkResolved[chunkIndex]) {
                        chunk = coreChunk != null
                                && chunkX == bounds.originChunkX()
                                && chunkZ == bounds.originChunkZ()
                                ? coreChunk
                                : getter.getChunkForLighting(chunkX, chunkZ);
                        chunks[chunkIndex] = chunk;
                        chunkResolved[chunkIndex] = true;
                    }

                    int blockXStart = Math.max(minBlockX, chunkX << 4);
                    int blockXEnd = Math.min(maxBlockX, (chunkX << 4) + 16);
                    if (chunk instanceof ChunkAccess chunkAccess) {
                        populateChunkSections(level, data, mutable, chunkAccess, blockXStart, blockXEnd, blockZStart, blockZEnd);
                    } else {
                        BlockState missingState = blockXStart < coreMaxBlockX && blockXEnd > coreMinBlockX
                                && blockZStart < coreMaxBlockZ && blockZEnd > coreMinBlockZ
                                ? Blocks.BEDROCK.defaultBlockState()
                                : Blocks.AIR.defaultBlockState();
                        populateFallback(level, data, mutable, chunk, missingState, blockXStart, blockXEnd, blockZStart, blockZEnd);
                    }
                }
            }
        } finally {
            scratch.release(chunkCount);
        }
    }

    private void populateChunkSections(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                                       ChunkAccess chunk, int blockXStart, int blockXEnd, int blockZStart, int blockZEnd) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSection();
        int firstSection = Math.max(0, (data.bounds.minBuildY() >> 4) - minSectionY);
        int lastSectionExclusive = Math.min(sections.length, ((data.bounds.maxBuildY() - 1) >> 4) - minSectionY + 1);

        for (int sectionIndex = firstSection; sectionIndex < lastSectionExclusive; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            int sectionMinY = (minSectionY + sectionIndex) << 4;
            int blockYStart = Math.max(data.bounds.minBuildY(), sectionMinY);
            int blockYEnd = Math.min(data.bounds.maxBuildY(), sectionMinY + 16);
            populateSectionCells(level, data, mutable, section, blockXStart, blockXEnd, blockYStart, blockYEnd, blockZStart, blockZEnd);
        }
    }

    private void populateSectionCells(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                                       LevelChunkSection section, int blockXStart, int blockXEnd, int blockYStart,
                                       int blockYEnd, int blockZStart, int blockZEnd) {
        int minBlockX = data.bounds.minBlockX();
        int minBlockZ = data.bounds.minBlockZ();
        int minBuildY = data.bounds.minBuildY();
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        if (LuxFlags.sectionFastPath && populateHomogeneousSection(data, section,
                blockXStart - minBlockX, blockXEnd - minBlockX, blockYStart - minBuildY, blockYEnd - minBuildY,
                blockZStart - minBlockZ, blockZEnd - minBlockZ, width, area)) {
            return;
        }
        for (int worldY = blockYStart; worldY < blockYEnd; worldY++) {
            int yBase = (worldY - minBuildY) * area;
            for (int worldZ = blockZStart; worldZ < blockZEnd; worldZ++) {
                int rowBase = yBase + (worldZ - minBlockZ) * width;
                for (int worldX = blockXStart; worldX < blockXEnd; worldX++) {
                    BlockState state = section.getBlockState(worldX & 15, worldY & 15, worldZ & 15);
                    writeMaterial(level, data, mutable, state, worldX, worldY, worldZ, rowBase + (worldX - minBlockX));
                }
            }
        }
    }

    /**
     * Homogeneity-certified extraction: a section whose palette certifies a single light
     * material class is bulk-filled instead of visited cell by cell. Air sections fill
     * opacity/emission 0; sections whose every palette entry carries vanilla's cached
     * full opacity and zero emission fill opacity 15. Both certificates are exact with
     * respect to the per-cell material rules.
     */
    private boolean populateHomogeneousSection(RegionLightData data, LevelChunkSection section,
                                               int localXStart, int localXEnd, int localYStart, int localYEnd,
                                               int localZStart, int localZEnd, int width, int area) {
        byte fillOpacity;
        if (section.hasOnlyAir()) {
            fillOpacity = 0;
        } else if (section.maybeHas(state -> !(state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == 15
                && state.getLightEmission() == 0))) {
            return false;
        } else {
            fillOpacity = LuxConstants.MAX_LIGHT_BYTE;
        }

        LuxBenchmarkSupport.count(fillOpacity == 0 ? "lucistarlink.extract.sections.air" : "lucistarlink.extract.sections.opaque");
        for (int localY = localYStart; localY < localYEnd; localY++) {
            int yBase = localY * area;
            for (int localZ = localZStart; localZ < localZEnd; localZ++) {
                int rowBase = yBase + localZ * width;
                java.util.Arrays.fill(data.emission, rowBase + localXStart, rowBase + localXEnd, (byte) 0);
                java.util.Arrays.fill(data.opacity, rowBase + localXStart, rowBase + localXEnd, fillOpacity);
            }
        }
        return true;
    }

    private void populateFallback(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                                  LightChunk chunk, BlockState missingState, int blockXStart, int blockXEnd,
                                  int blockZStart, int blockZEnd) {
        for (int worldY = data.bounds.minBuildY(); worldY < data.bounds.maxBuildY(); worldY++) {
            int yBase = (worldY - data.bounds.minBuildY()) * data.bounds.area();
            for (int worldZ = blockZStart; worldZ < blockZEnd; worldZ++) {
                int rowBase = yBase + (worldZ - data.bounds.minBlockZ()) * data.bounds.widthBlocks();
                for (int worldX = blockXStart; worldX < blockXEnd; worldX++) {
                    mutable.set(worldX, worldY, worldZ);
                    BlockState state = chunk == null ? missingState : chunk.getBlockState(mutable);
                    writeMaterial(level, data, mutable, state, worldX, worldY, worldZ, rowBase + (worldX - data.bounds.minBlockX()));
                }
            }
        }
    }

    private void writeMaterial(BlockGetter level, RegionLightData data, BlockPos.MutableBlockPos mutable,
                               BlockState state, int worldX, int worldY, int worldZ, int index) {
        mutable.set(worldX, worldY, worldZ);
        int material = materialCache.lookupLight(level, state, mutable);
        data.opacity[index] = LightMaterial.opacity(material);
        data.emission[index] = LightMaterial.emission(material);
    }

    private static final class ChunkScratch {
        private LightChunk[] chunks = new LightChunk[9];
        private boolean[] resolved = new boolean[9];

        private LightChunk[] chunks(int required) {
            if (chunks.length < required) {
                chunks = new LightChunk[required];
            }
            return chunks;
        }

        private boolean[] resolved(int required) {
            if (resolved.length < required) {
                resolved = new boolean[required];
            }
            return resolved;
        }

        private void release(int used) {
            Arrays.fill(chunks, 0, used, null);
            Arrays.fill(resolved, 0, used, false);
        }
    }
}
