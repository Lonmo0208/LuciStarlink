package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class RuntimeUpdateQueue {
    private final ConcurrentHashMap<Long, PendingRegion> pendingByRegion = new ConcurrentHashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final int maxPendingRecords;
    private final int fullRelightChangeThreshold;

    public RuntimeUpdateQueue(int maxPendingRecords, int fullRelightChangeThreshold) {
        this.maxPendingRecords = Math.max(1, maxPendingRecords);
        this.fullRelightChangeThreshold = Math.max(1, fullRelightChangeThreshold);
    }

    public boolean enqueue(long regionKey, int x, int y, int z, BlockState oldState, BlockState newState) {
        int reserved = pendingCount.incrementAndGet();
        if (reserved > maxPendingRecords) {
            pendingCount.decrementAndGet();
            return false;
        }
        PendingRegion pending = pendingByRegion.computeIfAbsent(regionKey, ignored -> new PendingRegion());
        pending.enqueue(x, y, z, oldState, newState, fullRelightChangeThreshold);
        return true;
    }

    public int enqueueAll(long regionKey, Collection<BlockChangeRecord> records) {
        int accepted = 0;
        for (BlockChangeRecord record : records) {
            if (record != null && enqueue(regionKey, record.x(), record.y(), record.z(), record.oldState(), record.newState())) {
                accepted++;
            }
        }
        return accepted;
    }

    /**
     * Full relights never went through the record counter, so a single huge bulk write could insert one
     * region entry per touched chunk without any bound. New regions are therefore refused once the region
     * table reached {@code maxPendingRecords}; regions already queued are always accepted because they only
     * add a work reservation, not an entry.
     *
     * @return false when the request was refused to stay inside the budget
     */
    public boolean enqueueFullRelight(long regionKey, long originalChangeCount) {
        PendingRegion pending = pendingByRegion.get(regionKey);
        if (pending == null) {
            if (pendingByRegion.size() >= maxPendingRecords) {
                return false;
            }
            pending = pendingByRegion.computeIfAbsent(regionKey, ignored -> new PendingRegion());
        }
        int queued = pending.enqueueFullRelight(originalChangeCount);
        pendingCount.addAndGet(queued);
        return true;
    }

    public void enqueueBoundaryDeltas(long regionKey, long[] deltas) {
        if (deltas == null || deltas.length == 0) {
            return;
        }
        PendingRegion pending = pendingByRegion.computeIfAbsent(regionKey, ignored -> new PendingRegion());
        pending.enqueueDeltas(deltas);
        pendingCount.addAndGet(deltas.length);
    }

    public boolean hasFullRelight(long regionKey) {
        PendingRegion pending = pendingByRegion.get(regionKey);
        return pending != null && pending.hasFullRelight();
    }

    public int drainTo(Map<Long, RuntimeRegionBatch> drained) {
        int count = 0;
        for (Map.Entry<Long, PendingRegion> entry : pendingByRegion.entrySet()) {
            PendingRegion pending = pendingByRegion.remove(entry.getKey());
            if (pending == null) {
                continue;
            }
            DrainedRegion drainedRegion = pending.drain();
            RuntimeRegionBatch batch = drainedRegion.batch();
            if (batch.isEmpty()) {
                continue;
            }
            count += drainedRegion.reservations();
            drained.merge(entry.getKey(), batch, RuntimeUpdateQueue::mergeBatches);
        }
        pendingCount.addAndGet(-count);
        return count;
    }

    public boolean isEmpty() {
        return pendingCount.get() <= 0 || pendingByRegion.isEmpty();
    }

    /** True when this region has queued work (records, a full relight, or boundary deltas). */
    public boolean hasRegion(long regionKey) {
        return pendingByRegion.containsKey(regionKey);
    }

    public boolean hasCapacity() {
        return pendingCount.get() < maxPendingRecords;
    }

    public void clear() {
        pendingByRegion.clear();
        pendingCount.set(0);
    }

    public int pendingRecordCount() {
        return Math.max(0, pendingCount.get());
    }

    public int regionCount() {
        return pendingByRegion.size();
    }

    private static RuntimeRegionBatch mergeBatches(RuntimeRegionBatch first, RuntimeRegionBatch second) {
        if (first.fullRelight() || second.fullRelight()) {
            return RuntimeRegionBatch.fullRelight(first.originalChangeCount() + second.originalChangeCount());
        }
        ArrayList<BlockChangeRecord> merged = new ArrayList<>(first.queuedChangeCount() + second.queuedChangeCount());
        merged.addAll(first.changes());
        merged.addAll(second.changes());
        long[] deltas = mergeDeltas(first.boundaryDeltas(), second.boundaryDeltas());
        return new RuntimeRegionBatch(merged, false, first.originalChangeCount() + second.originalChangeCount(), deltas);
    }

    private static long[] mergeDeltas(long[] first, long[] second) {
        if (first == null || first.length == 0) {
            return second;
        }
        if (second == null || second.length == 0) {
            return first;
        }
        long[] merged = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }

    private record DrainedRegion(RuntimeRegionBatch batch, int reservations) {
    }

    private static final class PendingRegion {
        private final HashMap<Long, BlockChangeRecord> changes = new HashMap<>();
        private LongArrayDeltas deltas;
        private boolean fullRelight;
        private long originalChangeCount;
        private int reservations;
        private int deltaCount;

        private synchronized void enqueue(int x, int y, int z, BlockState oldState, BlockState newState, int fullRelightChangeThreshold) {
            originalChangeCount++;
            reservations++;
            if (fullRelight) {
                return;
            }
            changes.put(blockKey(x, y, z), new BlockChangeRecord(x, y, z, oldState, newState));
            if (changes.size() >= fullRelightChangeThreshold) {
                fullRelight = true;
                changes.clear();
            }
        }

        private synchronized void enqueueDeltas(long[] incoming) {
            if (deltas == null) {
                deltas = new LongArrayDeltas(incoming);
            } else {
                deltas.append(incoming);
            }
            deltaCount += incoming.length;
        }

        private synchronized int enqueueFullRelight(long changes) {
            long count = Math.max(1L, changes);
            originalChangeCount += count;
            boolean wasFullRelight = fullRelight;
            int previousReservations = reservations;
            fullRelight = true;
            this.changes.clear();
            int queued = wasFullRelight || previousReservations > 0 ? 0 : 1;
            reservations += queued;
            return queued;
        }

        private synchronized boolean hasFullRelight() {
            return fullRelight;
        }

        private synchronized DrainedRegion drain() {
            int drainedReservations = reservations;
            int drainedDeltaCount = deltaCount;
            reservations = 0;
            deltaCount = 0;
            long[] drainedDeltas = deltas == null ? null : deltas.take();
            if (fullRelight) {
                RuntimeRegionBatch batch = new RuntimeRegionBatch(List.of(), true, originalChangeCount, drainedDeltas);
                fullRelight = false;
                originalChangeCount = 0L;
                changes.clear();
                return new DrainedRegion(batch, drainedReservations + drainedDeltaCount);
            }
            ArrayList<BlockChangeRecord> drained = new ArrayList<>(changes.values());
            changes.clear();
            long drainedOriginalChangeCount = originalChangeCount;
            originalChangeCount = 0L;
            return new DrainedRegion(new RuntimeRegionBatch(drained, false, drainedOriginalChangeCount, drainedDeltas),
                    drainedReservations + drainedDeltaCount);
        }

        private static long blockKey(int x, int y, int z) {
            return (((long) x & 0x3ffffffL) << 38) | (((long) z & 0x3ffffffL) << 12) | (y & 0xfffL);
        }
    }

    private static final class LongArrayDeltas {
        private long[] data;
        private int size;

        private LongArrayDeltas(long[] initial) {
            data = initial.clone();
            size = initial.length;
        }

        private void append(long[] incoming) {
            if (size + incoming.length > data.length) {
                data = Arrays.copyOf(data, Math.max(size + incoming.length, data.length << 1));
            }
            System.arraycopy(incoming, 0, data, size, incoming.length);
            size += incoming.length;
        }

        private long[] take() {
            if (size == 0) {
                return null;
            }
            long[] out = size == data.length ? data : Arrays.copyOf(data, size);
            data = new long[0];
            size = 0;
            return out;
        }
    }
}
