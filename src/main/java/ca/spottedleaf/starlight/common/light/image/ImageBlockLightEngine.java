package ca.spottedleaf.starlight.common.light.image;

/**
 * The Lucis block-light BFS, ported from the 1.x engine ({@code dev.lucistarlink.light.engine.LuxBlockLightEngine})
 * into the settle-local {@link ImageRegion}. This is the engine whose whole-cell readings made 1.x the engine-metric
 * leader; it is this project's own code, moved, not re-derived — the queue walk, the removal rule and the refill rule
 * are line-for-line the 1.x algorithm, and the coordinate decode is the 1.x one (two integer divisions per pop, no
 * more).
 *
 * <p><b>What changed against the 1.x original</b> (and why the semantics do not):</p>
 * <ul>
 *   <li>Changes arrive as parallel arrays ({@code indices}/{@code newOpacity}/{@code newEmission}) instead of 1.x's
 *       packed {@code RuntimeLightChangeBuffer} longs — the settle runs synchronously on one thread, so there is no
 *       buffer to pack for, and the old values are read from the region itself (they are, by definition, what is in
 *       there before the change is applied).</li>
 *   <li>The 1.x queue objects are thread-local; here the engine owns one instance per lane (same thread per settle).</li>
 *   <li>{@link #compute} is the test oracle, not a 1.x method: it re-derives the interior from emissions while the
 *       boundary cells <i>keep the light they entered with</i> and act as fixed sources — the same rule the sky window
 *       settle runs under.</li>
 * </ul>
 *
 * <p><b>The correctness property the self-test checks</b> (and the property the real lane depends on): applying a set
 * of changes incrementally must leave the interior <i>exactly</i> what {@link #compute} produces on the changed
 * material, and must never touch the boundary cells. Incremental == oracle, cell for cell, on random worlds — if that
 * ever fails, the lane is wrong no matter what the timing says.</p>
 */
public final class ImageBlockLightEngine {

    private static final int REMOVAL_LEVEL_BITS = 4;
    private static final int REMOVAL_LEVEL_MASK = (1 << REMOVAL_LEVEL_BITS) - 1;

    private final IntBucketQueue addQueue = new IntBucketQueue(16, 4096);
    private final IntRingQueue removalQueue = new IntRingQueue(4096);

    /** Queue pops of the last apply, for the pricing run (one increment per pop; not part of the semantics). */
    public long lastPopCount;

    /** Applies one burst of changes incrementally. {@code indices} must be unique and interior (see the lane contract). */
    public void applyChanges(final ImageRegion region, final int[] indices,
                             final int[] newOpacity, final int[] newEmission) {
        final IntBucketQueue queue = this.addQueue;
        final IntRingQueue removals = this.removalQueue;

        queue.clear();
        removals.clear();
        this.lastPopCount = 0L;

        final byte[] light = region.light;
        final byte[] opacity = region.opacity;
        final byte[] emission = region.emission;

        for (int i = 0; i < indices.length; i++) {
            final int index = indices[i];
            final int oldLight = light[index] & 0xF;
            final int oldOpacity = opacity[index] & 0xF;
            final int oldEmission = emission[index] & 0xF;
            final int newOp = newOpacity[i];
            final int newEm = newEmission[i];

            opacity[index] = (byte) newOp;
            emission[index] = (byte) newEm;
            region.markTouched(index);

            // 1.x order: the cell's light becomes its new emission unconditionally, then the loss rule decides
            // whether a removal walk is needed (emission dropped, or the cell became harder to pass through)
            light[index] = (byte) newEm;
            region.markDirty(index);

            if (oldLight > 0 && (newEm < oldEmission || newOp > oldOpacity)) {
                this.enqueueRemoval(removals, index, oldLight);
            }

            if (newEm > 1) {
                queue.enqueue(newEm, index);
            }

            this.enqueueNeighborAdds(region, queue, index);
        }

        this.processRemovals(region, queue, removals);
        this.processAdds(region, queue);
    }

    /**
     * The oracle: re-derive the interior from the region's emissions, with the boundary cells kept at the light they
     * entered with and treated as fixed sources. The self-test compares this against {@link #applyChanges}.
     */
    public void compute(final ImageRegion region) {
        final IntBucketQueue queue = this.addQueue;

        queue.clear();
        this.lastPopCount = 0L;

        final byte[] light = region.light;
        final byte[] emission = region.emission;
        final int w = region.width;
        final int d = region.depth;
        final int h = region.height;

        for (int y = 0; y < h; y++) {
            final boolean yEdge = y == 0 || y == h - 1;
            for (int z = 0; z < d; z++) {
                final boolean zEdge = z == 0 || z == d - 1;
                final int rowBase = (y * d + z) * w;
                for (int x = 0; x < w; x++) {
                    if (x == 0 || x == w - 1 || zEdge || yEdge) {
                        continue; // boundary: keep the entered light, it is a fixed source
                    }
                    final int index = rowBase + x;
                    final int em = emission[index] & 0xF;
                    light[index] = (byte) em;

                    if (em > 1) {
                        queue.enqueue(em, index);
                    }
                }
            }
        }

        // boundary cells re-spread the light they entered with (the sky-window rule)
        for (int index = 0; index < region.volume; index++) {
            final int x = index % w;
            final int z = (index / w) % d;
            final int y = index / region.area;
            if (x == 0 || x == w - 1 || z == 0 || z == d - 1 || y == 0 || y == h - 1) {
                final int lightLevel = light[index] & 0xF;
                if (lightLevel > 1) {
                    queue.enqueue(lightLevel, index);
                }
            }
        }

        this.processAdds(region, queue);
    }

    private void processRemovals(final ImageRegion region, final IntBucketQueue queue, final IntRingQueue removals) {
        int packed;

        while ((packed = removals.poll()) != Integer.MIN_VALUE) {
            final int index = packed >>> REMOVAL_LEVEL_BITS;
            final int removedLevel = packed & REMOVAL_LEVEL_MASK;
            final int y = index / region.area;
            final int rem = index - y * region.area;
            final int z = rem / region.width;
            final int x = rem - z * region.width;

            this.tryRemoveNeighbor(region, queue, removals, removedLevel, x + 1, y, z, index + region.offsetPosX);
            this.tryRemoveNeighbor(region, queue, removals, removedLevel, x - 1, y, z, index + region.offsetNegX);
            this.tryRemoveNeighbor(region, queue, removals, removedLevel, x, y, z + 1, index + region.offsetPosZ);
            this.tryRemoveNeighbor(region, queue, removals, removedLevel, x, y, z - 1, index + region.offsetNegZ);
            this.tryRemoveNeighbor(region, queue, removals, removedLevel, x, y + 1, z, index + region.offsetPosY);
            this.tryRemoveNeighbor(region, queue, removals, removedLevel, x, y - 1, z, index + region.offsetNegY);

            // 1.x rule: a cell the walk zeroed re-seeds itself from its own emitter
            final int em = region.emission[index] & 0xF;
            if (em > 0) {
                region.light[index] = (byte) em;
                if (em > 1) {
                    queue.enqueue(em, index);
                }
            }
        }
    }

    private void tryRemoveNeighbor(final ImageRegion region, final IntBucketQueue queue, final IntRingQueue removals,
                                   final int removedLevel, final int nextX, final int nextY, final int nextZ, final int nextIndex) {
        if (nextX < 0 || nextX >= region.width || nextZ < 0 || nextZ >= region.depth
                || nextY < 0 || nextY >= region.height) {
            return;
        }

        final byte[] light = region.light;
        final int neighborLight = light[nextIndex] & 0xF;

        if (neighborLight == 0) {
            return;
        }

        if (neighborLight < removedLevel) {
            light[nextIndex] = 0;
            region.markDirty(nextIndex);
            this.enqueueRemoval(removals, nextIndex, neighborLight);
        } else if (neighborLight > 1) {
            // the neighbour survives at its own level: it becomes a refill source (1.x rule)
            queue.enqueue(neighborLight, nextIndex);
        }
    }

    private void processAdds(final ImageRegion region, final IntBucketQueue queue) {
        int index;

        while ((index = queue.poll()) >= 0) {
            this.lastPopCount++;
            final int current = region.light[index] & 0xF;

            if (current <= 1) {
                continue;
            }

            final int y = index / region.area;
            final int rem = index - y * region.area;
            final int z = rem / region.width;
            final int x = rem - z * region.width;

            this.spreadTo(region, queue, current, x + 1, y, z, index + region.offsetPosX);
            this.spreadTo(region, queue, current, x - 1, y, z, index + region.offsetNegX);
            this.spreadTo(region, queue, current, x, y, z + 1, index + region.offsetPosZ);
            this.spreadTo(region, queue, current, x, y, z - 1, index + region.offsetNegZ);
            this.spreadTo(region, queue, current, x, y + 1, z, index + region.offsetPosY);
            this.spreadTo(region, queue, current, x, y - 1, z, index + region.offsetNegY);
        }
    }

    private void spreadTo(final ImageRegion region, final IntBucketQueue queue, final int current,
                          final int nextX, final int nextY, final int nextZ, final int nextIndex) {
        if (nextX < 0 || nextX >= region.width || nextZ < 0 || nextZ >= region.depth
                || nextY < 0 || nextY >= region.height) {
            return;
        }

        final int candidate = current - ImageRegion.propagationCost(region.opacity[nextIndex] & 0xF);

        if (candidate > (region.light[nextIndex] & 0xF)) {
            region.light[nextIndex] = (byte) candidate;
            region.markDirty(nextIndex);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }

    private void enqueueNeighborAdds(final ImageRegion region, final IntBucketQueue queue, final int index) {
        final int y = index / region.area;
        final int rem = index - y * region.area;
        final int z = rem / region.width;
        final int x = rem - z * region.width;

        this.enqueueIfLit(region, queue, x + 1, y, z, index + region.offsetPosX);
        this.enqueueIfLit(region, queue, x - 1, y, z, index + region.offsetNegX);
        this.enqueueIfLit(region, queue, x, y, z + 1, index + region.offsetPosZ);
        this.enqueueIfLit(region, queue, x, y, z - 1, index + region.offsetNegZ);
        this.enqueueIfLit(region, queue, x, y + 1, z, index + region.offsetPosY);
        this.enqueueIfLit(region, queue, x, y - 1, z, index + region.offsetNegY);
    }

    private void enqueueIfLit(final ImageRegion region, final IntBucketQueue queue,
                              final int nextX, final int nextY, final int nextZ, final int nextIndex) {
        if (nextX < 0 || nextX >= region.width || nextZ < 0 || nextZ >= region.depth
                || nextY < 0 || nextY >= region.height) {
            return;
        }

        final int light = region.light[nextIndex] & 0xF;

        if (light > 1) {
            queue.enqueue(light, nextIndex);
        }
    }

    private void enqueueRemoval(final IntRingQueue removals, final int index, final int lightLevel) {
        if (lightLevel <= 0) {
            return;
        }

        removals.enqueue((index << REMOVAL_LEVEL_BITS) | lightLevel);
    }
}
