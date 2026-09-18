package dev.lucistarlink.light.engine;

import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.runtime.RuntimeRegionBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Cross-region boundary light delta machinery, free of Minecraft types so it can be
 * exercised headlessly. A region job snapshots its border shell before mutating light
 * and, after repairs, emits one delta per changed border cell to every region sharing
 * that border. Neighboring jobs re-evaluate their adjacent cells from those deltas.
 */
public final class BorderDeltaSupport {
    private BorderDeltaSupport() {
    }

    /**
     * Receives cross-region boundary light deltas produced by a region job. Deltas are
     * queued for the owning neighbor region and applied by its next job, which preserves
     * the single-writer rule per region.
     */
    public interface BoundaryDeltaSink {
        void accept(long neighborRegionKey, long[] deltas);
    }

    public static int borderCellCount(RegionLightData data) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int height = data.bounds.heightBlocks();
        return height * (2 * width + 2 * depth - 4);
    }

    public static byte[] snapshotBorder(RegionLightData data, byte[] existing) {
        int count = borderCellCount(data);
        byte[] buffer = existing != null && existing.length >= count * 2 ? existing : new byte[count * 2];
        snapshotBorderLayer(data, data.blockLight, buffer, 0);
        snapshotBorderLayer(data, data.skyLight, buffer, count);
        return buffer;
    }

    private static void snapshotBorderLayer(RegionLightData data, byte[] source, byte[] out, int offset) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int height = data.bounds.heightBlocks();
        int area = data.bounds.area();
        int i = offset;
        for (int y = 0; y < height; y++) {
            int yBase = y * area;
            for (int z = 0; z < depth; z++) {
                int rowBase = yBase + z * width;
                if (z == 0 || z == depth - 1) {
                    for (int x = 0; x < width; x++) {
                        out[i++] = source[rowBase + x];
                    }
                } else {
                    out[i++] = source[rowBase];
                    out[i++] = source[rowBase + width - 1];
                }
            }
        }
    }

    public static void emitBoundaryDeltas(RegionLightData data, byte[] before, BoundaryDeltaSink sink) {
        if (before == null || sink == null) {
            return;
        }
        RegionBounds bounds = data.bounds;
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();
        int area = bounds.area();
        int regionChunks = bounds.regionChunks();
        int count = borderCellCount(data);
        DeltaCollector collector = new DeltaCollector();
        int i = 0;
        int j = count;
        for (int y = 0; y < height; y++) {
            int worldY = bounds.minBuildY() + y;
            int yBase = y * area;
            for (int z = 0; z < depth; z++) {
                int rowBase = yBase + z * width;
                boolean fullRow = z == 0 || z == depth - 1;
                for (int x = 0; x < width; x++) {
                    if (!fullRow && x != 0 && x != width - 1) {
                        continue;
                    }
                    int oldBlock = before[i++] & 0xF;
                    int newBlock = data.blockLight[rowBase + x] & 0xF;
                    if (oldBlock != newBlock) {
                        addBorderDelta(collector, bounds, regionChunks, bounds.minBlockX() + x, worldY,
                                bounds.minBlockZ() + z, x, z, oldBlock, newBlock, false);
                    }
                    int oldSky = before[j++] & 0xF;
                    int newSky = data.skyLight[rowBase + x] & 0xF;
                    if (oldSky != newSky) {
                        addBorderDelta(collector, bounds, regionChunks, bounds.minBlockX() + x, worldY,
                                bounds.minBlockZ() + z, x, z, oldSky, newSky, true);
                    }
                }
            }
        }
        collector.forEach((key, deltas) -> {
            if (deltas.length > 0) {
                sink.accept(key, deltas);
            }
        });
    }

    private static void addBorderDelta(DeltaCollector collector, RegionBounds bounds, int regionChunks,
                                       int worldX, int worldY, int worldZ, int localX, int localZ,
                                       int oldLevel, int newLevel, boolean sky) {
        int dx = localX == 0 ? -1 : (localX == bounds.widthBlocks() - 1 ? 1 : 0);
        int dz = localZ == 0 ? -1 : (localZ == bounds.depthBlocks() - 1 ? 1 : 0);
        int originChunkX = bounds.originChunkX();
        int originChunkZ = bounds.originChunkZ();
        long[] packed = RuntimeRegionBatch.packDelta(worldX, worldY, worldZ, oldLevel, newLevel, sky);
        if (dx != 0) {
            collector.add(RegionBounds.regionKey(originChunkX + dx * regionChunks, originChunkZ), packed[0], packed[1]);
        }
        if (dz != 0) {
            collector.add(RegionBounds.regionKey(originChunkX, originChunkZ + dz * regionChunks), packed[0], packed[1]);
        }
        if (dx != 0 && dz != 0) {
            collector.add(RegionBounds.regionKey(originChunkX + dx * regionChunks, originChunkZ + dz * regionChunks), packed[0], packed[1]);
        }
    }

    private static final class LongList {
        private long[] data = new long[8];
        private int size;

        private LongList add(long value) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size << 1);
            }
            data[size++] = value;
            return this;
        }

        private long[] toArray() {
            return size == data.length ? data : java.util.Arrays.copyOf(data, size);
        }
    }

    private static final class DeltaCollector {
        private final Map<Long, LongList> byNeighbor = new HashMap<>();

        private void add(long regionKey, long pos, long levels) {
            byNeighbor.computeIfAbsent(regionKey, ignored -> new LongList()).add(pos).add(levels);
        }

        private void forEach(BiConsumer<Long, long[]> action) {
            byNeighbor.forEach((key, list) -> action.accept(key, list.toArray()));
        }
    }
}
