package ca.spottedleaf.starlight.common.light.own;

import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.Random;

/**
 * R5 of the new engine: the flat byte-per-cell light field (see {@code docs/NEW-ENGINE-TEARDOWN.md} §13).
 *
 * <p><b>Why one byte per cell instead of vanilla's nibbles.</b> The R4 acceptance measured where the engine metric is
 * actually lost: on {@code structure_cube} one pass touches <b>52,669 neighbour cells</b> during propagation and that
 * costs <b>1.99 ms</b> — <b>~38 ns per touched cell</b>, while {@link SWMRNibbleArray#getUpdating(int)} itself is only
 * 2–4 ns. The difference is the <b>two-level pointer chase</b> the base's storage forces on every touch
 * ({@code nibbleCache[sectionIndex]} → {@code SWMRNibbleArray} → {@code storageUpdating[]}), each hop a likely cache
 * miss. 1.x keeps light in one flat region-wide byte image, touches cost it ~5 ns, and its whole engine time on that
 * cell is <b>0.26 ms</b> — the same 52,669 touches. So the target is not the algorithm (83% of those touches are
 * immediate level-skips anyway) but the <b>shape of the storage</b>.</p>
 *
 * <p><b>What this class is.</b> One section's light as two byte arrays (sky, block), <b>one cell per byte</b>, in
 * vanilla's cell order ({@code (y &lt;&lt; 8) | (z &lt;&lt; 4) | x}) so a section is contiguous and prefetchable. Nibble
 * arrays stay the published truth (vanilla interop, save format, client sync, worldgen all depend on them), so the two
 * conversions that matter are priced by {@link #selfTest()}: <b>build</b> (nibbles → bytes) and <b>publish</b>
 * (bytes → nibbles, both the updating and the visible layer). Those two numbers decide the design: the field is only
 * worth keeping if build+publish per section is small against the ~1.7 ms the flat touches give back.</p>
 *
 * <p><b>Not R1's field.</b> {@link OwnLightField} copies the <i>nibble</i> layout byte for byte — right for memcpy
 * install, useless for propagation because every cell read still pays a shift/and. This one is 2× the memory
 * (4096 + 4096 bytes per section against 2048 + 2048) and buys a one-instruction cell access; that trade is the whole
 * point of R5 and it is exactly what the fuse in §13.4 is there to judge.</p>
 */
public final class OwnFlatField {

    /** 16x16x16. */
    public static final int CELLS = 4096;

    private final byte[] sky = new byte[CELLS];
    private final byte[] block = new byte[CELLS];

    private OwnFlatField() {
    }

    /** Expands a section's nibble layers into the flat field. {@code null} layers read as all-zero. */
    public static OwnFlatField readFrom(final DataLayer skyUpdating, final DataLayer blockUpdating) {
        final OwnFlatField ret = new OwnFlatField();
        if (skyUpdating != null) {
            expand(skyUpdating.getData(), ret.sky);
        }
        if (blockUpdating != null) {
            expand(blockUpdating.getData(), ret.block);
        }
        return ret;
    }

    /** Writes the field back as nibbles, into both the updating and the visible layer when they are present. */
    public void writeBack(final DataLayer skyUpdating, final DataLayer skyVisible,
                          final DataLayer blockUpdating, final DataLayer blockVisible) {
        if (skyUpdating != null) {
            pack(this.sky, skyUpdating.getData());
        }
        if (skyVisible != null) {
            pack(this.sky, skyVisible.getData());
        }
        if (blockUpdating != null) {
            pack(this.block, blockUpdating.getData());
        }
        if (blockVisible != null) {
            pack(this.block, blockVisible.getData());
        }
    }

    public byte[] skyCells() {
        return this.sky;
    }

    public byte[] blockCells() {
        return this.block;
    }

    public int getSky(final int x, final int y, final int z) {
        return this.sky[index(x, y, z)] & 0xFF;
    }

    public int getBlock(final int x, final int y, final int z) {
        return this.block[index(x, y, z)] & 0xFF;
    }

    public void setSky(final int x, final int y, final int z, final int level) {
        this.sky[index(x, y, z)] = (byte) level;
    }

    public void setBlock(final int x, final int y, final int z, final int level) {
        this.block[index(x, y, z)] = (byte) level;
    }

    private static int index(final int x, final int y, final int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static void expand(final byte[] nibbles, final byte[] out) {
        for (int i = 0; i < CELLS >> 1; i++) {
            final int b = nibbles[i] & 0xFF;
            out[i << 1] = (byte) (b & 0x0F);
            out[(i << 1) | 1] = (byte) (b >>> 4);
        }
    }

    private static void pack(final byte[] cells, final byte[] nibbles) {
        for (int i = 0; i < CELLS >> 1; i++) {
            nibbles[i] = (byte) ((cells[i << 1] & 0x0F) | ((cells[(i << 1) | 1] & 0x0F) << 4));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // R5-1 acceptance: the round trip is exact, and the flat layout really is cheaper per touched cell.
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Run at server start with {@code -Dscalablelux.ownFlatSelfTest=true}. Prints one {@code FLATFIELD-SELFTEST} line.
     *
     * <ol>
     *   <li><b>Correctness:</b> nibbles → flat → mutate → nibbles, all 4096 cells compared through the layer's own
     *   accessors, both the updating and the visible layer (the 1.1.5 invariant).</li>
     *   <li><b>Build / publish:</b> per-section cost of each conversion.</li>
     *   <li><b>Touch:</b> 52,669 reads over a 25-section working set — the {@code structure_cube} access pattern — in
     *   the base's layout (a {@code SWMRNibbleArray} reference array plus per-section nibble arrays, i.e. the two hops
     *   the engine pays) against the flat layout (one contiguous byte array over the same 25 sections). This is the
     *   number that decides R5: if the flat touch is not clearly cheaper, the rewrite is not worth it and the cell is
     *   declared a reading of vanilla.</li>
     * </ol>
     */
    public static void selfTest() {
        // --- 1) correctness: nibbles -> flat -> nibbles, every cell through DataLayer's accessors
        final byte[] sourceSky = new byte[OwnLightField.NIBBLE_BYTES];
        final byte[] sourceBlock = new byte[OwnLightField.NIBBLE_BYTES];
        for (int i = 0; i < OwnLightField.NIBBLE_BYTES; i++) {
            sourceSky[i] = (byte) ((i * 7) & 0xFF);
            sourceBlock[i] = (byte) ((i * 13 + 5) & 0xFF);
        }
        final DataLayer skyUpdating = new DataLayer(sourceSky.clone());
        final DataLayer skyVisible = new DataLayer(new byte[OwnLightField.NIBBLE_BYTES]);
        final DataLayer blockUpdating = new DataLayer(sourceBlock.clone());
        final DataLayer blockVisible = new DataLayer(new byte[OwnLightField.NIBBLE_BYTES]);

        final OwnFlatField field = readFrom(skyUpdating, blockUpdating);
        int errors = 0;
        // the field must equal the source for every cell before any mutation
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (field.getSky(x, y, z) != skyUpdating.get(x, y, z)) {
                        errors++;
                    }
                    if (field.getBlock(x, y, z) != blockUpdating.get(x, y, z)) {
                        errors++;
                    }
                }
            }
        }
        // mutate and write back, then verify both layers
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
        field.writeBack(skyUpdating, skyVisible, blockUpdating, blockVisible);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int expectedSky = (x == 3 && y == 4 && z == 5) ? 7 : 0;
                    final int expectedBlock = (x == 11 && y == 12 && z == 13) ? 9 : 15;
                    if (skyUpdating.get(x, y, z) != expectedSky || skyVisible.get(x, y, z) != expectedSky) {
                        errors++;
                    }
                    if (blockUpdating.get(x, y, z) != expectedBlock || blockVisible.get(x, y, z) != expectedBlock) {
                        errors++;
                    }
                }
            }
        }

        // --- 2) build / publish cost per section
        final int buildIterations = 20_000;
        long buildNanos = 0L;
        for (int i = 0; i < buildIterations; i++) {
            final long t0 = System.nanoTime();
            final OwnFlatField f = readFrom(skyUpdating, blockUpdating);
            buildNanos += System.nanoTime() - t0;
            if (f.skyCells() == null) {
                throw new IllegalStateException();
            }
        }
        long publishNanos = 0L;
        for (int i = 0; i < buildIterations; i++) {
            final long t0 = System.nanoTime();
            field.writeBack(skyUpdating, skyVisible, blockUpdating, blockVisible);
            publishNanos += System.nanoTime() - t0;
        }
        final long buildPer = buildNanos / buildIterations;
        final long publishPer = publishNanos / buildIterations;

        // --- 3) touch cost: the base's layout against the flat one, same 52,669 reads over 25 sections
        final int sections = 25;
        final int touches = 52_669;
        final int[] sectionOf = new int[touches];
        final int[] cellOf = new int[touches];
        final Random random = new Random(1L);
        for (int i = 0; i < touches; i++) {
            sectionOf[i] = random.nextInt(sections);
            cellOf[i] = random.nextInt(CELLS);
        }

        // base layout: a section reference array, each element an object holding its own nibble array
        final SWMRNibbleArray[] nibbleCache = new SWMRNibbleArray[sections];
        final byte[][] rawNibbles = new byte[sections][OwnLightField.NIBBLE_BYTES];
        for (int i = 0; i < sections; i++) {
            for (int j = 0; j < OwnLightField.NIBBLE_BYTES; j++) {
                rawNibbles[i][j] = (byte) (j * 31 + i);
            }
            nibbleCache[i] = new SWMRNibbleArray(rawNibbles[i]);
        }
        // flat layout: one contiguous byte array over the same 25 sections
        final byte[] flatCells = new byte[sections * CELLS];
        for (int i = 0; i < flatCells.length; i++) {
            flatCells[i] = (byte) (i * 31);
        }

        final int touchRounds = 40;
        long nibbleTouchNanos = 0L;
        long flatTouchNanos = 0L;
        int sink = 0;
        for (int round = 0; round < touchRounds; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < touches; i++) {
                sink += nibbleCache[sectionOf[i]].getUpdating(cellOf[i]);
            }
            nibbleTouchNanos += System.nanoTime() - t0;

            t0 = System.nanoTime();
            for (int i = 0; i < touches; i++) {
                sink += flatCells[(sectionOf[i] << 12) | cellOf[i]] & 0xFF;
            }
            flatTouchNanos += System.nanoTime() - t0;
        }
        final long nibblePerTouch = nibbleTouchNanos / ((long) touches * touchRounds);
        final long flatPerTouch = flatTouchNanos / ((long) touches * touchRounds);
        // keep the reads alive so the JIT cannot drop either loop
        if (sink == Integer.MIN_VALUE) {
            System.out.println(sink);
        }

        System.out.println("FLATFIELD-SELFTEST cells=4096 mismatches=" + errors
                + " buildNanos=" + buildPer
                + " publishNanos=" + publishPer
                + " nibbleTouchPicos=" + (nibbleTouchNanos * 1000L / ((long) touches * touchRounds))
                + " flatTouchPicos=" + (flatTouchNanos * 1000L / ((long) touches * touchRounds))
                + " nibblePerTouchNanos=" + nibblePerTouch
                + " flatPerTouchNanos=" + flatPerTouch
                + " ratio=" + (flatPerTouch == 0L ? "inf" : String.format("%.2f", (double) nibblePerTouch / (double) flatPerTouch))
                + " verdict=" + (errors == 0 && flatPerTouch < nibblePerTouch ? "PASS" : "FAIL"));
    }
}
