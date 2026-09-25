package ca.spottedleaf.starlight.common.light.image;

/**
 * The Lucis block-light BFS, ported from the 1.x engine ({@code dev.lucistarlink.light.engine.LuxBlockLightEngine})
 * onto the ported region data ({@link ImageRegionData}). This is the engine whose whole-cell readings made the 1.x
 * line the engine-metric leader; it is this project's own Lucis code, moved, not re-derived — the queue walk, the
 * removal rule, the refill rule and the coordinate decode are line for line the 1.x algorithm.
 *
 * <p><b>Two entries:</b> {@link #applyChanges(ImageRegionData, RuntimeLightChangeBuffer)} is the live lane's path —
 * the change buffer carries the material deltas captured at {@code setBlock} time, and applying them also advances
 * the region's own {@code opacity}/{@code emission} planes, which is what keeps those planes current between settles.
 * {@link #applyChanges(ImageRegionData, int[], int[], int[])} and {@link #compute} exist for the kernel fuzz in
 * {@link ImageLaneSelfTest} (incremental must equal the from-scratch oracle).</p>
 */
public final class ImageBlockLightEngine {

    private static final int REMOVAL_LEVEL_BITS = 4;
    private static final int REMOVAL_LEVEL_MASK = (1 << REMOVAL_LEVEL_BITS) - 1;

    private final IntBucketQueue addQueue = new IntBucketQueue(16, 4096);
    private final IntRingQueue removalQueue = new IntRingQueue(4096);

    /** Queue pops of the last apply, for pricing (one increment per pop; not part of the semantics). */
    public long lastPopCount;

    /** The live lane's entry: apply the captured material deltas, then run the removal and add walks. */
    public void applyChanges(final ImageRegionData data, final RuntimeLightChangeBuffer changes) {
        final IntBucketQueue queue = this.addQueue;
        final IntRingQueue removals = this.removalQueue;

        queue.clear();
        removals.clear();
        this.lastPopCount = 0L;

        final byte[] light = data.blockLight;
        final byte[] opacity = data.opacity;
        final byte[] emission = data.emission;

        for (int i = 0; i < changes.size(); i++) {
            final long change = changes.get(i);
            final int index = RuntimeLightChangeBuffer.localIndex(change);
            final int oldLight = light[index] & 0xF;
            final int newOpacity = RuntimeLightChangeBuffer.newOpacity(change);
            final int newEmission = RuntimeLightChangeBuffer.newEmission(change);

            // advance the region's material planes: the buffer's "new" values are the world's current truth
            opacity[index] = (byte) newOpacity;
            emission[index] = (byte) newEmission;
            data.markDirtyBlockIndex(index);

            // 1.x/base order: the cell's light becomes its new emission unconditionally
            light[index] = (byte) newEmission;

            // THE BASE SHAPE (StarLightEngine.checkBlock): the decrease entry is UNCONDITIONAL - "it also accounts
            // for a change in emitted light that would cause a decrease ... as it checks all neighbours (even if
            // current level is 0)". A level-0 sweep is not a removal: every lit neighbour passes the survivor test
            // and re-spreads into the changed cell, which is how an opacity drop opens a path. The 1.x-shaped
            // conditional wave plus a separate neighbourAdd seeding produced both missed removals and stale
            // pre-removal entries; the fuzz caught both.
            this.enqueueRemoval(removals, index, oldLight);

            if (newEmission > 1) {
                queue.enqueue(newEmission, index);
            }
        }

        this.processRemovals(data, queue, removals);
        this.processAdds(data, queue);
    }

    /** Fuzz entry: same walk, with the material deltas given as parallel arrays. */
    public void applyChanges(final ImageRegionData data, final int[] indices,
                             final int[] newOpacity, final int[] newEmission) {
        final RuntimeLightChangeBuffer buffer = new RuntimeLightChangeBuffer(indices.length);
        final int materialMask = ImageMaterial.MATERIAL_MASK;

        for (int i = 0; i < indices.length; i++) {
            final int index = indices[i];
            final int oldMaterial = (((data.opacity[index] & 0xF) | ((data.emission[index] & 0xF) << 4)));
            final int newMaterial = ((newOpacity[i] & 0xF) | ((newEmission[i] & 0xF) << 4)) & materialMask;
            buffer.addMaterial(index, oldMaterial, newMaterial);
        }

        this.applyChanges(data, buffer);
    }

    /**
     * The oracle: re-derive the interior from the region's emissions, with boundary cells kept at the light they
     * entered with and treated as fixed sources. The fuzz compares this against {@link #applyChanges}. All loops
     * walk real cells (padded coordinates 1..w), and the moat ring around them is never touched.
     */
    public void compute(final ImageRegionData data) {
        final IntBucketQueue queue = this.addQueue;

        queue.clear();
        this.lastPopCount = 0L;

        final byte[] light = data.blockLight;
        final byte[] emission = data.emission;
        final int w = data.paddedWidth;
        final int d = data.paddedDepth;
        final int h = data.paddedHeight;
        final int area = data.paddedArea;

        // real interior: padded coordinates 2..w-3 (the real faces sit at padded 1 and w-2)
        for (int y = 2; y < h - 2; y++) {
            for (int z = 2; z < d - 2; z++) {
                final int rowBase = y * area + z * w;
                for (int x = 2; x < w - 2; x++) {
                    final int index = rowBase + x;
                    final int em = emission[index] & 0xF;
                    light[index] = (byte) em;

                    if (em > 1) {
                        queue.enqueue(em, index);
                    }
                }
            }
        }

        // real boundary cells re-spread the light they entered with (the sky-window rule)
        final int xFace = w - 2, zFace = d - 2, yFace = h - 2;
        for (int y = 1; y <= yFace; y++) {
            for (int z = 1; z <= zFace; z++) {
                final int rowBase = y * area + z * w;
                for (int x = 1; x <= xFace; x++) {
                    if (x == 1 || x == xFace || z == 1 || z == zFace || y == 1 || y == yFace) {
                        final int index = rowBase + x;
                        final int lightLevel = light[index] & 0xF;
                        if (lightLevel > 1) {
                            queue.enqueue(lightLevel, index);
                        }
                    }
                }
            }
        }

        this.processAdds(data, queue);
    }

    private void processRemovals(final ImageRegionData data, final IntBucketQueue queue, final IntRingQueue removals) {
        int packed;

        while ((packed = removals.poll()) != Integer.MIN_VALUE) {
            final int index = packed >>> REMOVAL_LEVEL_BITS;
            final int removedLevel = packed & REMOVAL_LEVEL_MASK;

            // the moat needs no coordinates and no bounds checks: every out-of-region neighbour is a wall cell
            // with opacity 15 and light 0, so both the spread and the removal walks die there on their own
            this.tryRemoveNeighbor(data, queue, removals, removedLevel, index + data.offsetPosX);
            this.tryRemoveNeighbor(data, queue, removals, removedLevel, index + data.offsetNegX);
            this.tryRemoveNeighbor(data, queue, removals, removedLevel, index + data.offsetPosZ);
            this.tryRemoveNeighbor(data, queue, removals, removedLevel, index + data.offsetNegZ);
            this.tryRemoveNeighbor(data, queue, removals, removedLevel, index + data.offsetPosY);
            this.tryRemoveNeighbor(data, queue, removals, removedLevel, index + data.offsetNegY);

            // 1.x rule: a cell the walk zeroed re-seeds itself from its own emitter
            final int em = data.emission[index] & 0xF;
            if (em > 0) {
                data.blockLight[index] = (byte) em;
                if (em > 1) {
                    queue.enqueue(em, index);
                }
            }
        }
    }

    private void tryRemoveNeighbor(final ImageRegionData data, final IntBucketQueue queue, final IntRingQueue removals,
                                   final int removedLevel, final int nextIndex) {
        final byte[] light = data.blockLight;
        final int neighborLight = light[nextIndex] & 0xF;

        if (neighborLight == 0) {
            return; // also stops on the moat: wall cells hold light 0 and opacity 15
        }

        // The base engine's rule (StarLightEngine.performLightDecrease): a neighbour survives only when it is
        // BRIGHTER than this wave justifies. An equal level is zeroed too - otherwise a cell whose only source
        // died in this wave keeps its stale light and re-spreads it, which the fuzz caught as case 53.
        final int targetLevel = Math.max(0, removedLevel - ImageRegionData.propagationCost(data.opacity[nextIndex] & 0xF));

        if (neighborLight > targetLevel) {
            // survivor: independent-source territory. It becomes a refill source; processAdds re-reads the
            // cell's current level at pop, so a stale enqueue cannot re-raise a corrected cell.
            if (neighborLight > 1) {
                queue.enqueue(neighborLight, nextIndex);
            }
            return;
        }

        // the wave takes this cell: zero it, re-seed it if it is itself an emitter, and CONTINUE through it.
        // The base engine does all three (StarLightEngine.performLightDecrease): the emitter's own light is
        // enqueued for the add walk (which runs after the whole removal walk), and the removal wave proceeds at
        // targetLevel regardless - stopping here would leave the emitter's old downstream lit (the fuzz caught
        // that as the inverse mismatch).
        light[nextIndex] = 0;
        data.markDirtyBlockIndex(nextIndex);

        final int em = data.emission[nextIndex] & 0xF;
        if (em != 0) {
            light[nextIndex] = (byte) em;
            if (em > 1) {
                queue.enqueue(em, nextIndex);
            }
        }

        if (targetLevel > 0) {
            this.enqueueRemoval(removals, nextIndex, targetLevel);
        }
    }

    private void processAdds(final ImageRegionData data, final IntBucketQueue queue) {
        int index;

        while ((index = queue.poll()) >= 0) {
            this.lastPopCount++;
            final int current = data.blockLight[index] & 0xF;

            // recheck rule (the base engine's FLAG_RECHECK_LEVEL, applied to every entry): the cell must still hold
            // the level it was enqueued with. The change loop enqueues neighbours at their PRE-removal levels, and
            // the removal wave may since have lowered them - without this check the stale high entry pops first
            // (bucket order) and writes an inflated value that the correct lower spread cannot overwrite.
            if (current != queue.lastLevel()) {
                continue;
            }

            if (current <= 1) {
                continue;
            }

            // the moat: no coordinate decode, no bounds checks - the wall cells (opacity 15, light 0) stop every
            // walk that would leave the region, so the six strides are always safe to touch
            this.spreadTo(data, queue, current, index + data.offsetPosX);
            this.spreadTo(data, queue, current, index + data.offsetNegX);
            this.spreadTo(data, queue, current, index + data.offsetPosZ);
            this.spreadTo(data, queue, current, index + data.offsetNegZ);
            this.spreadTo(data, queue, current, index + data.offsetPosY);
            this.spreadTo(data, queue, current, index + data.offsetNegY);
        }
    }

    private void spreadTo(final ImageRegionData data, final IntBucketQueue queue, final int current, final int nextIndex) {
        final int candidate = current - ImageRegionData.propagationCost(data.opacity[nextIndex] & 0xF);

        if (candidate > (data.blockLight[nextIndex] & 0xF)) {
            data.blockLight[nextIndex] = (byte) candidate;
            data.markDirtyBlockIndex(nextIndex);
            if (candidate > 1) {
                queue.enqueue(candidate, nextIndex);
            }
        }
    }

    private void enqueueRemoval(final IntRingQueue removals, final int index, final int lightLevel) {
        if (lightLevel < 0) {
            return;
        }

        // level 0 is meaningful: the sweep re-spreads lit neighbours into the changed cell (survivor branch)
        removals.enqueue((index << REMOVAL_LEVEL_BITS) | lightLevel);
    }
}
