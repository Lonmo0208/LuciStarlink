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

    public long lastPopCount() {
        return this.lastPops;
    }

    public String stats() {
        return "skyPops=" + this.lastPops;
    }
}
