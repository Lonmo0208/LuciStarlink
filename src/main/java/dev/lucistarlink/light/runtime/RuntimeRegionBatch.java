package dev.lucistarlink.light.runtime;

import java.util.List;

public record RuntimeRegionBatch(List<BlockChangeRecord> changes, boolean fullRelight, long originalChangeCount,
                                 long[] boundaryDeltas) {
    public static RuntimeRegionBatch incremental(List<BlockChangeRecord> changes) {
        return new RuntimeRegionBatch(changes, false, changes == null ? 0 : changes.size(), null);
    }

    public static RuntimeRegionBatch fullRelight(long originalChangeCount) {
        return new RuntimeRegionBatch(List.of(), true, originalChangeCount, null);
    }

    public RuntimeRegionBatch withBoundaryDeltas(long[] deltas) {
        return new RuntimeRegionBatch(changes, fullRelight, originalChangeCount, deltas);
    }

    public int queuedChangeCount() {
        return changes == null ? 0 : changes.size();
    }

    public int queuedWorkCount() {
        return fullRelight ? 1 : queuedChangeCount();
    }

    public boolean isEmpty() {
        return !fullRelight && queuedChangeCount() == 0 && isEmptyDeltaSet();
    }

    public boolean isEmptyDeltaSet() {
        return boundaryDeltas == null || boundaryDeltas.length == 0;
    }

    /**
     * Packs a cross-region boundary light delta into two longs: the source cell position
     * (x 26b | z 26b | y 12b) and the level payload (old 4b | new 4b | layer flag). Two
     * words are required because signed world coordinates do not fit alongside the
     * levels in one word.
     */
    public static long[] packDelta(int x, int y, int z, int oldLevel, int newLevel, boolean sky) {
        long pos = ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | ((long) (y & 0xFFF));
        long levels = ((long) (oldLevel & 0xF) << 4)
                | (newLevel & 0xF)
                | (sky ? SKY_FLAG : 0L);
        return new long[]{pos, levels};
    }

    private static final long SKY_FLAG = 1L << 60;

    public static boolean deltaIsSky(long levels) {
        return (levels & SKY_FLAG) != 0;
    }

    public static int deltaX(long pos) {
        return (int) (pos >> 38);
    }

    public static int deltaZ(long pos) {
        return (int) ((pos << 26) >> 38);
    }

    public static int deltaY(long pos) {
        return (int) ((pos << 52) >> 52);
    }

    public static int deltaOldLevel(long levels) {
        return (int) ((levels >> 4) & 0xF);
    }

    public static int deltaNewLevel(long levels) {
        return (int) (levels & 0xF);
    }
}
