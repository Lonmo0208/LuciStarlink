package ca.spottedleaf.starlight.common.light.image;

/**
 * The settle-local flat workspace for the image lane: one bounded box of the world as three byte arrays
 * ({@code light}, {@code opacity}, {@code emission}), one cell per byte, in y-major order
 * ({@code index = (y * depth + z) * width + x}) so the six neighbour steps are constant strides.
 *
 * <p><b>Settle-local, not resident.</b> Unlike 1.x's region image (built at region load and kept for the region's
 * lifetime), this workspace exists for the duration of one synchronous settle and is discarded. That is what keeps the
 * closed routes closed rather than re-opened: there is no persistent copy of vanilla truth to go stale (the material
 * table's defect, TEARDOWN §17/§18), no second working set living next to the nibbles during normal play (the flat
 * mirror's defect, §16) — during the BFS the flat arrays <i>are</i> the only thing touched; the nibbles are read once
 * on the way in (expand) and written once on the way out (pack).</p>
 *
 * <p><b>The boundary rule</b> (the same one the sky window settle runs under, which passes the fingerprint gate): the
 * caller must expand the box at least 16 blocks past every changed cell — light cannot travel further — and the cells
 * on the box floor/ceiling/walls are seeded with the light already there and act as sources. Nothing inside can move
 * light across the boundary, so the answer is exact, not an approximation.</p>
 */
public final class ImageRegion {

    public final int width;   // x extent
    public final int depth;   // z extent
    public final int height;  // y extent
    public final int area;    // width * depth
    public final int volume;  // width * depth * height

    public final byte[] light;
    public final byte[] opacity;
    public final byte[] emission;

    public final int offsetPosX;
    public final int offsetNegX;
    public final int offsetPosZ;
    public final int offsetNegZ;
    public final int offsetPosY;
    public final int offsetNegY;

    /** Cell-level dirty bits, {@code volume/64} longs: the pack step derives which sections to write back. */
    public final long[] dirty;
    /** Cells whose opacity/emission the caller changed (the pack step needs those even when light did not move). */
    public final long[] touched;

    public ImageRegion(final int width, final int depth, final int height) {
        final long cells = (long) width * depth * height;

        if (width <= 0 || depth <= 0 || height <= 0 || cells > (1L << 28)) {
            throw new IllegalArgumentException("region " + width + "x" + depth + "x" + height + " (cells " + cells + ")");
        }

        this.width = width;
        this.depth = depth;
        this.height = height;
        this.area = width * depth;
        this.volume = (int) cells;
        this.light = new byte[this.volume];
        this.opacity = new byte[this.volume];
        this.emission = new byte[this.volume];
        this.offsetPosX = 1;
        this.offsetNegX = -1;
        this.offsetPosZ = width;
        this.offsetNegZ = -width;
        this.offsetPosY = this.area;
        this.offsetNegY = -this.area;
        this.dirty = new long[(this.volume >>> 6) + 1];
        this.touched = new long[(this.volume >>> 6) + 1];
    }

    public int localX(final int index) {
        return index % this.width;
    }

    public int localZ(final int index) {
        return (index / this.width) % this.depth;
    }

    public int localY(final int index) {
        return index / this.area;
    }

    public void markDirty(final int index) {
        final long[] dirty = this.dirty;
        dirty[index >>> 6] |= 1L << index;
    }

    public void markTouched(final int index) {
        final long[] touched = this.touched;
        touched[index >>> 6] |= 1L << index;
    }

    public boolean isDirty(final int index) {
        return (this.dirty[index >>> 6] & (1L << index)) != 0L;
    }

    public boolean isTouched(final int index) {
        return (this.touched[index >>> 6] & (1L << index)) != 0L;
    }

    public void clearChangeTracking() {
        java.util.Arrays.fill(this.dirty, 0L);
        java.util.Arrays.fill(this.touched, 0L);
    }

    /** Light semantics shared with the base engine: air costs 1 to pass through, opacity {@code n} costs {@code n}. */
    public static int propagationCost(final int opacity) {
        return opacity <= 0 ? 1 : opacity;
    }
}
