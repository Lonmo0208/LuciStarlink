package ca.spottedleaf.starlight.common.light.image;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OwnedRegionImageCache {
    private final ConcurrentHashMap<Long, RuntimeRegionImageState> cache = new ConcurrentHashMap<>();

    public RuntimeRegionImageState getOrCreate(ImageRegionBounds bounds) {
        RuntimeRegionImageState state = cache.compute(bounds.coreRegionKey(), (key, existing) -> {
            if (existing == null) {
                return new RuntimeRegionImageState(new ImageRegionData(bounds));
            }
            if (!sameShape(existing.data().bounds, bounds)) {
                return new RuntimeRegionImageState(new ImageRegionData(bounds));
            }
            existing.touch();
            return existing;
        });
        state.touch();
        return state;
    }

    public RuntimeRegionImageState getInitialized(long regionKey) {
        RuntimeRegionImageState state = cache.get(regionKey);
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

        ArrayList<RegionTrimCandidate> oldestFirst = new ArrayList<>(size);
        for (Map.Entry<Long, RuntimeRegionImageState> entry : cache.entrySet()) {
            RuntimeRegionImageState state = entry.getValue();
            oldestFirst.add(new RegionTrimCandidate(entry.getKey(), state, state.lastAccessNanos()));
        }
        oldestFirst.sort(Comparator.comparingLong(RegionTrimCandidate::lastAccessNanos));

        for (RegionTrimCandidate candidate : oldestFirst) {
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
        for (RuntimeRegionImageState state : cache.values()) {
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

    private boolean sameShape(ImageRegionBounds left, ImageRegionBounds right) {
        return left.widthBlocks() == right.widthBlocks()
                && left.depthBlocks() == right.depthBlocks()
                && left.minBuildY() == right.minBuildY()
                && left.maxBuildY() == right.maxBuildY()
                && left.regionChunks() == right.regionChunks()
                && left.haloChunks() == right.haloChunks();
    }

    private record RegionTrimCandidate(long regionKey, RuntimeRegionImageState state, long lastAccessNanos) {
    }
}
