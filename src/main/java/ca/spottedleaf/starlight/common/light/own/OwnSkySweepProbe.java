package ca.spottedleaf.starlight.common.light.own;

import ca.spottedleaf.starlight.common.blockstate.ExtendedAbstractBlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

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
                + " fixupCostEstNanos=" + ((long) (cells - inRun + inRunMismatch) * 32L));
    }

    /** 0 = provably transparent (opacity 0), 1 = blocks the run, 2 = opacity not cached yet. */
    private static int materialOf(final BlockGetter getter, final BlockPos pos) {
        final BlockState state = getter.getBlockState(pos);
        final int opacity = ((ExtendedAbstractBlockState) state).scalablelux$getOpacityIfCached();
        return opacity == 0 ? 0 : (opacity > 0 ? 1 : 2);
    }
}
