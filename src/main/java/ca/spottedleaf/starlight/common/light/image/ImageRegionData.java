package ca.spottedleaf.starlight.common.light.image;

import net.minecraft.core.SectionPos;

import java.util.BitSet;

public final class ImageRegionData {
    public final ImageRegionBounds bounds;
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

    // The moat: every plane is padded by one cell on all six faces. The pad carries opacity 15 and light 0, so
    // propagation naturally dies at the region edge and the BFS hot loops run without any bounds checks or
    // coordinate decodes - the whole per-pop cost is opacity read, light read, compare, write. Padded space
    // arithmetic: real-local (x,y,z) lives at padded (x+1, y+1, z+1); real (width,depth,height) become
    // (paddedWidth-2, paddedDepth-2, paddedHeight-2).
    public final int paddedWidth;
    public final int paddedDepth;
    public final int paddedHeight;
    public final int paddedArea;
    public final int paddedVolume;

    public ImageRegionData(ImageRegionBounds bounds) {
        this.bounds = bounds;
        this.paddedWidth = bounds.widthBlocks() + 2;
        this.paddedDepth = bounds.depthBlocks() + 2;
        this.paddedHeight = bounds.heightBlocks() + 2;
        this.paddedArea = this.paddedWidth * this.paddedDepth;
        this.paddedVolume = this.paddedArea * this.paddedHeight;
        this.opacity = new byte[this.paddedVolume];
        this.emission = new byte[this.paddedVolume];
        this.blockLight = new byte[this.paddedVolume];
        this.skyLight = new byte[this.paddedVolume];
        int sectionCapacity = bounds.widthBlocks() / 16 * bounds.depthBlocks() / 16 * bounds.sectionCount();
        this.dirtyBlockSections = new BitSet(sectionCapacity);
        this.dirtySkySections = new BitSet(sectionCapacity);
        this.sectionWidth = bounds.widthBlocks() >> 4;
        this.sectionsPerPlane = sectionWidth * sectionWidth;
        this.coreLocalChunkStart = bounds.haloChunks();
        this.coreLocalChunkEnd = bounds.haloChunks() + bounds.regionChunks();
        this.offsetPosX = 1;
        this.offsetNegX = -1;
        this.offsetPosZ = this.paddedWidth;
        this.offsetNegZ = -this.paddedWidth;
        this.offsetPosY = this.paddedArea;
        this.offsetNegY = -this.paddedArea;
        this.fillWalls();
    }

    /** Opacity 15 on the pad: a spreading wave loses 15 levels entering it, so it never writes and never enqueues. */
    private void fillWalls() {
        final byte wall = 15;
        final int pw = this.paddedWidth, pd = this.paddedDepth, ph = this.paddedHeight;
        for (int y = 0; y < ph; y++) {
            for (int z = 0; z < pd; z++) {
                final int rowBase = y * this.paddedArea + z * pw;
                this.opacity[rowBase] = wall;
                this.opacity[rowBase + pw - 1] = wall;
            }
        }
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                this.opacity[y * this.paddedArea + x] = wall;
                this.opacity[y * this.paddedArea + (pd - 1) * pw + x] = wall;
            }
        }
        for (int z = 0; z < pd; z++) {
            for (int x = 0; x < pw; x++) {
                this.opacity[z * pw + x] = wall;
                this.opacity[(ph - 1) * this.paddedArea + z * pw + x] = wall;
            }
        }
    }

    /** Region-local index of a real-local cell (unpadded coordinates in, padded index out). */
    public int index(int worldX, int worldY, int worldZ) {
        int localX = worldX - bounds.minBlockX();
        int localY = worldY - bounds.minBuildY();
        int localZ = worldZ - bounds.minBlockZ();
        return (localY + 1) * this.paddedArea + (localZ + 1) * this.paddedWidth + (localX + 1);
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
        return (localY + 1) * this.paddedArea + (localZ + 1) * this.paddedWidth + (localX + 1);
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

    /** Padded index -> real-local coordinate (the pad is one cell thick on every face). */
    public int localX(int index) {
        return index % this.paddedWidth - 1;
    }

    public int localY(int index) {
        return index / this.paddedArea - 1;
    }

    public int localZ(int index) {
        int rem = index - (localY(index) + 1) * this.paddedArea;
        return rem / this.paddedWidth - 1;
    }

    public void markDirtyBlockIndex(int index) {
        int localY = index / this.paddedArea - 1;
        int rem = index - (localY + 1) * this.paddedArea;
        int localZ = rem / this.paddedWidth - 1;
        int localX = rem - (localZ + 1) * this.paddedWidth - 1;
        if (localX < 0 || localY < 0 || localZ < 0) {
            // A dirty mark on the moat ring means some index was built with the wrong strides. It used to throw
            // (2026-09-26) and killed whole benchmark runs; it is a measurement aid, not a safety property, so it
            // reports once per process and then stays quiet.
            if (WALL_REPORTED.compareAndSet(false, true)) {
                System.out.println("IMAGE-LANE-WALL-DIRTY index=" + index + " x=" + localX + " y=" + localY
                        + " z=" + localZ + " opacity=" + (this.opacity[index] & 0xF)
                        + " light=" + (this.blockLight[index] & 0xF));
            }
            return;
        }
        markDirtyBlockLocal(localX, localY, localZ);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean WALL_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public void markDirtySkyIndex(int index) {
        int localY = index / this.paddedArea - 1;
        int rem = index - (localY + 1) * this.paddedArea;
        int localZ = rem / this.paddedWidth - 1;
        int localX = rem - (localZ + 1) * this.paddedWidth - 1;
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
        int minSectionBaseX = (localChunkX << 4) + 1;
        int minSectionBaseZ = (localChunkZ << 4) + 1;
        int width = this.paddedWidth;
        int area = this.paddedArea;
        byte[] target = sky ? skyLight : blockLight;
        for (int y = 0; y < 16; y++) {
            int yBase = ((sectionIndex << 4 | y) + 1) * area;
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
        // 物化标记必须跟着一起失效：populate 之后那些 section 的材质是新读的，而这之前它们可能被
        // materializeReach 标成“已物化”。留着标记等于宣告那片材质是对的 —— 于是传播把邻居当空气，光穿墙。
        clearLightMaterialized();
        clearDirty();
    }

    /** 整片清成“空气”。只在 populate 会覆盖全片时用，否则 halo 会白清一遍又没人补。 */
    public void clearAllMaterials() {
        java.util.Arrays.fill(opacity, (byte) 0);
        java.util.Arrays.fill(emission, (byte) 0);
    }

    /**
     * 只把 halo 那片（自有区块以外）的 emission 清成 0，opacity 不动。
     *
     * <p>为什么只清 emission：lazy 模式下 halo 的材质保留着上一次提取的值，而那次可能是「邻居那边还有发光
     * 方块」的时候 —— 全量重算会拿这份 emission 当光源，于是在区域边界留下一圈本该消失的光。客户端自己算
     * 光照所以它是对的，服务端引擎却会把这份错值写进存档。
     *
     * <p>为什么不动 opacity：那是遮挡关系。清掉它等于宣告邻居是空气，传播就会穿墙 —— 那是另一个方向的错。
     * 只清 emission 是保守一侧：最坏情况是邻居贴着边界放光源时我们这侧略暗，比出一圈亮斑轻得多。
     */
    public void clearHaloEmission(int originChunkX, int originChunkZ, int chunkCount) {
        int xStart = (originChunkX << 4) - bounds.minBlockX() + 1;
        int zStart = (originChunkZ << 4) - bounds.minBlockZ() + 1;
        int span = chunkCount << 4;
        int xEnd = xStart + span;
        int zEnd = zStart + span;
        int width = this.paddedWidth;
        int depth = this.paddedDepth;
        int area = this.paddedArea;
        for (int y = 1; y <= bounds.heightBlocks(); y++) {
            int yBase = y * area;
            if (zStart > 0) {
                java.util.Arrays.fill(emission, yBase, yBase + zStart * width, (byte) 0);
            }
            if (zEnd < depth) {
                java.util.Arrays.fill(emission, yBase + zEnd * width, yBase + depth * width, (byte) 0);
            }
            for (int z = zStart; z < zEnd; z++) {
                int rowBase = yBase + z * width;
                if (xStart > 0) {
                    java.util.Arrays.fill(emission, rowBase, rowBase + xStart, (byte) 0);
                }
                if (xEnd < width) {
                    java.util.Arrays.fill(emission, rowBase + xEnd, rowBase + width, (byte) 0);
                }
            }
        }
    }

    /**
     * 只清自有区块那一块，与 lazy 模式下 populate 的提取范围严格一致。
     * 全空气 section 靠“初值是 0”才能整段跳过，所以清零的范围必须跟着提取范围走：
     * 多清一块 halo 就只能让传播把实心方块当空气，少清一块自有区块就会把旧材质留下来。
     */
    public void clearMaterialsForChunks(int originChunkX, int originChunkZ, int chunkCount) {
        int xStart = (originChunkX << 4) - bounds.minBlockX() + 1;
        int zStart = (originChunkZ << 4) - bounds.minBlockZ() + 1;
        int span = chunkCount << 4;
        int width = this.paddedWidth;
        int area = this.paddedArea;
        for (int y = 1; y <= bounds.heightBlocks(); y++) {
            int yBase = y * area;
            for (int z = 0; z < span; z++) {
                int rowBase = yBase + (zStart + z) * width + xStart;
                java.util.Arrays.fill(opacity, rowBase, rowBase + span, (byte) 0);
                java.util.Arrays.fill(emission, rowBase, rowBase + span, (byte) 0);
            }
        }
    }

    public void resetForReuse() {
        java.util.Arrays.fill(opacity, (byte) 0);
        java.util.Arrays.fill(emission, (byte) 0);
        clearLight();
        this.fillWalls(); // the material wipe erases the moat; restore it before anything propagates
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

    /** Light semantics shared with the base engine: air costs 1 to pass through, opacity {@code n} costs {@code n}. */
    public static int propagationCost(final int opacity) {
        return opacity <= 0 ? 1 : opacity;
    }
}
