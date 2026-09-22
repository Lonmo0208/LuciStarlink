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
        // Two questions, and they need different references:
        //  (a) can a cheap seed set (run bottoms + wall faces) reproduce the engine?   -> section 20, answered: no
        //  (b) if we were allowed to define the light ourselves, how much would change? -> this one, and it needs the
        //      rule's own fixed point, which is what seeding EVERY run cell gives (the engine's increase rule applied
        //      from scratch). The mismatch against the stored light is then exactly the price of dropping the
        //      "bit-identical to vanilla" promise: those cells would take a different value than the history left them.
        final byte[] mine = new byte[cells];
        final int[] seedQueue = new int[cells * 8]; // a cell can be pushed more than once as its level grows
        int seedCount = 0;
        final long algorithmT0 = System.nanoTime();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = maxY; y >= minY; y--) {
                    final int index = boxIndex(x, y, z, minX, minY, minZ, maxX, maxZ);
                    if (materialCells[index] != 0) {
                        break;
                    }
                    mine[index] = 15;
                    seedQueue[seedCount++] = index; // every source cell: the rule's fixed point, not a heuristic subset
                }
            }
        }
        final long sweepOnlyNanos = System.nanoTime() - algorithmT0;
        int head = 0;
        int tail = seedCount;
        while (head < tail) {
            final int index = seedQueue[head++];
            final int seedLevel = mine[index] & 0xFF;
            if (seedLevel <= 1) {
                continue;
            }
            final int y = minY + index / (width * depth);
            final int rem = index % (width * depth);
            final int z = minZ + rem / width;
            final int x = minX + rem % width;
            for (int dir = 0; dir < 5; dir++) {
                final int nx = x + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                final int ny = y + (dir == 4 ? -1 : 0);
                final int nz = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
                if (nx < minX || nx > maxX || ny < minY || nz < minZ || nz > maxZ) {
                    continue;
                }
                final int nIndex = boxIndex(nx, ny, nz, minX, minY, minZ, maxX, maxZ);
                final int cellOpacity = materialCells[nIndex] & 0xFF;
                if (cellOpacity == ExtendedChunk.MATERIAL_UNCACHED) {
                    continue;
                }
                final int target = seedLevel - Math.max(1, cellOpacity);
                if (target > (mine[nIndex] & 0xFF)) {
                    mine[nIndex] = (byte) target;
                    if (target > 1) {
                        seedQueue[tail++] = nIndex;
                    }
                }
            }
        }
        int mineZeroTruthLit = 0;
        int mineLitTruthZero = 0;
        int bothLitDifferent = 0;
        final StringBuilder examples = new StringBuilder();
        final long algorithmNanos = System.nanoTime() - algorithmT0;
        int algorithmMismatch = 0;
        int compared = 0;
        for (int z = minZ + 1; z < maxZ; z++) {
            for (int x = minX + 1; x < maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    compared++;
                    final int index = boxIndex(x, y, z, minX, minY, minZ, maxX, maxZ);
                    final int myValue = mine[index] & 0xFF;
                    final int truth = lightCells[index] & 0xFF;

                    if (myValue == truth) {
                        continue;
                    }
                    algorithmMismatch++;
                    if (myValue == 0) {
                        mineZeroTruthLit++;
                    } else if (truth == 0) {
                        mineLitTruthZero++;
                    } else {
                        bothLitDifferent++;
                    }
                    if (examples.length() < 240) {
                        // read the engine's INTERNAL nibble as well as the published value: part of the mismatch could be
                        // a publish artefact rather than a different computation, and this pair tells the two apart
                        int internal = -1;
                        final ChunkAccess cellChunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);

                        if (cellChunk != null) {
                            final SWMRNibbleArray[] nibbles = ((ExtendedChunk) cellChunk).scalablelux$getSkyNibbles();
                            final int section = (y >> 4) - WorldUtil.getMinLightSection(level);

                            if (nibbles != null && section >= 0 && section < nibbles.length && nibbles[section] != null) {
                                internal = nibbles[section].getUpdating(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
                            }
                        }
                        examples.append("(").append(x).append(',').append(y).append(',').append(z)
                                .append(" mine=").append(myValue).append(" vis=").append(truth)
                                .append(" nib=").append(internal).append(" mat=").append(materialCells[index] & 0xFF)
                                .append(") ");
                    }
                }
            }
        }
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
                + " algorithmSeeds=" + seedCount
                + " algorithmCompared=" + compared
                + " algorithmMismatch=" + algorithmMismatch
                + " sweepOnlyNanos=" + sweepOnlyNanos
                + " algorithmNanos=" + algorithmNanos
                + " algorithmNsPerCell=" + (compared == 0 ? 0L : algorithmNanos / compared)
                + " mineZeroTruthLit=" + mineZeroTruthLit
                + " mineLitTruthZero=" + mineLitTruthZero
                + " bothLitDifferent=" + bothLitDifferent);
        System.out.println("SKYSWEEP-EXAMPLES " + examples);
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
}
