package ca.spottedleaf.starlight.common.light.image;

/**
 * The sky half of the region image, computed in place.
 *
 * <p>This is slice 1 of the image-as-storage route (docs/HANDOVER.md 10.34): the four steps of the proven windowed
 * recompute ({@code SkyStarLightEngine.settleSkyWindow} - expand, run sweep, BFS, install) applied to a whole region
 * at once, on the region's own flat planes, with no nibble step and no packing. The region is LARGER than the window
 * was, so the boundary problem is smaller: the only light entering from outside is through the moat, whose opacity 15
 * and light 0 make every walk stop at the edge - the same treatment the block half has always had.</p>
 *
 * <p>{@link #compute} is the oracle form for now: it re-derives the ENTIRE region's sky light from the region's
 * material planes and the world's heightmaps, ignoring what the image already holds (except at the moat). Correctness
 * is judged by {@code SkyStarLightEngine}'s own result for the same chunk, compared per cell by the caller - the same
 * arrangement the block half's fuzz uses. Wiring it into the incremental path (slice 2) and the pack (slice 3) comes
 * after this passes.</p>
 */
public final class ImageSkyLightEngine {

    private int[] queue;
    private int[] runs; // (pw+1) x (pd+1): the region's columns plus a one-cell halo, indexed (z+1)*(pw+1)+(x+1)

    /** Re-derives every sky cell of the region from its material planes. Not wired into any path yet. */
    public void compute(final ImageRegionData data) {
        final int pw = data.paddedWidth;
        final int pd = data.paddedDepth;
        final int ph = data.paddedHeight;
        final int area = data.paddedArea;
        final int cells = data.paddedVolume;
        final byte[] material = data.opacity;
        final byte[] light = data.skyLight;
        final int runStride = pw + 1;

        if (this.queue == null || this.queue.length < cells * 2) {
            this.queue = new int[cells * 2];
        }
        if (this.runs == null || this.runs.length < runStride * (pd + 1)) {
            this.runs = new int[runStride * (pd + 1)];
        }
        final int[] queue = this.queue;
        final int[] runs = this.runs;
        java.util.Arrays.fill(light, 0, cells, (byte) 0);
        java.util.Arrays.fill(runs, Integer.MIN_VALUE);

        // ---- 1) open-column runs, over MATERIALIZED columns only. A region materializes just the sections its
        // changes can reach; outside them the material planes are zero because they were never EXTRACTED, not because
        // the world is air - the first oracle run read exactly that (x=32 was unmaterialized halo, computed 15 against
        // an adopted 0). A column is in scope when at least one of its sections is materialized; out-of-scope columns
        // keep the light the adoption brought and take no part in the sweep or the BFS.
        int tail = 0;

        for (int z = 1; z <= pd - 2; z++) {
            for (int x = 1; x <= pw - 2; x++) {
                if (!data.isColumnMaterialMaterialized(x - 1, z - 1)) {
                    continue;
                }
                final int colBase = z * pw + x;
                int y = ph - 2; // top real cell (ph-1 is the moat slab)

                while (y >= 1) {
                    final int i = y * area + colBase;

                    if (material[i] != 0) {
                        break;
                    }
                    light[i] = 15;
                    y--;
                }
                final int runBottom = y + 1;

                if (runBottom <= ph - 2) {
                    runs[z * runStride + x] = runBottom;
                    // seed the whole run, exactly as settleSkyWindow does since 2.0.9: the BFS has no upward
                    // direction, so a pocket needs a seed at its own height, and a region sized like a tile has
                    // at most ~256 runs a plane
                    for (int sy = runBottom; sy <= ph - 2; sy++) {
                        if ((light[sy * area + colBase] & 0xF) == 15) {
                            queue[tail++] = sy * area + colBase;
                        }
                    }
                }
            }
        }

        // ---- 2) BFS with the moat as the boundary: six strides, no coordinate decode, no bounds checks. The moat's
        // opacity 15 stops every walk (a step into it costs 15 levels), so a spread can never leave the region and
        // no neighbour light is needed - identical to the block half's handling of the same edge.
        int head = 0;

        while (head < tail) {
            final int index = queue[head++];
            this.lastPops++;
            final int level = light[index] & 0xF;

            if (level <= 1) {
                continue;
            }
            final int yOff = index / area;
            final int rem = index - yOff * area;

            for (int dir = 0; dir < 6; dir++) {
                final int nIndex;
                final int nYOff;

                switch (dir) {
                    case 0: nIndex = rem - 1; nYOff = yOff; break;
                    case 1: nIndex = rem + 1; nYOff = yOff; break;
                    case 2: nIndex = rem - pw; nYOff = yOff; break;
                    case 3: nIndex = rem + pw; nYOff = yOff; break;
                    case 4: nIndex = index - area; nYOff = yOff - 1; break;
                    default: nIndex = index + area; nYOff = yOff + 1; break;
                }
                if (nYOff < 0 || nYOff > ph - 1) {
                    continue; // the vertical moat slabs
                }
                final int opacity = material[nIndex] & 0xF;

                if (opacity >= 15) {
                    continue; // the moat ring, or an opaque cell
                }
                final int target = level - Math.max(1, opacity);

                if (target > (light[nIndex] & 0xF)) {
                    light[nIndex] = (byte) target;
                    if (target > 1) {
                        queue[tail++] = nIndex;
                    }
                }
            }
        }
    }

    private long lastPops;

    /**
     * Slice 1.5: recompute the sky light of the whole region over a y window, on the region's own planes - the shape
     * of {@code SkyStarLightEngine.settleSkyWindow}, lifted to region granularity (all the region's core columns at
     * once, spanning chunks), with no nibble step and no packing. The window and the per-column highest-change data
     * come from the settle's own pending-recompute bookkeeping, and the columns' material planes are in place because
     * the materialization that preceded the settle covered every section the change can reach.
     *
     * <p>Boundary handling mirrors the single-chunk version: the cells one above and one below the window keep their
     * current values and act as sources (a path from any change to them crosses >= 16 levels, which a field of at
     * most 15 cannot traverse); horizontally the whole core is inside the window, and the halo columns' materials were
     * materialized by the settle, so a walk into them sees real opacity rather than the moat's 15.</p>
     */
    public void computeWindow(final ImageRegionData data, final int yLoWorld, final int yHiWorld) {
        final int pw = data.paddedWidth;
        final int pd = data.paddedDepth;
        final int ph = data.paddedHeight;
        final int area = data.paddedArea;
        final int minBuildY = data.bounds.minBuildY();
        final int yLo = Math.max(1, yLoWorld - minBuildY + 1);
        final int yHi = Math.min(ph - 2, yHiWorld - minBuildY + 1);

        if (yLo > yHi) {
            return;
        }
        final int height = yHi - yLo + 1;
        final int cells = (pw - 2) * (pd - 2) * height;

        this.heightScratch = height;
        this.yLoWorldScratch = yLoWorld;
        final byte[] material = data.opacity;
        final byte[] light = data.skyLight;

        if (this.queue == null || this.queue.length < cells * 2) {
            this.queue = new int[cells * 2];
        }
        final int[] queue = this.queue;
        final int[] before = this.beforeScratch != null && this.beforeScratch.length >= cells
                ? this.beforeScratch : (this.beforeScratch = new int[cells]);
        final int[] runs = this.runs != null && this.runs.length >= (pw + 1) * (pd + 1)
                ? this.runs : (this.runs = new int[(pw + 1) * (pd + 1)]);
        java.util.Arrays.fill(runs, Integer.MIN_VALUE);
        this.lastPops = 0L;

        // ---- 1) snapshot the window's current light into `before` (the caller diffs against it), and clear the
        // 15-run below each changed column's highest change - the column-scoped form of the 2.0.9 fix, identical to
        // what settleSkyWindow does now.
        for (int z = 1; z <= pd - 2; z++) {
            for (int x = 1; x <= pw - 2; x++) {
                final int colBase = z * pw + x;
                final int outBase = ((z - 1) * (pw - 2) + (x - 1)) * height;

                for (int y = yLo; y <= yHi; y++) {
                    before[outBase + (y - yLo)] = light[y * area + colBase] & 0xF;
                }
                final int perColumnMaxY = this.columnMaxYFor(x - 1, z - 1);

                if (perColumnMaxY == Integer.MIN_VALUE) {
                    continue;
                }
                final int topY = Math.min(yHi, perColumnMaxY - minBuildY + 1);

                for (int y = topY; y >= yLo; y--) {
                    final int i = y * area + colBase;

                    if ((light[i] & 0xF) != 15) {
                        break;
                    }
                    light[i] = 0;
                }
            }
        }

        // ---- 2) sweep the open 15-runs inside the window, over the CORE columns only (x/z in 1..16 for a 1-chunk
        // tile; the halo columns keep their adopted values and act as boundary sources - the same semantics as the
        // single-chunk version, whose window never crosses the chunk edge). The oracle runs proved why: a halo
        // column's 15-run is decided by blockers OUTSIDE the window (and outside the materialization reach), so
        // sweeping it from the window top poured 15 through cells the nibbles correctly hold dark (138/82944 cells).
        int tail = 0;

        for (int z = 1; z <= pd - 2; z++) {
            for (int x = 1; x <= pw - 2; x++) {
                if (x > 16 || z > 16) {
                    continue; // core columns of a 1-chunk tile (REGION_CHUNKS=1); wider tiles are slice 3's work
                }
                final int colBase = z * pw + x;
                final int iTop = (yHi + 1) * area + colBase;

                if ((light[iTop] & 0xF) != 15) {
                    continue; // no run inside the window for this column
                }
                int y = yHi;

                while (y >= yLo) {
                    final int i = y * area + colBase;

                    if (material[i] != 0) {
                        break;
                    }
                    final int outBase = ((z - 1) * (pw - 2) + (x - 1)) * height;

                    if (before[outBase + (y - yLo)] != 15) {
                        break; // the adopted field says the run ends here: trust it, the BFS refills below
                    }
                    light[i] = 15;
                    y--;
                }
                final int runBottom = y + 1;
                final int runSlot = z * (pw + 1) + x;

                runs[runSlot] = runBottom;
                // seed the whole lit run: the BFS has no upward direction (docs/HANDOVER.md 10.21)
                for (int sy = runBottom; sy <= yHi; sy++) {
                    if ((light[sy * area + colBase] & 0xF) == 15) {
                        queue[tail++] = sy * area + colBase;
                    }
                }
            }
        }

        // ---- 3) the window's bottom slab seeds at its adopted level: it is the window's lower boundary and the only
        // light that can enter from below (the top slab's light has already been swept into the runs). Core columns
        // only, for the same reason as the sweep: a halo cell's value is decided by world outside this window.
        for (int z = 1; z <= 16; z++) {
            for (int x = 1; x <= 16; x++) {
                final int colBase = z * pw + x;
                final int bottom = yLo * area + colBase;

                if ((light[bottom] & 0xF) > 1) {
                    queue[tail++] = bottom;
                }
            }
        }
        int head = 0;

        while (head < tail) {
            final int index = queue[head++];
            this.lastPops++;
            final int level = light[index] & 0xF;

            if (level <= 1) {
                continue;
            }
            final int yOff = index / area;
            final int rem = index - yOff * area;

            for (int dir = 0; dir < 6; dir++) {
                final int nIndex;
                final int nYOff;

                switch (dir) {
                    case 0: nIndex = rem - 1; nYOff = yOff; break;
                    case 1: nIndex = rem + 1; nYOff = yOff; break;
                    case 2: nIndex = rem - pw; nYOff = yOff; break;
                    case 3: nIndex = rem + pw; nYOff = yOff; break;
                    case 4: nIndex = index - area; nYOff = yOff - 1; break;
                    default: nIndex = index + area; nYOff = yOff + 1; break;
                }
                if (nYOff < yLo || nYOff > yHi) {
                    continue; // the BFS stays inside the window; the slabs act as sources, not as walkable cells
                }
                final int opacity = material[nIndex] & 0xF;

                if (opacity >= 15) {
                    continue;
                }
                final int target = level - Math.max(1, opacity);

                if (target > (light[nIndex] & 0xF)) {
                    light[nIndex] = (byte) target;
                    if (target > 1) {
                        queue[tail++] = nIndex;
                    }
                }
            }
        }
    }

    public int[] beforeScratch;
    private int heightScratch = -1;
    private int yLoWorldScratch;

    /** The window the last computeWindow call used, for the caller's compare/install pass. */
    public int lastWindowHeight() {
        return this.heightScratch;
    }

    public int lastWindowYLoWorld() {
        return this.yLoWorldScratch;
    }

    /**
     * The per-column highest change, given a region-local column. The pending recompute's array is chunk-local, and
     * with a region of one chunk plus a one-chunk halo the changed chunk occupies region-local 16..31 in both axes -
     * NOT 0..15. The first cut assumed 0..15 and cleared the 15-run below the highest change of the chunk to the WEST
     * and NORTH instead of the changed one (docs/HANDOVER.md 10.37): the changed chunk's own shadow was never cleared,
     * so this routine could not lower a single cell - the slice-1.5 oracle compared its (unchanged) result against the
     * plane it had just read and called the two routes identical.
     */
    private int columnMaxYFor(final int regionLocalX, final int regionLocalZ) {
        final int chunkLocalX = regionLocalX - this.changedChunkLocalX;
        final int chunkLocalZ = regionLocalZ - this.changedChunkLocalZ;

        if (this.pendingColumnMaxY == null || (chunkLocalX | chunkLocalZ) < 0
                || chunkLocalX >= 16 || chunkLocalZ >= 16) {
            return Integer.MIN_VALUE;
        }
        return this.pendingColumnMaxY[2 + ((chunkLocalZ << 4) | chunkLocalX)];
    }

    /** Set by the caller (the lane) before computeWindow: the pending recompute's {minY, maxY, per-column maxY}, plus
     *  the region-local position of the chunk that recompute belongs to (the array's columns are chunk-local). */
    public void setPendingColumns(final int[] columnMaxY, final int changedChunkLocalX, final int changedChunkLocalZ) {
        this.pendingColumnMaxY = columnMaxY;
        this.changedChunkLocalX = changedChunkLocalX;
        this.changedChunkLocalZ = changedChunkLocalZ;
    }
    private int[] pendingColumnMaxY;
    private int changedChunkLocalX;
    private int changedChunkLocalZ;

    public long lastPopCount() {
        return this.lastPops;
    }

    public String stats() {
        return "skyPops=" + this.lastPops;
    }
}
