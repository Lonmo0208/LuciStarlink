package dev.lucistarlink.light.region;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeRegionState {
    private final RegionLightData data;
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
    private final Set<ExternalSection> externalSections = ConcurrentHashMap.newKeySet();
    /**
     * Bumped on every external mark. A job reads it after refreshing and compares afterwards: if it moved, the
     * engine changed underneath the job while it computed, so publishing its result could overwrite fresher
     * light with values derived from a stale baseline. Such a job re-runs instead of publishing.
     */
    private final java.util.concurrent.atomic.AtomicLong externalEpoch = new java.util.concurrent.atomic.AtomicLong();

    public RuntimeRegionState(RegionLightData data) {
        this.data = data;
        touch();
    }

    public RegionLightData data() {
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
        externalSections.add(new ExternalSection(packedSectionPos, sky));
        externalEpoch.incrementAndGet();
    }

    public long externalEpoch() {
        return externalEpoch.get();
    }

    public List<ExternalSection> drainExternalSections() {
        if (externalSections.isEmpty()) {
            return List.of();
        }
        List<ExternalSection> drained = new ArrayList<>(externalSections);
        externalSections.removeAll(drained);
        return drained;
    }

    public int externalSectionCount() {
        return externalSections.size();
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

