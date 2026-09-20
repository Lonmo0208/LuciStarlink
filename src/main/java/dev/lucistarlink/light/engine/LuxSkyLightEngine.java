package dev.lucistarlink.light.engine;

import dev.lucistarlink.light.LuxConstants;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.runtime.RuntimeLightChangeBuffer;
import dev.lucistarlink.light.util.IntBucketQueue;
import dev.lucistarlink.light.util.IntRingQueue;
import dev.lucistarlink.test.LuxBenchmarkSupport;

import java.util.Arrays;

public final class LuxSkyLightEngine {
    private static final int REMOVAL_LEVEL_BITS = 4;
    private static final int REMOVAL_LEVEL_MASK = (1 << REMOVAL_LEVEL_BITS) - 1;
    private static final int REMOVAL_EMPTY = Integer.MIN_VALUE;
    private static final int RUNTIME_FULL_SKY_RECOMPUTE_COLUMN_PERCENT = Math.max(0,
            Math.min(Integer.getInteger("lucistarlink.sky.runtimeFullRecomputeColumnPercent", 75), 100));

    private final ThreadLocal<IntBucketQueue> queues = ThreadLocal.withInitial(() -> new IntBucketQueue(16, 4096));
    private final ThreadLocal<IntRingQueue> removalQueues = ThreadLocal.withInitial(() -> new IntRingQueue(4096));
    private final ThreadLocal<RuntimeColumns> runtimeColumns = ThreadLocal.withInitial(RuntimeColumns::new);

    public void compute(RegionLightData data) {
        IntBucketQueue queue = queues.get();
        queue.clear();
        RegionBounds bounds = data.bounds;
        boolean metrics = LuxBenchmarkSupport.enabled();
        boolean seedSkip = LuxFlags.skySeedSkip;
        int oldSeedCandidates = 0;
        int seeds = 0;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int area = bounds.area();
        int height = bounds.heightBlocks();
        long[] uniformSky = seedSkip ? new long[uniformMaskLength(data)] : null;
        if (uniformSky != null) {
            Arrays.fill(uniformSky, -1L);
        }

        Arrays.fill(data.skyLight, (byte) 0);
        int openColumnsFast = 0;
        for (int z = 0; z < depth; z++) {
            int columnZBase = z * width;
            for (int x = 0; x < width; x++) {
                int columnBase = columnZBase + x;
                int y = height - 1;
                
                // Fast path: detect fully-open columns (all transparent from top to bottom)
                boolean fullyOpen = true;
                for (int scanY = height - 1; scanY >= 0; scanY--) {
                    if ((data.opacity[columnBase + scanY * area] & 0xF) != 0) {
                        fullyOpen = false;
                        break;
                    }
                }
                
                if (fullyOpen) {
                    // Bulk-fill entire column with MAX_LIGHT using Arrays.fill for SIMD-friendly operation
                    for (int fillY = 0; fillY < height; fillY++) {
                        data.skyLight[columnBase + fillY * area] = (byte) LuxConstants.MAX_LIGHT;
                    }
                    if (metrics) {
                        oldSeedCandidates += height;
                        openColumnsFast++;
                    }
                    // Entire column is uniform sky, no need to clear bits
                    continue;
                }
                
                // Standard path for columns with obstacles
                for (; y >= 0; y--) {
                    int index = columnBase + y * area;
                    int opacity = data.opacity[index] & 0xF;
                    int light;
                    if (opacity == 0) {
                        light = LuxConstants.MAX_LIGHT;
                    } else {
                        light = propagatedCandidate(LuxConstants.MAX_LIGHT, opacity);
                    }
                    data.skyLight[index] = (byte) light;
                    if (uniformSky != null && light != LuxConstants.MAX_LIGHT) {
                        clearUniformBit(uniformSky, data, x, y, z);
                    }
                    if (metrics && light > 1) {
                        oldSeedCandidates++;
                    }
                    if (opacity != 0) {
                        break;
                    }
                }
                if (uniformSky != null) {
                    clearUniformBelow(uniformSky, data, x, z, y);
                }
            }
        }
        if (metrics && openColumnsFast > 0) {
            LuxBenchmarkSupport.count("lucistarlink.sky.open_columns_fast", openColumnsFast);
        }

        data.markCoreSkySectionsDirty();
        seeds = seedFrontiers(data, queue, uniformSky, metrics);
        int dequeues = spread(data, queue, metrics);
        if (metrics) {
            LuxBenchmarkSupport.count("lucistarlink.sky.old_seed_candidates", oldSeedCandidates);
            LuxBenchmarkSupport.count("lucistarlink.sky.frontier.seeds", seeds);
            LuxBenchmarkSupport.count("lucistarlink.sky.frontier.skipped", oldSeedCandidates - seeds);
            LuxBenchmarkSupport.count("lucistarlink.sky.spread.dequeues", dequeues);
        }
    }

    private static int uniformMaskLength(RegionLightData data) {
        int sections = data.bounds.sectionCount() * data.sectionsPerPlane;
        return ((sections - 1) >> 6) + 1;
    }

    private static void clearUniformBit(long[] mask, RegionLightData data, int localX, int localY, int localZ) {
        int bit = data.sectionLinearIndexLocal(localX, localY, localZ);
        mask[bit >> 6] &= ~(1L << (bit & 63));
    }

    /**
     * A column whose direct-sky walk ended leaves every cell below the break dark for
     * that column, so no section below the break can be uniformly lit.
     */
    private static void clearUniformBelow(long[] mask, RegionLightData data, int localX, int localZ, int breakY) {
        int sectionWidth = data.sectionWidth;
        int sectionsPerPlane = data.sectionsPerPlane;
        int chunkLocalX = localX >> 4;
        int chunkLocalZ = localZ >> 4;
        int topSection = (breakY >> 4) - 1;
        for (int sectionY = Math.min(topSection, data.bounds.sectionCount() - 1); sectionY >= 0; sectionY--) {
            int bit = chunkLocalX + chunkLocalZ * sectionWidth + sectionY * sectionsPerPlane;
            mask[bit >> 6] &= ~(1L << (bit & 63));
        }
    }

    public void applyRuntimeChanges(RegionLightData data, RuntimeLightChangeBuffer changes) {
        RuntimeColumns columns = runtimeColumns.get();
        collectColumns(data, changes, columns);
        LuxBenchmarkSupport.count("lucistarlink.sky.runtime.columns", columns.count);
        if (columns.count == 0) {
            return;
        }
        if (shouldPromoteToFullSkyRecompute(data, columns)) {
            LuxBenchmarkSupport.count("lucistarlink.sky.runtime.promoted_full_recompute");
            compute(data);
            return;
        }
        applyIncremental(data, columns);
        LuxBenchmarkSupport.count("lucistarlink.sky.runtime.incremental");
    }

    /**
     * Exact incremental skylight repair over the changed columns:
     * 1. recompute each changed column vertically, capturing every value delta;
     * 2. run an unrestricted decrease (unqueue) pass so stale light cannot survive
     *    outside any bounded repair shape;
     * 3. run an increase pass that re-lights from surviving and raised sources.
     */
    private void applyIncremental(RegionLightData data, RuntimeColumns columns) {
        IntBucketQueue adds = queues.get();
        IntRingQueue removals = removalQueues.get();
        adds.clear();
        removals.clear();
        recomputeChangedColumns(data, columns, adds, removals);
        processDecreases(data, adds, removals);
        processIncreases(data, adds);
    }

    private void recomputeChangedColumns(RegionLightData data, RuntimeColumns columns, IntBucketQueue adds, IntRingQueue removals) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        int height = data.bounds.heightBlocks();
        int maxYIndex = height - 1;
        for (int i = 0; i < columns.count; i++) {
            int column = columns.columns[i];
            int localZ = column / width;
            int localX = column - localZ * width;
            int top = Math.min(columns.maxY[column], maxYIndex);
            int recordBottom = columns.minY[column];
            int incoming = top + 1 >= height ? LuxConstants.MAX_LIGHT
                    : (data.skyLight[column + (top + 1) * area] & 0xF);
            for (int y = top; y >= 0; y--) {
                int index = column + y * area;
                int old = data.skyLight[index] & 0xF;
                int candidate = verticalCandidate(incoming, data.opacity[index] & 0xF);
                if (candidate != old) {
                    data.skyLight[index] = (byte) candidate;
                    data.markDirtySkyLocal(localX, y, localZ);
                    if (candidate < old) {
                        enqueueRemoval(removals, index, old);
                    } else if (candidate > 1) {
                        adds.enqueue(candidate, index);
                    }
                }
                if (y >= recordBottom) {
                    enqueueNeighborAdds(data, adds, localX, y, localZ);
                }
                if (y <= recordBottom && incoming == 0 && candidate == 0 && old == 0) {
                    break;
                }
                incoming = candidate;
            }
        }
    }

    private void enqueueNeighborAdds(RegionLightData data, IntBucketQueue adds, int localX, int localY, int localZ) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int height = data.bounds.heightBlocks();
        int index = data.localIndex(localX, localY, localZ);
        if (localX + 1 < width) {
            trySeedNeighbor(data, adds, index + data.offsetPosX);
        }
        if (localX > 0) {
            trySeedNeighbor(data, adds, index + data.offsetNegX);
        }
        if (localZ + 1 < depth) {
            trySeedNeighbor(data, adds, index + data.offsetPosZ);
        }
        if (localZ > 0) {
            trySeedNeighbor(data, adds, index + data.offsetNegZ);
        }
        if (localY + 1 < height) {
            trySeedNeighbor(data, adds, index + data.offsetPosY);
        }
        if (localY > 0) {
            trySeedNeighbor(data, adds, index + data.offsetNegY);
        }
    }

    private void trySeedNeighbor(RegionLightData data, IntBucketQueue adds, int index) {
        int light = data.skyLight[index] & 0xF;
        if (light > 1) {
            adds.enqueue(light, index);
        }
    }

    private void enqueueRemoval(IntRingQueue removals, int index, int lightLevel) {
        if (lightLevel <= 0) {
            return;
        }
        assert index >= 0 && index < (1 << (Integer.SIZE - REMOVAL_LEVEL_BITS));
        assert lightLevel <= REMOVAL_LEVEL_MASK;
        removals.enqueue((index << REMOVAL_LEVEL_BITS) | lightLevel);
    }

    private void processDecreases(RegionLightData data, IntBucketQueue adds, IntRingQueue removals) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        int height = data.bounds.heightBlocks();
        int packed;
        while ((packed = removals.poll()) != REMOVAL_EMPTY) {
            int index = packed >>> REMOVAL_LEVEL_BITS;
            int removedLevel = packed & REMOVAL_LEVEL_MASK;
            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;

            decreaseNeighbor(data, adds, removals, removedLevel, x + 1 < width ? index + data.offsetPosX : -1);
            decreaseNeighbor(data, adds, removals, removedLevel, x > 0 ? index + data.offsetNegX : -1);
            decreaseNeighbor(data, adds, removals, removedLevel, z + 1 < data.bounds.depthBlocks() ? index + data.offsetPosZ : -1);
            decreaseNeighbor(data, adds, removals, removedLevel, z > 0 ? index + data.offsetNegZ : -1);
            decreaseNeighbor(data, adds, removals, removedLevel, y + 1 < height ? index + data.offsetPosY : -1);
            decreaseNeighbor(data, adds, removals, removedLevel, y > 0 ? index + data.offsetNegY : -1);
        }
    }

    private void decreaseNeighbor(RegionLightData data, IntBucketQueue adds, IntRingQueue removals,
                                  int removedLevel, int nextIndex) {
        if (nextIndex < 0) {
            return;
        }
        int neighborLight = data.skyLight[nextIndex] & 0xF;
        if (neighborLight == 0) {
            return;
        }
        if (neighborLight < removedLevel) {
            data.skyLight[nextIndex] = 0;
            data.markDirtySkyIndex(nextIndex);
            enqueueRemoval(removals, nextIndex, neighborLight);
        } else if (neighborLight > 1) {
            adds.enqueue(neighborLight, nextIndex);
        }
    }

    private void processIncreases(RegionLightData data, IntBucketQueue adds) {
        spread(data, adds, false);
    }

    private boolean shouldPromoteToFullSkyRecompute(RegionLightData data, RuntimeColumns columns) {
        if (RUNTIME_FULL_SKY_RECOMPUTE_COLUMN_PERCENT <= 0 || !columns.opaqueSkyCutAdded || columns.openedSkyPath) {
            return false;
        }
        int totalColumns = data.bounds.widthBlocks() * data.bounds.depthBlocks();
        return columns.count * 100 >= totalColumns * RUNTIME_FULL_SKY_RECOMPUTE_COLUMN_PERCENT;
    }

    private void collectColumns(RegionLightData data, RuntimeLightChangeBuffer changes, RuntimeColumns columns) {
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        columns.reset(width * data.bounds.depthBlocks());
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            if (!RuntimeLightChangeBuffer.hasSkyChange(change)) {
                continue;
            }
            int index = RuntimeLightChangeBuffer.localIndex(change);
            int localY = index / area;
            int column = index - localY * area;
            if (RuntimeLightChangeBuffer.hasSkyMaterialChange(change)) {
                int localZ = column / width;
                int localX = column - localZ * width;
                data.markDirtySkyLocal(localX, localY, localZ);
            }
            columns.add(column, localY, requiresFullDepthRepair(data, change, index, localY),
                    RuntimeLightChangeBuffer.oldOpacity(change), RuntimeLightChangeBuffer.newOpacity(change));
        }
    }

    private boolean requiresFullDepthRepair(RegionLightData data, long change, int index, int localY) {
        if (RuntimeLightChangeBuffer.oldOpacity(change) == RuntimeLightChangeBuffer.newOpacity(change)) {
            return false;
        }
        if ((data.skyLight[index] & 0xF) != 0) {
            return true;
        }
        return localY + 1 >= data.bounds.heightBlocks()
                || (data.skyLight[index + data.offsetPosY] & 0xF) != 0;
    }

    private int seedFrontiers(RegionLightData data, IntBucketQueue queue, long[] uniformSky, boolean metrics) {
        if (uniformSky != null) {
            return seedFrontiersSectionSkipped(data, queue, uniformSky, metrics);
        }
        RegionBounds bounds = data.bounds;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int area = bounds.area();
        int height = bounds.heightBlocks();
        int seeds = 0;
        for (int y = 0; y < height; y++) {
            int yBase = y * area;
            for (int z = 0; z < depth; z++) {
                int rowBase = yBase + z * width;
                for (int x = 0; x < width; x++) {
                    int index = rowBase + x;
                    int current = data.skyLight[index] & 0xF;
                    if (current > 1) {
                        seeds += seedKnownFrontier(data, queue, current, x, y, z, index);
                    }
                }
            }
        }
        return seeds;
    }

    /**
     * Homogeneity-certified seeding: a section whose cells are all direct-sky sources
     * (uniform 15) cannot improve any equally lit neighbor, and every genuinely darker
     * cell lives in a non-uniform section whose own scan seeds the pair. Uniform
     * sections are therefore skipped entirely.
     */
    private int seedFrontiersSectionSkipped(RegionLightData data, IntBucketQueue queue, long[] uniformSky, boolean metrics) {
        RegionBounds bounds = data.bounds;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int area = bounds.area();
        int height = bounds.heightBlocks();
        int sectionsPerPlane = data.sectionsPerPlane;
        int sectionWidth = data.sectionWidth;
        int sectionCount = bounds.sectionCount();
        int seeds = 0;
        for (int sectionY = 0; sectionY < sectionCount; sectionY++) {
            int yBase = sectionY * sectionsPerPlane;
            for (int chunkZ = 0; chunkZ < sectionWidth; chunkZ++) {
                for (int chunkX = 0; chunkX < sectionWidth; chunkX++) {
                    int bit = chunkX + chunkZ * sectionWidth + yBase;
                    if ((uniformSky[bit >> 6] & (1L << (bit & 63))) != 0) {
                        if (metrics) {
                            LuxBenchmarkSupport.count("lucistarlink.sky.sections.uniform_skipped");
                        }
                        continue;
                    }
                    int minBlockX = bounds.minBlockX();
                    int minBlockZ = bounds.minBlockZ();
                    int worldSectionX = minBlockX + (chunkX << 4);
                    int worldSectionZ = minBlockZ + (chunkZ << 4);
                    int worldSectionY = (bounds.minSectionY() + sectionY) << 4;
                    for (int y = 0; y < 16; y++) {
                        int worldY = worldSectionY + y;
                        if (worldY < bounds.minBuildY() || worldY >= bounds.maxBuildY()) {
                            continue;
                        }
                        int rowBase = (worldY - bounds.minBuildY()) * area + (worldSectionZ - minBlockZ) * width;
                        for (int z = 0; z < 16; z++, rowBase += width) {
                            int index = rowBase + (worldSectionX - minBlockX);
                            for (int x = 0; x < 16; x++, index++) {
                                int current = data.skyLight[index] & 0xF;
                                if (current > 1) {
                                    seeds += seedKnownFrontier(data, queue, current,
                                            worldSectionX - minBlockX + x, worldY - bounds.minBuildY(),
                                            worldSectionZ - minBlockZ + z, index);
                                }
                            }
                        }
                    }
                }
            }
        }
        return seeds;
    }

    private int seedKnownFrontier(RegionLightData data, IntBucketQueue queue, int current, int x, int y, int z, int index) {
        int seeds = 0;
        boolean queuedCurrent = false;
        if (x > 0) {
            int westIndex = index + data.offsetNegX;
            int west = data.skyLight[westIndex] & 0xF;
            if (canImproveSky(data, current, westIndex)) {
                queue.enqueue(current, index);
                queuedCurrent = true;
                seeds++;
            } else if (canImproveSky(data, west, index)) {
                queue.enqueue(west, westIndex);
                seeds++;
            }
        }
        if (z > 0) {
            int northIndex = index + data.offsetNegZ;
            int north = data.skyLight[northIndex] & 0xF;
            if (canImproveSky(data, current, northIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, north, index)) {
                queue.enqueue(north, northIndex);
                seeds++;
            }
        }
        if (x + 1 < data.bounds.widthBlocks()) {
            int eastIndex = index + data.offsetPosX;
            int east = data.skyLight[eastIndex] & 0xF;
            if (canImproveSky(data, current, eastIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, east, index)) {
                queue.enqueue(east, eastIndex);
                seeds++;
            }
        }
        if (z + 1 < data.bounds.depthBlocks()) {
            int southIndex = index + data.offsetPosZ;
            int south = data.skyLight[southIndex] & 0xF;
            if (canImproveSky(data, current, southIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, south, index)) {
                queue.enqueue(south, southIndex);
                seeds++;
            }
        }
        if (y > 0) {
            int downIndex = index + data.offsetNegY;
            int down = data.skyLight[downIndex] & 0xF;
            if (canImproveSky(data, current, downIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, down, index)) {
                queue.enqueue(down, downIndex);
                seeds++;
            }
        }
        if (y + 1 < data.bounds.heightBlocks()) {
            int upIndex = index + data.offsetPosY;
            int up = data.skyLight[upIndex] & 0xF;
            if (canImproveSky(data, current, upIndex)) {
                if (!queuedCurrent) {
                    queue.enqueue(current, index);
                    queuedCurrent = true;
                    seeds++;
                }
            } else if (canImproveSky(data, up, index)) {
                queue.enqueue(up, upIndex);
                seeds++;
            }
        }
        return seeds;
    }

    private boolean canImproveSky(RegionLightData data, int current, int nextIndex) {
        int candidate = propagatedCandidate(current, data.opacity[nextIndex] & 0xF);
        return candidate > (data.skyLight[nextIndex] & 0xF);
    }

    private int spread(RegionLightData data, IntBucketQueue queue, boolean metrics) {
        RegionBounds bounds = data.bounds;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int area = bounds.area();
        int dequeues = 0;
        int index;
        while ((index = queue.poll()) >= 0) {
            if (metrics) {
                dequeues++;
            }
            int current = data.skyLight[index] & 0xF;
            if (current <= 1) {
                continue;
            }

            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;

            if (x + 1 < width) {
                spreadTo(data, queue, current, x + 1, y, z, index + data.offsetPosX);
            }
            if (x > 0) {
                spreadTo(data, queue, current, x - 1, y, z, index + data.offsetNegX);
            }
            if (z + 1 < depth) {
                spreadTo(data, queue, current, x, y, z + 1, index + data.offsetPosZ);
            }
            if (z > 0) {
                spreadTo(data, queue, current, x, y, z - 1, index + data.offsetNegZ);
            }
            if (y + 1 < bounds.heightBlocks()) {
                spreadTo(data, queue, current, x, y + 1, z, index + data.offsetPosY);
            }
            if (y > 0) {
                spreadTo(data, queue, current, x, y - 1, z, index + data.offsetNegY);
            }
        }
        return dequeues;
    }

    private void spreadTo(RegionLightData data, IntBucketQueue queue, int current, int nextX, int nextY, int nextZ, int nextIndex) {
        int candidate = propagatedCandidate(current, data.opacity[nextIndex] & 0xF);
        if (candidate > (data.skyLight[nextIndex] & 0xF)) {
            data.skyLight[nextIndex] = (byte) candidate;
            data.markDirtySkyLocal(nextX, nextY, nextZ);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }

    private static int verticalCandidate(int incoming, int opacity) {
        if (incoming <= 0) {
            return 0;
        }
        if (incoming == LuxConstants.MAX_LIGHT && opacity == 0) {
            return LuxConstants.MAX_LIGHT;
        }
        return propagatedCandidate(incoming, opacity);
    }

    private static int propagatedCandidate(int incoming, int opacity) {
        int candidate = incoming - propagationCost(opacity);
        return candidate > 0 ? candidate : 0;
    }

    private static int propagationCost(int opacity) {
        return opacity <= 0 ? 1 : opacity;
    }

    private static final class RuntimeColumns {
        private int[] maxY = new int[0];
        private int[] minY = new int[0];
        private int[] stamps = new int[0];
        private int[] columns = new int[0];
        private int stamp;
        private int count;
        private boolean fullDepthRepair;
        private boolean opaqueSkyCutAdded;
        private boolean openedSkyPath;

        private void reset(int capacity) {
            ensureCapacity(capacity);
            count = 0;
            fullDepthRepair = false;
            opaqueSkyCutAdded = false;
            openedSkyPath = false;
            stamp++;
            if (stamp == 0) {
                Arrays.fill(stamps, 0);
                stamp = 1;
            }
        }

        private void ensureCapacity(int capacity) {
            if (maxY.length >= capacity) {
                return;
            }
            maxY = new int[capacity];
            minY = new int[capacity];
            stamps = new int[capacity];
            columns = new int[capacity];
        }

        private void add(int column, int localY, boolean fullDepthRepair, int oldOpacity, int newOpacity) {
            if (fullDepthRepair) {
                this.fullDepthRepair = true;
                if (oldOpacity < LuxConstants.MAX_LIGHT && newOpacity >= LuxConstants.MAX_LIGHT) {
                    opaqueSkyCutAdded = true;
                }
                if (newOpacity < oldOpacity) {
                    openedSkyPath = true;
                }
            }
            if (stamps[column] != stamp) {
                stamps[column] = stamp;
                maxY[column] = localY;
                minY[column] = localY;
                columns[count++] = column;
            } else if (localY > maxY[column]) {
                maxY[column] = localY;
            } else if (localY < minY[column]) {
                minY[column] = localY;
            }
        }
    }
}
