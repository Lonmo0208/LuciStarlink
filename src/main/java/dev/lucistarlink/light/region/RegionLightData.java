package dev.lucistarlink.light.region;

import net.minecraft.core.SectionPos;

import java.util.BitSet;

public final class RegionLightData {
    public final RegionBounds bounds;
    public final byte[] opacity;
    public final byte[] emission;
    public final byte[] blockLight;
    public final byte[] skyLight;
    public final BitSet dirtyBlockSections;
    public final BitSet dirtySkySections;
    public final int sectionWidth;
    public final int sectionsPerPlane;
    public final int coreLocalChunkStart;
    public final int coreLocalChunkEnd;
    public final int offsetPosX;
    public final int offsetNegX;
    public final int offsetPosZ;
    public final int offsetNegZ;
    public final int offsetPosY;
    public final int offsetNegY;

    public RegionLightData(RegionBounds bounds) {
        this.bounds = bounds;
        this.opacity = new byte[bounds.volume()];
        this.emission = new byte[bounds.volume()];
        this.blockLight = new byte[bounds.volume()];
        this.skyLight = new byte[bounds.volume()];
        int sectionCapacity = bounds.widthBlocks() / 16 * bounds.depthBlocks() / 16 * bounds.sectionCount();
        this.dirtyBlockSections = new BitSet(sectionCapacity);
        this.dirtySkySections = new BitSet(sectionCapacity);
        this.sectionWidth = bounds.widthBlocks() >> 4;
        this.sectionsPerPlane = sectionWidth * sectionWidth;
        this.coreLocalChunkStart = bounds.haloChunks();
        this.coreLocalChunkEnd = bounds.haloChunks() + bounds.regionChunks();
        this.offsetPosX = 1;
        this.offsetNegX = -1;
        this.offsetPosZ = bounds.widthBlocks();
        this.offsetNegZ = -bounds.widthBlocks();
        this.offsetPosY = bounds.area();
        this.offsetNegY = -bounds.area();
    }

    public int index(int worldX, int worldY, int worldZ) {
        int localX = worldX - bounds.minBlockX();
        int localY = worldY - bounds.minBuildY();
        int localZ = worldZ - bounds.minBlockZ();
        return localX + localZ * bounds.widthBlocks() + localY * bounds.area();
    }

    /**
     * Heap footprint of this region's planes, in bytes: four byte planes of {@code volume} bytes each
     * (opacity, emission, block light, sky light) plus the dirty bit sets. The region cache uses this to
     * bound itself by memory instead of by entry count, because with a halo or a larger
     * {@code regionChunks} a single region is megabytes and an entry cap says nothing about the heap.
     */
    public long byteSize() {
        return 4L * (long) this.opacity.length + 64L;
    }

    public int localIndex(int localX, int localY, int localZ) {
        return localX + localZ * bounds.widthBlocks() + localY * bounds.area();
    }

    public boolean isInside(int worldX, int worldY, int worldZ) {
        return bounds.containsBlock(worldX, worldY, worldZ);
    }

    public int sectionLinearIndex(int worldX, int sectionY, int worldZ) {
        int chunkLocalX = (worldX - bounds.minBlockX()) >> 4;
        int chunkLocalZ = (worldZ - bounds.minBlockZ()) >> 4;
        int sectionLocalY = bounds.sectionIndex(sectionY);
        return chunkLocalX + chunkLocalZ * sectionWidth + sectionLocalY * sectionsPerPlane;
    }

    public void markDirtyBlock(int worldX, int sectionY, int worldZ) {
        dirtyBlockSections.set(sectionLinearIndex(worldX, sectionY, worldZ));
    }

    public void markDirtySky(int worldX, int sectionY, int worldZ) {
        dirtySkySections.set(sectionLinearIndex(worldX, sectionY, worldZ));
    }

    public void markDirtyBlockLocal(int localX, int localY, int localZ) {
        dirtyBlockSections.set(sectionLinearIndexLocal(localX, localY, localZ));
    }

    public void markDirtySkyLocal(int localX, int localY, int localZ) {
        dirtySkySections.set(sectionLinearIndexLocal(localX, localY, localZ));
    }

    public int sectionLinearIndexLocal(int localX, int localY, int localZ) {
        return (localX >> 4) + (localZ >> 4) * sectionWidth + (localY >> 4) * sectionsPerPlane;
    }

    /**
     * Marks every section whose blocks intersect the axis-aligned box of {@code radius} around a local cell.
     * Light decays one level per block in every direction (skylight included), so nothing outside this box can
     * be touched by a change at that cell: it is the sound upper bound for lazily materialising halo light.
     */
    /** Section-level record of which light has actually been materialised from the engine (lazy halo mode). */
    public final java.util.BitSet materializedLightSections = new java.util.BitSet();

    public boolean isLightMaterialized(int sectionLinear) {
        return materializedLightSections.get(sectionLinear);
    }

    public void markLightMaterialized(int sectionLinear) {
        materializedLightSections.set(sectionLinear);
    }

    public void markAllLightMaterialized() {
        for (int y = 0; y < bounds.sectionCount(); y++) {
            for (int z = 0; z < sectionWidth; z++) {
                for (int x = 0; x < sectionWidth; x++) {
                    materializedLightSections.set(x + z * sectionWidth + y * sectionsPerPlane);
                }
            }
        }
    }

    public void clearLightMaterialized() {
        materializedLightSections.clear();
    }

    /**
     * 只标记 {@code chunkCount × chunkCount} 自有区块的 section，与 {@link #markAllLightMaterialized} 相对。
     * lazy halo 模式下 {@code populate} 只提取自有区块的材质，halo 交给 materializeReach 按需补，
     * 所以这里标记的范围必须与它提取的范围完全一致：多标一格就等于那格的材质永远不补。
     */
    public void markRegionLightMaterialized(int originChunkX, int originChunkZ, int chunkCount) {
        int baseChunkX = originChunkX - (bounds.minBlockX() >> 4);
        int baseChunkZ = originChunkZ - (bounds.minBlockZ() >> 4);
        for (int y = 0; y < bounds.sectionCount(); y++) {
            int yBase = y * sectionsPerPlane;
            for (int z = 0; z < chunkCount; z++) {
                int localZ = baseChunkZ + z;
                if (localZ < 0 || localZ >= sectionWidth) {
                    continue;
                }
                int zBase = yBase + localZ * sectionWidth;
                for (int x = 0; x < chunkCount; x++) {
                    int localX = baseChunkX + x;
                    if (localX < 0 || localX >= sectionWidth) {
                        continue;
                    }
                    materializedLightSections.set(zBase + localX);
                }
            }
        }
    }

    /**
     * Reach of a change for lazily materialising light: {@code radius} blocks horizontally, but the *whole*
     * column vertically. Skylight travels without decay straight down a column, so a change can alter light in
     * sections far above/below it, while horizontally light decays one level per block (radius 15).
     */
    public void markColumnSectionsWithinReach(int localX, int localZ, int radius, BitSet out) {
        int minX = Math.max(0, localX - radius);
        int maxX = Math.min(bounds.widthBlocks() - 1, localX + radius);
        int minZ = Math.max(0, localZ - radius);
        int maxZ = Math.min(bounds.depthBlocks() - 1, localZ + radius);
        for (int chunkY = 0; chunkY < bounds.sectionCount(); chunkY++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
                    out.set(chunkX + chunkZ * sectionWidth + chunkY * sectionsPerPlane);
                }
            }
        }
    }
    public void markSectionsWithinReach(int localX, int localY, int localZ, int radius, BitSet out) {
        if (radius < 0) {
            return;
        }
        int minX = Math.max(0, localX - radius);
        int maxX = Math.min(bounds.widthBlocks() - 1, localX + radius);
        int minY = Math.max(0, localY - radius);
        int maxY = Math.min(bounds.heightBlocks() - 1, localY + radius);
        int minZ = Math.max(0, localZ - radius);
        int maxZ = Math.min(bounds.depthBlocks() - 1, localZ + radius);
        for (int chunkY = minY >> 4; chunkY <= (maxY >> 4); chunkY++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
                for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
                    out.set(chunkX + chunkZ * sectionWidth + chunkY * sectionsPerPlane);
                }
            }
        }
    }

    public void markCoreBlockSectionsDirty() {
        markCoreSectionsDirty(dirtyBlockSections);
    }

    public void markCoreSkySectionsDirty() {
        markCoreSectionsDirty(dirtySkySections);
    }

    private void markCoreSectionsDirty(BitSet mask) {
        for (int sectionY = 0; sectionY < bounds.sectionCount(); sectionY++) {
            int sectionBase = sectionY * sectionsPerPlane;
            for (int chunkZ = coreLocalChunkStart; chunkZ < coreLocalChunkEnd; chunkZ++) {
                int rowStart = sectionBase + chunkZ * sectionWidth + coreLocalChunkStart;
                mask.set(rowStart, rowStart + bounds.regionChunks());
            }
        }
    }

    public int sectionBaseIndex(int sectionWorldX, int sectionY, int sectionWorldZ) {
        return index(sectionWorldX, sectionY << 4, sectionWorldZ);
    }

    public SectionPos sectionPosFromLinear(int linear) {
        int sectionY = linear / sectionsPerPlane;
        int rem = linear - sectionY * sectionsPerPlane;
        int localChunkZ = rem / sectionWidth;
        int localChunkX = rem - localChunkZ * sectionWidth;
        return SectionPos.of(
                SectionPos.blockToSectionCoord(bounds.minBlockX()) + localChunkX,
                bounds.minSectionY() + sectionY,
                SectionPos.blockToSectionCoord(bounds.minBlockZ()) + localChunkZ
        );
    }

    public byte[] selectArray(boolean sky) {
        return sky ? skyLight : blockLight;
    }

    public int localX(int index) {
        return index % bounds.widthBlocks();
    }

    public int localY(int index) {
        return index / bounds.area();
    }

    public int localZ(int index) {
        int rem = index - localY(index) * bounds.area();
        return rem / bounds.widthBlocks();
    }

    public void markDirtyBlockIndex(int index) {
        int localY = index / bounds.area();
        int rem = index - localY * bounds.area();
        int localZ = rem / bounds.widthBlocks();
        int localX = rem - localZ * bounds.widthBlocks();
        markDirtyBlockLocal(localX, localY, localZ);
    }

    public void markDirtySkyIndex(int index) {
        int localY = index / bounds.area();
        int rem = index - localY * bounds.area();
        int localZ = rem / bounds.widthBlocks();
        int localX = rem - localZ * bounds.widthBlocks();
        markDirtySkyLocal(localX, localY, localZ);
    }

    public void clearLight() {
        java.util.Arrays.fill(blockLight, (byte) 0);
        java.util.Arrays.fill(skyLight, (byte) 0);
        clearDirty();
    }

    public void clearDirty() {
        dirtyBlockSections.clear();
        dirtySkySections.clear();
    }

    /**
     * Unpacks one 16x16x16 vanilla DataLayer (2048 packed nibbles) into this region's
     * per-byte light array. Used by the adoption path to mirror the authoritative
     * engine storage without recomputing light.
     */
    public void adoptSectionData(int localChunkX, int localChunkZ, int sectionIndex, byte[] packed, boolean sky) {
        int minSectionBaseX = localChunkX << 4;
        int minSectionBaseZ = localChunkZ << 4;
        int width = bounds.widthBlocks();
        int area = bounds.area();
        byte[] target = sky ? skyLight : blockLight;
        for (int y = 0; y < 16; y++) {
            int yBase = (sectionIndex << 4 | y) * area;
            for (int z = 0; z < 16; z++) {
                int rowBase = yBase + (minSectionBaseZ + z) * width + minSectionBaseX;
                int packedBase = (y << 8) | (z << 4);
                for (int x = 0; x < 16; x++) {
                    target[rowBase + x] = (byte) (x % 2 == 0 ? packed[(packedBase | x) >> 1] & 0xF : (packed[(packedBase | x) >> 1] >> 4) & 0xF);
                }
            }
        }
    }

    public void beginFullPopulate() {
        // 整片清零一次：opacity/emission 的初值就是“空气”，所有全空气的 section 因此可以直接跳过，
        // 不必逐行 Arrays.fill（实测那是提取里真正的大头：15 次提取里 2600 多个空气 section,
        // 每个 section 原本要 256 行 × 2 次 fill）。非空 section 反正会被覆盖，清零不影响它们的正确性。
        java.util.Arrays.fill(opacity, (byte) 0);
        java.util.Arrays.fill(emission, (byte) 0);
        // 物化标记必须跟着一起失效：清零之后 halo 的材质又变回“空气”，而 materializeReach 只看标记就跳过，
        // 留着标记等于宣告那片材质是对的 —— 于是全量重算把邻居当空气传播，出射的光穿墙，而且再也补不回来。
        clearLightMaterialized();
        clearDirty();
    }

    public void resetForReuse() {
        java.util.Arrays.fill(opacity, (byte) 0);
        java.util.Arrays.fill(emission, (byte) 0);
        clearLight();
        // 物化标记必须一起清：不清的话采纱失败回退到全量计算的路径会先在清零的基线上算，
        // 而 materializeReach 看到 isLightMaterialized=true 会跳过这些 section，永远不补 halo 材质。
        clearLightMaterialized();
    }

    /**
     * 只回滚“采纳引擎光照”写进去的内容，材质留着。
     * 材质是 {@code populate} 的产物，而回退后的全量计算正需要它 ——
     * 连材质一起清掉的话那次计算跑在“全是空气”的基线上，算出来的光整片穿墙。
     */
    public void resetLightForRecompute() {
        clearLight();
        clearLightMaterialized();
        clearDirty();
    }
}
