package dev.lucistarlink.light.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OwnedRegionCache {
    private final ConcurrentHashMap<Long, RuntimeRegionState> cache = new ConcurrentHashMap<>();

    public RuntimeRegionState getOrCreate(RegionBounds bounds) {
        RuntimeRegionState state = cache.compute(bounds.coreRegionKey(), (key, existing) -> {
            if (existing == null) {
                return new RuntimeRegionState(new RegionLightData(bounds));
            }
            if (!sameShape(existing.data().bounds, bounds)) {
                return new RuntimeRegionState(new RegionLightData(bounds));
            }
            existing.touch();
            return existing;
        });
        state.touch();
        return state;
    }

    public RuntimeRegionState getInitialized(long regionKey) {
        RuntimeRegionState state = cache.get(regionKey);
        if (state != null) {
            state.touch();
        }
        return state != null && state.initialized() ? state : null;
    }

    /**
     * Evicts the least recently touched regions until the cache is inside both budgets. A region holds
     * four byte planes of {@code widthBlocks * depthBlocks * heightBlocks} bytes each, so the entry
     * count alone does not bound the heap: 128 one-chunk regions are ~48 MiB, while the same 128 entries
     * with a 3x3 halo or {@code regionChunks=16} are several hundred MiB. At least one region is always
     * kept, so a single region larger than the byte budget cannot evict itself endlessly. Either budget
     * can be disabled by passing a non-positive value.
     */
    public void trimToSize(int maxEntries, long maxBytes) {
        boolean limitEntries = maxEntries > 0;
        boolean limitBytes = maxBytes > 0L;
        if ((!limitEntries && !limitBytes) || cache.isEmpty()) {
            return;
        }

        int size = cache.size();
        long bytes = limitBytes ? cachedBytes() : 0L;
        if ((!limitEntries || size <= maxEntries) && (!limitBytes || bytes <= maxBytes)) {
            return;
        }

        ArrayList<TrimCandidate> oldestFirst = new ArrayList<>(size);
        for (Map.Entry<Long, RuntimeRegionState> entry : cache.entrySet()) {
            RuntimeRegionState state = entry.getValue();
            oldestFirst.add(new TrimCandidate(entry.getKey(), state, state.lastAccessNanos()));
        }
        oldestFirst.sort(Comparator.comparingLong(TrimCandidate::lastAccessNanos));

        for (TrimCandidate candidate : oldestFirst) {
            if (size <= 1) {
                break;
            }
            if ((!limitEntries || size <= maxEntries) && (!limitBytes || bytes <= maxBytes)) {
                break;
            }
            if (cache.remove(candidate.regionKey(), candidate.state())) {
                size--;
                bytes -= candidate.state().data().byteSize();
            }
        }
    }

    public long cachedBytes() {
        long bytes = 0L;
        for (RuntimeRegionState state : cache.values()) {
            bytes += state.data().byteSize();
        }
        return bytes;
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private boolean sameShape(RegionBounds left, RegionBounds right) {
        return left.widthBlocks() == right.widthBlocks()
                && left.depthBlocks() == right.depthBlocks()
                && left.minBuildY() == right.minBuildY()
                && left.maxBuildY() == right.maxBuildY()
                && left.regionChunks() == right.regionChunks()
                && left.haloChunks() == right.haloChunks();
    }

    private record TrimCandidate(long regionKey, RuntimeRegionState state, long lastAccessNanos) {
    }
}
