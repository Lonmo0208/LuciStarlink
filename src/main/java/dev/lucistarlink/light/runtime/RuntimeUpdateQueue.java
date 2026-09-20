package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
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
        // 用 compute 而不是 computeIfAbsent + 锁外写：drainTo 也用 compute 摘除条目，两者走同一把 bin 锁。
        // 先取对象再写会掉进中间窗口 —— 条目已被摘走、记录写进一个不再可达的对象，于是这次改动永不重算，
        // 预留计数也永远不归还。
        pendingByRegion.compute(regionKey, (key, pending) -> {
            PendingRegion target = pending != null ? pending : new PendingRegion();
            target.enqueue(x, y, z, oldState, newState, fullRelightChangeThreshold);
            return target;
        });
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
        PendingRegion existing = pendingByRegion.get(regionKey);
        if (existing == null && pendingByRegion.size() >= maxPendingRecords) {
            return false;
        }
        // 同 enqueue：入队必须在 compute 的 bin 锁内完成，否则会写进一个正在被 drainTo 摘除的条目。
        int[] queuedHolder = new int[1];
        pendingByRegion.compute(regionKey, (key, pending) -> {
            PendingRegion target = pending != null ? pending : new PendingRegion();
            queuedHolder[0] = target.enqueueFullRelight(originalChangeCount);
            return target;
        });
        pendingCount.addAndGet(queuedHolder[0]);
        return true;
    }

    public boolean hasFullRelight(long regionKey) {
        PendingRegion pending = pendingByRegion.get(regionKey);
        return pending != null && pending.hasFullRelight();
    }

    public int drainTo(Map<Long, RuntimeRegionBatch> drained) {
        int count = 0;
        // A plain remove(key) followed by pending.drain() can lose work: an enqueue that had already resolved the
        // PendingRegion through computeIfAbsent before the remove would add its records to an object that is no
        // longer in the map, and nothing would ever drain them (the reservation count stayed high as well).
        // compute() takes the same bin lock as computeIfAbsent, so either the enqueue lands before the drain and is
        // included, or it lands after the entry is gone and creates a fresh one for the next round.
        for (Long key : new java.util.ArrayList<>(pendingByRegion.keySet())) {
            DrainedRegion[] holder = new DrainedRegion[1];
            pendingByRegion.compute(key, (ignored, pending) -> {
                if (pending == null) {
                    return null;
                }
                holder[0] = pending.drain();
                return null;
            });
            DrainedRegion drainedRegion = holder[0];
            if (drainedRegion == null) {
                continue;
            }
            RuntimeRegionBatch batch = drainedRegion.batch();
            if (batch.isEmpty()) {
                continue;
            }
            count += drainedRegion.reservations();
            drained.merge(key, batch, RuntimeUpdateQueue::mergeBatches);
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
        return new RuntimeRegionBatch(merged, false, first.originalChangeCount() + second.originalChangeCount());
    }

    private record DrainedRegion(RuntimeRegionBatch batch, int reservations) {
    }

    private static final class PendingRegion {
        private final HashMap<Long, BlockChangeRecord> changes = new HashMap<>();
        private boolean fullRelight;
        private long originalChangeCount;
        private int reservations;

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
            reservations = 0;
            if (fullRelight) {
                RuntimeRegionBatch batch = new RuntimeRegionBatch(List.of(), true, originalChangeCount);
                fullRelight = false;
                originalChangeCount = 0L;
                changes.clear();
                return new DrainedRegion(batch, drainedReservations);
            }
            ArrayList<BlockChangeRecord> drained = new ArrayList<>(changes.values());
            changes.clear();
            long drainedOriginalChangeCount = originalChangeCount;
            originalChangeCount = 0L;
            return new DrainedRegion(new RuntimeRegionBatch(drained, false, drainedOriginalChangeCount),
                    drainedReservations);
        }

        private static long blockKey(int x, int y, int z) {
            return (((long) x & 0x3ffffffL) << 38) | (((long) z & 0x3ffffffL) << 12) | (y & 0xfffL);
        }
    }
}
