package ca.spottedleaf.starlight.common.light.image;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeRegionImageState {
    private final ImageRegionData data;
    private volatile boolean initialized;
    /**
     * The chunk this region was initialized against, held weakly: a region outlives the
     * chunk load that created it, so a strong reference here would keep unloaded chunks
     * (sections, palettes, block entities) alive until the region cache evicted it.
     * A collected referent simply stops matching, which re-initializes the region.
     */
    private volatile WeakReference<Object> sourceIdentity;
    private volatile long lastAccessNanos;

    /**
     * Sections of this region's chunks that a *neighbouring* region's job pushed into the vanilla engine
     * (its halo results). This image still holds the pre-publish values for them, so they are re-read from
     * the engine before the next job uses the image as a baseline. Without that, stale cells could be
     * re-raised across the border - the failure mode the old cross-region delta prototype hit.
     */
    private final java.util.concurrent.atomic.AtomicReference<Set<ExternalSection>> externalSections =
            new java.util.concurrent.atomic.AtomicReference<>(ConcurrentHashMap.newKeySet());
    /**
     * Bumped on every external mark. A job reads it after refreshing and compares afterwards: if it moved, the
     * engine changed underneath the job while it computed, so publishing its result could overwrite fresher
     * light with values derived from a stale baseline. Such a job re-runs instead of publishing.
     */
    private final java.util.concurrent.atomic.AtomicLong externalEpoch = new java.util.concurrent.atomic.AtomicLong();

    public RuntimeRegionImageState(ImageRegionData data) {
        this.data = data;
        touch();
    }

    public ImageRegionData data() {
        return data;
    }

    public boolean initialized() {
        return initialized;
    }

    public boolean initializedFor(Object sourceIdentity) {
        WeakReference<Object> reference = this.sourceIdentity;
        return initialized && reference != null && reference.get() == sourceIdentity;
    }

    public void markInitialized(Object sourceIdentity) {
        this.sourceIdentity = new WeakReference<>(sourceIdentity);
        initialized = true;
        touch();
    }

    public void markExternalSection(long packedSectionPos, boolean sky) {
        externalSections.get().add(new ExternalSection(packedSectionPos, sky));
        externalEpoch.incrementAndGet();
    }

    public long externalEpoch() {
        return externalEpoch.get();
    }

    /**
     * 取走当前所有外部标记。
     *
     * <p>整组替换而不是「复制 + removeAll」：那种写法下，同一个 section 在复制与删除之间被再次标记时，
     * `add` 会因集合里已有该元素而返回 false，紧接着 `removeAll` 把它一并删掉 —— 那一次变化就永久没人
     * 刷新了，而这正是这套机制要防的跨区丢光。替换之后到达的标记进新集合，交给下一次。
     */
    public List<ExternalSection> drainExternalSections() {
        while (true) {
            Set<ExternalSection> pending = externalSections.get();
            if (pending.isEmpty()) {
                return List.of();
            }
            if (externalSections.compareAndSet(pending, ConcurrentHashMap.newKeySet())) {
                return new ArrayList<>(pending);
            }
        }
    }

    public int externalSectionCount() {
        return externalSections.get().size();
    }

    public void touch() {
        lastAccessNanos = System.nanoTime();
    }

    public long lastAccessNanos() {
        return lastAccessNanos;
    }

    /** One section published by a neighbour, identified by {@code SectionPos.asLong()} plus light layer. */
    public record ExternalSection(long packedSectionPos, boolean sky) {
    }
}

