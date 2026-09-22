package ca.spottedleaf.starlight.common.light.own;

import ca.spottedleaf.starlight.common.blockstate.ExtendedAbstractBlockState;
import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * R5-2 probe: can a <b>linear column sweep</b> over flat byte tables reproduce this engine's skylight, and what does it
 * cost? See {@code docs/NEW-ENGINE-TEARDOWN.md} §13.
 *
 * <p><b>Why this is the next lever, in one paragraph of measured facts.</b> On {@code structure_cube} our sky
 * propagation costs 1.70–2.00 ms per pass. Measured inside it: 24,385 queue entries at ~70 ns each (only ~6 ns of which
 * is actually reading a neighbour), 52,669 neighbour cells examined, 83% of them immediate level-skips; the block-state
 * lookup is 20 ns and happens 8,790 times; the per-cell publish notification is a server-side no-op. So the cost is the
 * <b>number of queue entries</b>, and those exist because the sky update is formulated as a six-neighbour BFS over the
 * whole newly-lit volume. 1.x's entire engine time on that cell is 0.26 ms ≈ one linear sweep of a chunk's 98,304 cells
 * at ~2.5 ns — it does not run that BFS for a column-shaped change. Two questions decide whether we can copy it:</p>
 *
 * <ol>
 *   <li><b>Coverage:</b> how much of a measured box does the pure column rule (15 down to the first cell that is not
 *   provably transparent) already produce, and how many cells are left for a fix-up pass?</li>
 *   <li><b>Cost:</b> what does the sweep cost per cell against the ~32 ns per touch the BFS pays now?</li>
 * </ol>
 *
 * <p>Read-only - nothing here writes light. The measurement harness calls it at the moment the light has settled (right
 * after its own fingerprint), over the box that fingerprint covers, so the state analysed is the state that was
 * measured. That is also why there is no tick or shutdown hook: a probe running inside the measured window would
 * perturb the reading it exists to explain.</p>
 */
public final class OwnSkySweepProbe {

    private OwnSkySweepProbe() {
    }

    /** Called reflectively by {@code LuxServerBenchmark} with {@code -Dlucistarlink.benchmark.skySweepProbe=true}. */
    public static void probeBox(final Level level, final int minX, final int minY, final int minZ,
                               final int maxX, final int maxY, final int maxZ) {
        try {
            probe(level, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (final Throwable throwable) {
            System.out.println("SKYSWEEP-PROBE failed: " + throwable);
        }
    }

    private static void probe(final Level level, final int minX, final int minY, final int minZ,
                              final int maxX, final int maxY, final int maxZ) {
        final var sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        final BlockGetter getter = level;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int worldTop = level.getMaxBuildHeight() - 1;
        final int worldBottom = level.getMinBuildHeight();

        final long t0 = System.nanoTime();
        int columns = 0;
        int cells = 0;
        int inRun = 0;
        int inRunMismatch = 0;
        int belowNonZero = 0;
        int unknownOpacity = 0;

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                columns++;
                // where does the world-top run of provably transparent cells end for this column?
                int runBottom = worldBottom - 1;
                for (int y = worldTop; y >= worldBottom; y--) {
                    pos.set(x, y, z);
                    if (materialOf(getter, pos) != 0) {
                        runBottom = y;
                        break;
                    }
                }
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    final int material = materialOf(getter, pos);
                    if (material == 2) {
                        unknownOpacity++;
                    }
                    final int engine = sky.getLightValue(pos);
                    cells++;
                    if (y > runBottom) {
                        // the column rule says 15 here
                        inRun++;
                        if (engine != 15) {
                            inRunMismatch++;
                        }
                    } else if (engine != 0) {
                        // below the run: only a fix-up pass can know these, and they are what the BFS is for
                        belowNonZero++;
                    }
                }
            }
        }
        final long nanos = System.nanoTime() - t0;

        // The decisive number for the reformulation: what does the SAME sweep cost when it runs over plain arrays
        // instead of through getLightValue()/getBlockState()? 1.x's whole engine time on structure_cube is 0.26 ms,
        // i.e. one 98,304-cell chunk at ~2.5 ns/cell - so if the flat sweep is not at that order, the reformulation has
        // no path and the cell stays as it is. This is pure array work: no engine, no world, no palette.
        final byte[] materialCells = new byte[cells];
        final byte[] lightCells = new byte[cells];
        final byte[] flatSweepOut = new byte[cells];
        for (int i = 0; i < cells; i++) {
            lightCells[i] = (byte) 0; // filled below by the same walk, so the buffer is real, not a copy of nothing
        }
        final int width = maxX - minX + 1;
        final int depth = maxZ - minZ + 1;
        {
            int cursor = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        pos.set(x, y, z);
                        materialCells[cursor] = (byte) materialOf(getter, pos);
                        lightCells[cursor] = (byte) sky.getLightValue(pos);
                        cursor++;
                    }
                }
            }
        }
        final long flatT0 = System.nanoTime();
        final int rounds = 8;
        final boolean[] blocked = new boolean[width * depth];
        for (int round = 0; round < rounds; round++) {
            java.util.Arrays.fill(flatSweepOut, (byte) 0);
            java.util.Arrays.fill(blocked, false);
            for (int y = maxY; y >= minY; y--) {
                final int plane = (y - minY) * width * depth;
                for (int z = minZ; z <= maxZ; z++) {
                    final int row = plane + (z - minZ) * width;
                    for (int x = 0; x < width; x++) {
                        final int column = (z - minZ) * width + x;
                        if (blocked[column]) {
                            continue;
                        }
                        final int index = row + x;
                        if (materialCells[index] != 0) {
                            blocked[column] = true;
                        } else {
                            flatSweepOut[index] = 15;
                        }
                    }
                }
            }
        }

        // ---- the reformulation itself, validated off-engine ------------------------------------------------
        //  (a) can a cheap seed set reproduce the engine?  Section 20 said no, but that probe skipped the box's EDGE
        //      columns while comparing from minX+1 - the columns next to the unseeded edge were guaranteed dark. This
        //      run seeds the same cheap set over EVERY column, so the question is finally fair.
        //  (b) what does the rule itself produce? Every run cell seeded = the rule's fixed point; its difference from
        //      the stored light is the price of dropping bit-identity (section 21 measured 6.5%, all one-directional).
        // Both are computed here, over the same material, and reported separately.
        final byte[] mine = new byte[cells];
        final byte[] mineFull = new byte[cells];
        final int[] seedQueue = new int[cells * 8]; // a cell can be pushed more than once as its level grows
        final int[] seedQueueFull = new int[cells * 8];
        int seedCount = 0;
        int seedCountFull = 0;
        final int[] runBottoms = new int[width * depth]; // the lowest lit cell of each column, or MIN_VALUE
        java.util.Arrays.fill(runBottoms, Integer.MIN_VALUE);
        final long algorithmT0 = System.nanoTime();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = maxY; y >= minY; y--) {
                    final int index = boxIndex(x, y, z, minX, minY, minZ, maxX, maxZ);
                    if (materialCells[index] != 0) {
                        break;
                    }
                    mine[index] = 15;
                    mineFull[index] = 15;
                    seedQueueFull[seedCountFull++] = index; // the rule's fixed point, not a heuristic subset
                    runBottoms[(z - minZ) * width + (x - minX)] = y;
                }
            }
        }
        // The cheap shell: a lit cell is a source only where light can actually LEAVE its column - at the run bottom
        // (attenuated light through whatever ended the run), or where a neighbouring column's run ends BELOW this cell's
        // height, i.e. the neighbour is dark at this Y and light has to spill sideways. The previous attempt tested the
        // neighbouring *cell's* material instead, which misses exactly that case: a transparent neighbour under a
        // ceiling is dark while its own cell is air - that is why it under-propagated 86,669 cells.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                final int runBottom = runBottoms[(z - minZ) * width + (x - minX)];

                if (runBottom == Integer.MIN_VALUE) {
                    continue;
                }
                for (int y = runBottom; y <= maxY; y++) {
                    final int index = boxIndex(x, y, z, minX, minY, minZ, maxX, maxZ);

                    if ((mine[index] & 0xFF) != 15) {
                        continue;
                    }
                    boolean seed = y == runBottom;

                    for (int dir = 0; dir < 4 && !seed; dir++) {
                        final int nx = x + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                        final int nz = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);

                        if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                            seed = true; // outside the box: the neighbour's run is unknown here, so err towards seeding
                            break;
                        }
                        final int neighbourRunBottom = runBottoms[(nz - minZ) * width + (nx - minX)];

                        if (neighbourRunBottom == Integer.MIN_VALUE || neighbourRunBottom > y) {
                            seed = true;
                        }
                    }
                    if (seed) {
                        seedQueue[seedCount++] = index;
                    }
                }
            }
        }
        final long sweepOnlyNanos = System.nanoTime() - algorithmT0;
        solveIncrease(mine, seedQueue, seedCount, materialCells, minX, minY, minZ, maxX, maxY, maxZ);
        final long cheapNanos = System.nanoTime() - algorithmT0;
        final long fullNanos = cheapNanos;
        solveIncrease(mineFull, seedQueueFull, seedCountFull, materialCells, minX, minY, minZ, maxX, maxY, maxZ);
        final StringBuilder examples = new StringBuilder();
        int cheapMismatch = 0;
        int cheapDarker = 0;
        int fullMismatch = 0;
        int chunk00Mismatch = 0;
        int chunk00Compared = 0;
        int chunk00Darker = 0;
        int chunk00Lighter = 0;
        final StringBuilder chunk00Examples = new StringBuilder();
        int compared = 0;
        for (int z = minZ + 1; z < maxZ; z++) {
            for (int x = minX + 1; x < maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    compared++;
                    final int index = boxIndex(x, y, z, minX, minY, minZ, maxX, maxZ);
                    final int myValue = mine[index] & 0xFF;
                    final int fullValue = mineFull[index] & 0xFF;
                    final int truth = lightCells[index] & 0xFF;

                    if (myValue != truth) {
                        cheapMismatch++;
                        if (myValue < truth) {
                            cheapDarker++;
                        }
                        if (examples.length() < 400) {
                            examples.append("(").append(x).append(',').append(y).append(',').append(z)
                                    .append(" cheap=").append(myValue).append(" full=").append(fullValue)
                                    .append(" vis=").append(truth)
                                    .append(" mat=").append(materialCells[index] & 0xFF).append(") ");
                        }
                    }
                    if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
                        chunk00Compared++;
                        if (myValue != truth) {
                            if (myValue < truth) {
                                chunk00Darker++;
                            } else {
                                chunk00Lighter++;
                                if (chunk00Examples.length() < 600) {
                                    chunk00Examples.append("(").append(x).append(",").append(y).append(",").append(z)
                                            .append(" vis=").append(truth).append(" rule=").append(myValue)
                                            .append(" mat=").append(materialCells[index] & 0xFF).append(") ");
                                }
                            }
                            chunk00Mismatch++;
                        }
                    }
                    if (fullValue != truth) {
                        fullMismatch++;
                    }
                }
            }
        }
        final long algorithmNanos = System.nanoTime() - algorithmT0;
        final long flatNanos = (System.nanoTime() - flatT0) / rounds;

        System.out.println("SKYSWEEP-PROBE box=" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ
                + " columns=" + columns
                + " cells=" + cells
                + " inRun=" + inRun
                + " inRunMismatch=" + inRunMismatch
                + " belowRunNonZero=" + belowNonZero
                + " unknownOpacity=" + unknownOpacity
                + " probeNanos=" + nanos
                + " nsPerCell=" + (cells == 0 ? 0L : nanos / cells)
                + " ruleCoveragePct=" + (cells == 0 ? 0L : (100L * inRun / cells))
                + " fixupCells=" + (cells - inRun + inRunMismatch)
                + " fixupCostEstNanos=" + ((long) (cells - inRun + inRunMismatch) * 32L)
                + " flatSweepNanos=" + flatNanos
                + " flatSweepNsPerCell=" + (cells == 0 ? 0L : flatNanos / cells)
                + " flatSweepOneChunkNanos=" + (cells == 0 ? 0L : (98304L * (flatNanos / Math.max(1L, cells))))
                + " flatSweepSink=" + (flatSweepOut[cells - 1] + lightCells[0])
                + " cheapSeeds=" + seedCount
                + " cheapMismatch=" + cheapMismatch
                + " cheapDarker=" + cheapDarker
                + " cheapNanos=" + cheapNanos
                + " fullSeeds=" + seedCountFull
                + " fullMismatch=" + fullMismatch
                + " fullNanos=" + fullNanos
                + " compared=" + compared
                + " chunk00Compared=" + chunk00Compared
                + " chunk00Mismatch=" + chunk00Mismatch
                + " chunk00Darker=" + chunk00Darker
                + " chunk00Lighter=" + chunk00Lighter);
        System.out.println("SKYSWEEP-EXAMPLES " + examples);
        System.out.println("SKYSWEEP-CHUNK00 " + chunk00Examples);
    }

    /** 0 = provably transparent (opacity 0), 1 = blocks the run, 2 = opacity not cached yet. */
    private static int materialOf(final BlockGetter getter, final BlockPos pos) {
        final BlockState state = getter.getBlockState(pos);
        final int opacity = ((ExtendedAbstractBlockState) state).scalablelux$getOpacityIfCached();
        return opacity == 0 ? 0 : (opacity > 0 ? 1 : 2);
    }
    /** Flat index of a cell inside the probe box: y-major, then z, then x. */
    private static int boxIndex(final int x, final int y, final int z,
                                final int minX, final int minY, final int minZ, final int maxX, final int maxZ) {
        return ((y - minY) * (maxZ - minZ + 1) + (z - minZ)) * (maxX - minX + 1) + (x - minX);
    }
    /** The engine's own increase rule over the probe's arrays, from the given seeds, to a fixed point. */
    private static void solveIncrease(final byte[] field, final int[] queue, final int seedCount, final byte[] material,
                                      final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
        final int width = maxX - minX + 1;
        final int depth = maxZ - minZ + 1;
        int head = 0;
        int tail = seedCount;

        while (head < tail) {
            final int index = queue[head++];
            final int level = field[index] & 0xFF;

            if (level <= 1) {
                continue;
            }
            final int y = minY + index / (width * depth);
            final int rem = index % (width * depth);
            final int z = minZ + rem / width;
            final int x = minX + rem % width;

            for (int dir = 0; dir < 5; dir++) { // four horizontals and down: skylight never propagates upward
                final int nx = x + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                final int ny = y + (dir == 4 ? -1 : 0);
                final int nz = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);

                if (nx < minX || nx > maxX || ny < minY || nz < minZ || nz > maxZ) {
                    continue;
                }
                final int nIndex = boxIndex(nx, ny, nz, minX, minY, minZ, maxX, maxZ);
                final int opacity = material[nIndex] & 0xFF;

                if (opacity == ExtendedChunk.MATERIAL_UNCACHED) {
                    continue;
                }
                final int target = level - Math.max(1, opacity);

                if (target > (field[nIndex] & 0xFF)) {
                    field[nIndex] = (byte) target;
                    if (target > 1 && tail < queue.length) {
                        queue[tail++] = nIndex;
                    }
                }
            }
        }
    }
}
