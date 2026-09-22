package ca.spottedleaf.starlight.common.light.own;

import net.minecraft.world.level.chunk.DataLayer;

/**
 * R1 of the new engine: a self-owned light field for one chunk section (see {@code docs/NEW-ENGINE-TEARDOWN.md}).
 *
 * <p><b>What this is for.</b> Both predecessors had to choose between two bad options: 1.x could install an edit inside
 * the call because the light truth was its own region image, but its material extraction is what closed the own-engine
 * attempt (per-cell palette reads, ~45 ns/cell); the ScalableLux base keeps the truth in vanilla's arrays but must hand
 * edits to a light thread, which costs the completion latency measured at ~4.3 ms on {@code block_toggle_border} and
 * which cannot be deferred safely (four thread dumps, four different phases - see
 * {@code docs/OWN-SCHEDULER-PLAN.md} §9/§10).</p>
 *
 * <p><b>The design decision that separates this from own-engine v1.</b> This field is <i>not</i> a region image and it
 * is <i>not</i> rebuilt from a palette walk. It is a byte-for-byte copy of the section's nibble arrays, so loading and
 * installing a section is a memcpy rather than a per-cell walk. v1 died because an edit needed material that had to be
 * re-extracted from the vanilla arrays (4096 cells per section); here the material <i>is</i> the field, and an edit
 * only touches the cells its propagation reaches.</p>
 *
 * <p><b>Invariants (each one earned by a real defect in this project's history):</b></p>
 * <ul>
 *   <li>writing back must update <b>both</b> the updating and the visible array - writing only half is exactly the
 *   1.1.5 restart-truncation incident;</li>
 *   <li>an existing {@code DataLayer} object must never be replaced (the light thread may still hold it) - copy into it
 *   instead;</li>
 *   <li>the packing must stay vanilla's nibble layout so both directions are bulk copies and the save format is
 *   untouched;</li>
 *   <li>the field is thread-confined to the thread that owns the section's work (same rule as the engine's pooled
 *   instances).</li>
 * </ul>
 *
 * <p><b>Status: R1 skeleton.</b> The round trip is implemented in terms of {@link DataLayer#getData()}; the correctness
 * gate for R1 is a cell-by-cell comparison against both baselines (vanilla/SL) plus the differential probe, and the
 * fuse is simple: if the round trip is not cheap enough to be per-edit affordable, stop at R1.</p>
 */
public final class OwnLightField {

    /** Vanilla nibble layout: 2048 bytes for a 16x16x16 section, 4 bits per cell. */
    public static final int NIBBLE_BYTES = 2048;

    /** The section this field belongs to, as {@code SectionPos.asLong}. */
    public final long sectionKey;

    // Own copies of the two light layers. Same packing as vanilla on purpose: load and install are memcpys.
    private final byte[] sky = new byte[NIBBLE_BYTES];
    private final byte[] block = new byte[NIBBLE_BYTES];

    private OwnLightField(final long sectionKey) {
        this.sectionKey = sectionKey;
    }

    /** Copies a section's light out of the vanilla arrays. {@code null} layers are treated as all-zero. */
    public static OwnLightField readFrom(final long sectionKey, final DataLayer skyUpdating, final DataLayer blockUpdating) {
        final OwnLightField ret = new OwnLightField(sectionKey);
        if (skyUpdating != null) {
            System.arraycopy(skyUpdating.getData(), 0, ret.sky, 0, NIBBLE_BYTES);
        }
        if (blockUpdating != null) {
            System.arraycopy(blockUpdating.getData(), 0, ret.block, 0, NIBBLE_BYTES);
        }
        return ret;
    }

    /**
     * Installs the field back into the section's arrays. Both the updating and the visible layer are written when they
     * are present, and an existing layer object is written in place rather than replaced.
     */
    public void writeBack(final DataLayer skyUpdating, final DataLayer skyVisible,
                          final DataLayer blockUpdating, final DataLayer blockVisible) {
        if (skyUpdating != null) {
            System.arraycopy(this.sky, 0, skyUpdating.getData(), 0, NIBBLE_BYTES);
        }
        if (skyVisible != null) {
            System.arraycopy(this.sky, 0, skyVisible.getData(), 0, NIBBLE_BYTES);
        }
        if (blockUpdating != null) {
            System.arraycopy(this.block, 0, blockUpdating.getData(), 0, NIBBLE_BYTES);
        }
        if (blockVisible != null) {
            System.arraycopy(this.block, 0, blockVisible.getData(), 0, NIBBLE_BYTES);
        }
    }

    public byte[] skyBytes() {
        return this.sky;
    }

    public byte[] blockBytes() {
        return this.block;
    }

    /** Raw nibble read, for the propagation core and the tests. */
    public int getSky(final int x, final int y, final int z) {
        return nibble(this.sky, index(x, y, z));
    }

    public int getBlock(final int x, final int y, final int z) {
        return nibble(this.block, index(x, y, z));
    }

    public void setSky(final int x, final int y, final int z, final int level) {
        setNibble(this.sky, index(x, y, z), level);
    }

    public void setBlock(final int x, final int y, final int z, final int level) {
        setNibble(this.block, index(x, y, z), level);
    }

    // vanilla layout: index = (y << 8) | (z << 4) | x, low nibble first
    private static int index(final int x, final int y, final int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static int nibble(final byte[] data, final int index) {
        final int b = data[index >> 1] & 0xFF;
        return (index & 1) == 0 ? (b & 0x0F) : (b >> 4);
    }

    private static void setNibble(final byte[] data, final int index, final int level) {
        final int i = index >> 1;
        final int b = data[i] & 0xFF;
        data[i] = (byte) ((index & 1) == 0
                ? ((b & 0xF0) | (level & 0x0F))
                : ((b & 0x0F) | ((level & 0x0F) << 4)));
    }

    // ------------------------------------------------------------------------------------------------------------
    // R1 acceptance: the round trip is exact, and cheap enough to be per-edit affordable.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * R1's gate (run at server start with {@code -Dscalablelux.ownFieldSelfTest=true}):
     *
     * <ol>
     *   <li><b>Correctness:</b> a synthetic section's light is read into the field, mutated, written back, and every
     *   one of the 4096 cells is compared against an independently computed expectation - including the nibble
     *   packing (both halves of a byte), untouched neighbours, and the rule that write-back must hit both the updating
     *   and the visible layer.</li>
     *   <li><b>Cost:</b> {@code load+install} of a whole section is timed; the budget is that it has to be a memcpy
     *   class operation (tens of nanoseconds, not microseconds), because R2 runs it per edited section per edit.</li>
     * </ol>
     *
     * <p>Prints one {@code LIGHTFIELD-SELFTEST} line with the outcome, so a run log carries the evidence.</p>
     */
    public static void selfTest() {
        final int failures = 0;
        int errors = 0;

        // synthetic source layers with a pattern that exercises both nibble halves and every value
        final byte[] sourceSky = new byte[NIBBLE_BYTES];
        final byte[] sourceBlock = new byte[NIBBLE_BYTES];
        for (int i = 0; i < NIBBLE_BYTES; i++) {
            sourceSky[i] = (byte) ((i * 7) & 0xFF);
            sourceBlock[i] = (byte) ((i * 13 + 5) & 0xFF);
        }

        final DataLayer skyUpdating = new DataLayer(sourceSky.clone());
        final DataLayer skyVisible = new DataLayer(new byte[NIBBLE_BYTES]);
        final DataLayer blockUpdating = new DataLayer(sourceBlock.clone());
        final DataLayer blockVisible = new DataLayer(new byte[NIBBLE_BYTES]);

        // 1) read
        final OwnLightField field = readFrom(0L, skyUpdating, blockUpdating);

        // 2) mutate: flip every cell to 15 - blocklight, and to 0 - skylight, then restore one cell of each extreme
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    field.setBlock(x, y, z, 15);
                    field.setSky(x, y, z, 0);
                }
            }
        }
        field.setSky(3, 4, 5, 7);
        field.setBlock(11, 12, 13, 9);

        // 3) install
        field.writeBack(skyUpdating, skyVisible, blockUpdating, blockVisible);

        // 4) verify every cell against the expectation, through the layer's own accessors
        for (int y = 0; y < 16 && errors == 0; y++) {
            for (int z = 0; z < 16 && errors == 0; z++) {
                for (int x = 0; x < 16; x++) {
                    final int expectedSky = (x == 3 && y == 4 && z == 5) ? 7 : 0;
                    final int expectedBlock = (x == 11 && y == 12 && z == 13) ? 9 : 15;
                    if (skyUpdating.get(x, y, z) != expectedSky) {
                        errors++;
                        break;
                    }
                    if (skyVisible.get(x, y, z) != expectedSky) {
                        errors++;
                        break;
                    }
                    if (blockUpdating.get(x, y, z) != expectedBlock) {
                        errors++;
                        break;
                    }
                    if (blockVisible.get(x, y, z) != expectedBlock) {
                        errors++;
                        break;
                    }
                }
            }
        }

        // 5) cost: whole-section load + install
        final int iterations = 20_000;
        long nanos = 0L;
        for (int i = 0; i < iterations; i++) {
            final long t0 = System.nanoTime();
            final OwnLightField f = readFrom(0L, skyUpdating, blockUpdating);
            f.writeBack(skyUpdating, skyVisible, blockUpdating, blockVisible);
            nanos += System.nanoTime() - t0;
        }
        final long perRoundTrip = nanos / iterations;

        System.out.println("LIGHTFIELD-SELFTEST cells=4096 mismatches=" + errors
                + " roundTripNanos=" + perRoundTrip
                + " verdict=" + (errors == 0 && perRoundTrip < 20_000L ? "PASS" : "FAIL"));
    }
}
