package ca.spottedleaf.starlight.common.command;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.compat.SableCompat;
import ca.spottedleaf.starlight.common.debug.LuxProfiler;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The in-game introspection this engine did not have: {@code /scalablelux stats | light | relight}.
 *
 * <ul>
 *   <li>{@code stats} - the same figures as the telemetry line, on demand, for the level the
 *       command runs in.</li>
 *   <li>{@code light <pos>} - block/sky/raw light at a position plus whether its chunk claims to
 *       be lit, i.e. enough to answer "is this dark spot our problem or the map's".</li>
 *   <li>{@code relight <radius>} - the repair path for a world whose stored light is already
 *       wrong: mark the chunks light-incorrect and hand them to {@code lightChunk(chunk, false)},
 *       which is exactly what vanilla's own LIGHT generation step does. Radius is bounded (8
 *       chunks = 289) so a mistyped command cannot stall the server; the radius should cover the
 *       damaged area plus a couple of chunks of margin, because neighbours are what make edge
 *       light correct.</li>
 * </ul>
 *
 * <p>Requires permission level 2 (op). Marked here honestly: {@code stats} and {@code light} are
 * read-only and safe; {@code relight} recomputes light for loaded chunks only (unloaded ones are
 * skipped, so run it where the damage is visible).</p>
 */
@EventBusSubscriber(modid = "lucistarlink")
public final class LuciStarlinkCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("LuciStarlink");

    private LuciStarlinkCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("lucistarlink")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats").executes(context -> {
                    final CommandSourceStack source = context.getSource();
                    final StarLightInterface engine = engineOf(source.getLevel());
                    if (engine == null) {
                        source.sendFailure(Component.literal("LuciStarlink: this level does not run the ScalableLux light engine"));
                        return 0;
                    }
                    final String stats = "LuciStarlink " + engine.lucisStats()
                            + " batchLimit=" + Integer.getInteger("scalablelux.batchLimit", 1)
                            + " profile=" + LuxProfiler.enabled()
                            + " edit=" + LuxProfiler.ownEditCalls + "/" + LuxProfiler.ownEditBatched
                            + " fallback=" + LuxProfiler.ownEditFallback
                            + " rej=" + LuxProfiler.ownEditRejThread + "/" + LuxProfiler.ownEditRejChunk
                            + "/" + LuxProfiler.ownEditRejStatus
                            + " cb=" + LuxProfiler.checkBlockCalls
                            + " qt=" + LuxProfiler.queueTaskCalls
                            + "|notReady=" + LuxProfiler.queueTaskNotReady
                            + " inline=" + LuxProfiler.queueTaskInline
                            + " notSched=" + LuxProfiler.queueTaskNotScheduled
                            + " ticket=" + LuxProfiler.queueTaskTicketAdds
                            + " blkSkip=" + LuxProfiler.ownEditBlockSkipped
                            + " blkMs=" + (LuxProfiler.ownEditBlkNanos / 1_000_000L)
                            + " skyMs=" + (LuxProfiler.ownEditSkyNanos / 1_000_000L)
                            + " setup=" + (LuxProfiler.ownEditSetupNanos / 1_000_000L);
                    LOGGER.info(stats); // see the note in the light branch: functions suppress chat output
                    source.sendSuccess(() -> Component.literal(stats), false);
                    return 1;
                }))
                .then(Commands.literal("light")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            final CommandSourceStack source = context.getSource();
                            final ServerLevel level = source.getLevel();
                            final BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                            final LevelChunk chunk = level.getChunkAt(pos);
                            // The two layers this engine keeps, read side by side on purpose: "vis" is what the client
                            // is sent and what a save contains; "upd" is what the engine has computed but may not have
                            // promoted yet. A value in one and not the other says which half is at fault.
                            final int sectionIndex = (pos.getY() >> 4) - WorldUtil.getMinLightSection(level);
                            final int localIndex = (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
                            final ExtendedChunk extended = (ExtendedChunk) chunk;
                            final String layers = " updBlock=" + nibbleAt(extended.scalablelux$getBlockNibbles(), sectionIndex, localIndex)
                                    + " updSky=" + nibbleAt(extended.scalablelux$getSkyNibbles(), sectionIndex, localIndex)
                                    + " sable=" + SableCompat.isSablePlotChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
                            final String report = "LuciStarlink light " + pos.toShortString()
                                    + " block=" + level.getBrightness(LightLayer.BLOCK, pos)
                                    + " sky=" + level.getBrightness(LightLayer.SKY, pos)
                                    + " raw=" + level.getRawBrightness(pos, 0)
                                    + layers
                                    + " state=" + level.getBlockState(pos)
                                    + " lightCorrect=" + chunk.isLightCorrect();
                            // Also logged: inside a function (a datapack-driven diagnostic, for instance) chat output is
                            // suppressed, and the log is then the only place the reading can be seen at all.
                            LOGGER.info(report);
                            source.sendSuccess(() -> Component.literal(report), false);
                            return 1;
                        })))
                .then(Commands.literal("relight")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8)).executes(context -> {
                            final CommandSourceStack source = context.getSource();
                            final ServerLevel level = source.getLevel();
                            final StarLightInterface engine = engineOf(level);
                            if (engine == null) {
                                source.sendFailure(Component.literal("LuciStarlink: this level does not run the ScalableLux light engine"));
                                return 0;
                            }
                            final int radius = IntegerArgumentType.getInteger(context, "radius");
                            final BlockPos origin = BlockPos.containing(source.getPosition());
                            final int centerX = origin.getX() >> 4;
                            final int centerZ = origin.getZ() >> 4;

                            final List<LevelChunk> chunks = new ArrayList<>();
                            for (int dx = -radius; dx <= radius; dx++) {
                                for (int dz = -radius; dz <= radius; dz++) {
                                    final ChunkAccess chunk = level.getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                                    if (chunk instanceof LevelChunk full && full.isLightCorrect()) {
                                        chunks.add(full);
                                    }
                                }
                            }
                            if (chunks.isEmpty()) {
                                source.sendSuccess(() -> Component.literal("LuciStarlink: no loaded, light-correct chunk in radius "
                                        + radius + " around " + centerX + "," + centerZ + " - nothing to relight"), false);
                                return 1;
                            }
                            for (final LevelChunk chunk : chunks) {
                                chunk.setLightCorrect(false);
                            }
                            final ThreadedLevelLightEngine lightEngine =
                                    (ThreadedLevelLightEngine) level.getChunkSource().getLightEngine();
                            final CompletableFuture<?>[] futures = new CompletableFuture<?>[chunks.size()];
                            for (int i = 0; i < chunks.size(); i++) {
                                futures[i] = lightEngine.lightChunk(chunks.get(i), false);
                            }
                            final int count = chunks.size();
                            source.sendSuccess(() -> Component.literal("LuciStarlink: relighting " + count
                                    + " chunks around " + centerX + "," + centerZ), false);
                            CompletableFuture.allOf(futures).whenComplete((ignored, throwable) -> source.getServer().execute(() -> {
                                if (throwable != null) {
                                    source.sendFailure(Component.literal("LuciStarlink relight failed: " + throwable));
                                } else {
                                    source.sendSuccess(() -> Component.literal("LuciStarlink: re-lit " + count
                                            + " chunks around " + centerX + "," + centerZ), true);
                                }
                            }));
                            return 1;
                        })))
                .then(Commands.literal("bench")
                        .executes(context -> startBench(context.getSource(), 16, 3))
                        .then(Commands.argument("size", IntegerArgumentType.integer(4, 32))
                                .executes(context -> startBench(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "size"), 3))
                                .then(Commands.argument("passes", IntegerArgumentType.integer(1, 10))
                                        .executes(context -> startBench(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "size"),
                                                IntegerArgumentType.getInteger(context, "passes")))))));
        LOGGER.debug("Registered /lucistarlink (stats, light, relight, bench)");
    }

    /**
     * The in-game form of the workload the published table measures: fill a cube with light-emitting blocks, time the
     * changes, time how long the light takes to land, then put the world back exactly as it was.
     *
     * <p>It reports the same two axes the benchmark table carries, because those are the two a player can feel:</p>
     * <ul>
     *   <li><b>apply</b> - the changes themselves. This is mostly Minecraft's own work (state write, heightmap, dirty
     *       marking) and costs 600-775 ns per change for every engine measured, vanilla included, so it barely moves
     *       between engines.</li>
     *   <li><b>landed</b> - from the last change until the light engine has been idle for two consecutive ticks.
     *       This is what a player waits through, and it is the number that moves.</li>
     * </ul>
     *
     * <p>The default 16x16x16 is 4096 changes per pass - deliberately the same size as the {@code structure_cube}
     * workload in the README, so a number read here can be compared against that table's shape (not its absolute
     * values: a player's machine, a loaded machine and a dev client are all different rooms). The cube appears two
     * blocks above the player's feet, centred on them, so the light it produces is visible while it is there.</p>
     *
     * <p>Safety: the cube's blocks are snapshotted before the first pass and restored after every pass, so the world
     * is never left filled; size and pass count are bounded by the argument types; a cube that runs above the build
     * limit is shifted down rather than refused; and a run gives up after {@link Bench#MAX_WAIT_TICKS} ticks instead
     * of waiting forever if something else keeps the light engine busy.</p>
     */
    private static int startBench(final CommandSourceStack source, final int size, final int passes) {
        final ServerLevel level = source.getLevel();
        if (engineOf(level) == null) {
            source.sendFailure(Component.literal("LuciStarlink: this level does not run the ScalableLux light engine"));
            return 0;
        }
        new Bench(source, level, BlockPos.containing(source.getPosition()), size, passes).start();
        return 1;
    }

    /** The 18 the benchmark harness uses for its own workloads, so the two are the same operation. */
    private static final int BENCH_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final class Bench implements Runnable {

        /** A busy light engine elsewhere (another player, worldgen) must not park the command forever. */
        private static final int MAX_WAIT_TICKS = 200;
        /** Two quiet ticks in a row: a drained queue is not a finished engine. */
        private static final int QUIET_TICKS = 2;

        private final CommandSourceStack source;
        private final ServerLevel level;
        private final int size;
        private final int passes;
        private final int changes;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final BlockState[] snapshot;
        private final long[] applyNanos;
        private final long[] restoreNanos;
        private final int[] landedFill;
        private final int[] landedRestore;
        private final int centreSkyBefore;
        private final int centreBlockBefore;
        private final long ownBatchedBefore;
        private final long ownNanosBefore;

        private int pass;
        private int waited;
        private int quiet;
        private int skyAfterFill = -1;
        private int blockAfterFill = -1;
        private boolean waitingOnRestore;
        private long t0;

        private Bench(final CommandSourceStack source, final ServerLevel level, final BlockPos origin,
                      final int size, final int passes) {
            this.source = source;
            this.level = level;
            this.size = size;
            this.passes = passes;
            this.changes = size * size * size;
            // a cube that would stick out of the build limit moves down instead of being refused; the two blocks of
            // headroom put the cube in front of the player's eyes rather than around their feet, so running the
            // command shows them the light instead of trapping them inside a block of glowstone
            final int maxY = level.getMaxBuildHeight() - size;
            final int minY = level.getMinBuildHeight();
            this.originX = origin.getX() - (size >> 1);
            this.originY = Math.max(minY, Math.min(origin.getY() + 2, maxY));
            this.originZ = origin.getZ() - (size >> 1);
            this.snapshot = new BlockState[this.changes];
            this.applyNanos = new long[passes];
            this.restoreNanos = new long[passes];
            this.landedFill = new int[passes];
            this.landedRestore = new int[passes];
            final BlockPos centre = new BlockPos(this.originX + (size >> 1), this.originY + (size >> 1), this.originZ + (size >> 1));
            this.centreSkyBefore = level.getBrightness(LightLayer.SKY, centre);
            this.centreBlockBefore = level.getBrightness(LightLayer.BLOCK, centre);
            this.ownBatchedBefore = LuxProfiler.ownEditBatched;
            this.ownNanosBefore = LuxProfiler.ownEditNanos;
        }

        private void start() {
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int index = 0;
            for (int y = 0; y < this.size; y++) {
                for (int z = 0; z < this.size; z++) {
                    for (int x = 0; x < this.size; x++) {
                        this.snapshot[index++] = this.level.getBlockState(pos.set(
                                this.originX + x, this.originY + y, this.originZ + z));
                    }
                }
            }
            this.source.sendSuccess(() -> Component.literal("LuciStarlink bench: " + this.size + "^3 = " + this.changes
                    + " changes per pass, " + this.passes + " passes, cube at "
                    + this.originX + " " + this.originY + " " + this.originZ
                    + " (blocks are restored after every pass)"), false);
            this.ownPass();
        }

        private void ownPass() {
            this.t0 = System.nanoTime();
            final int changed = this.writeFill();
            this.applyNanos[this.pass] = System.nanoTime() - this.t0;
            this.waitingOnRestore = false;
            this.waited = 0;
            this.quiet = 0;
            this.schedule();
            if (changed != this.changes) {
                this.source.sendSuccess(() -> Component.literal("  (note: " + changed + " of " + this.changes
                        + " blocks accepted - part of the cube is in an unloaded chunk or already that block)"), false);
            }
        }

        @Override
        public void run() {
            this.waited++;
            if (this.level.getChunkSource().getLightEngine().hasLightWork()) {
                this.quiet = 0;
            } else {
                this.quiet++;
            }
            if (this.quiet < QUIET_TICKS) {
                if (this.waited >= MAX_WAIT_TICKS) {
                    this.source.sendFailure(Component.literal("LuciStarlink bench: gave up waiting for the light engine after "
                            + MAX_WAIT_TICKS + " ticks (something else keeps it busy); the cube has been restored"));
                    this.restoreNow();
                    return;
                }
                this.schedule();
                return;
            }
            if (!this.waitingOnRestore) {
                this.landedFill[this.pass] = this.waited;
                if (this.pass == 0) {
                    this.settledLight();
                }
                this.restoreNow();
                this.waitingOnRestore = true;
                this.waited = 0;
                this.quiet = 0;
                this.schedule();
                return;
            }
            this.landedRestore[this.pass] = this.waited;
            this.pass++;
            if (this.pass < this.passes) {
                this.ownPass();
            } else {
                this.report();
            }
        }

        private void restoreNow() {
            this.t0 = System.nanoTime();
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int index = 0;
            for (int y = 0; y < this.size; y++) {
                for (int z = 0; z < this.size; z++) {
                    for (int x = 0; x < this.size; x++) {
                        this.level.setBlock(pos.set(this.originX + x, this.originY + y, this.originZ + z),
                                this.snapshot[index++], BENCH_FLAGS);
                    }
                }
            }
            this.restoreNanos[this.pass] = System.nanoTime() - this.t0;
        }

        /** Fills the cube in one pass and returns how many blocks the world actually accepted. */
        private int writeFill() {
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            final BlockState glowstone = Blocks.GLOWSTONE.defaultBlockState();
            int changed = 0;
            for (int y = 0; y < this.size; y++) {
                for (int z = 0; z < this.size; z++) {
                    for (int x = 0; x < this.size; x++) {
                        if (this.level.setBlock(pos.set(this.originX + x, this.originY + y, this.originZ + z), glowstone, BENCH_FLAGS)) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }

        /** Read once the light has landed, so the reading is the settled one rather than the half-propagated one. */
        private void settledLight() {
            final BlockPos centre = this.centre();
            this.skyAfterFill = this.level.getBrightness(LightLayer.SKY, centre);
            this.blockAfterFill = this.level.getBrightness(LightLayer.BLOCK, centre);
        }

        private BlockPos centre() {
            return new BlockPos(this.originX + (this.size >> 1), this.originY + (this.size >> 1),
                    this.originZ + (this.size >> 1));
        }

        private void schedule() {
            final MinecraftServer server = this.source.getServer();
            server.tell(new TickTask(server.getTickCount() + 1, this));
        }

        private void report() {
            final long medianApply = median(this.applyNanos);
            final long medianRestore = median(this.restoreNanos);
            final BlockPos centre = this.centre();
            final String engine = LuxProfiler.enabled()
                    ? " | engine work " + fmt((LuxProfiler.ownEditNanos - this.ownNanosBefore) / 1.0e6) + " ms for "
                        + (LuxProfiler.ownEditBatched - this.ownBatchedBefore) + " batched changes"
                    : " | (start the game with -Dscalablelux.profile=true for the engine's own timings)";
            final String lightLine = "LuciStarlink bench light at centre: before sky=" + this.centreSkyBefore
                    + " block=" + this.centreBlockBefore
                    + " | while filled sky=" + this.skyAfterFill + " block=" + this.blockAfterFill
                    + " | after sky=" + this.level.getBrightness(LightLayer.SKY, centre)
                    + " block=" + this.level.getBrightness(LightLayer.BLOCK, centre) + engine;
            this.source.sendSuccess(() -> Component.literal("LuciStarlink bench result: apply median "
                    + fmt(medianApply / 1.0e6) + " ms (" + fmt(medianApply / this.changes / 1.0e3) + " us/change), light landed median "
                    + median(this.landedFill) + " tick(s)"), false);
            this.source.sendSuccess(() -> Component.literal("LuciStarlink bench restore: median "
                    + fmt(medianRestore / 1.0e6) + " ms, landed median " + median(this.landedRestore) + " tick(s)"), false);
            this.source.sendSuccess(() -> Component.literal(lightLine), false);
            LOGGER.info("LuciStarlink bench: size={} passes={} changes={} applyMs={} landedFill={} restoreMs={} landedRestore={}",
                    this.size, this.passes, this.changes, fmt(medianApply / 1.0e6), median(this.landedFill),
                    fmt(medianRestore / 1.0e6), median(this.landedRestore));
        }

        /** The median of the array's first {@code passes} entries, in nanoseconds (the array is sized exactly). */
        private static long median(final long[] values) {
            final long[] copy = Arrays.copyOf(values, values.length);
            Arrays.sort(copy);
            return copy[(copy.length + 1) / 2 - 1];
        }

        private static int median(final int[] values) {
            final int[] copy = Arrays.copyOf(values, values.length);
            Arrays.sort(copy);
            return copy[(copy.length + 1) / 2 - 1];
        }

        private static String fmt(final double value) {
            return String.format("%.2f", value);
        }
    }

    /** One cell out of a chunk's own nibble array, or {@code -} when that section has no storage at all. */
    private static String nibbleAt(final SWMRNibbleArray[] nibbles, final int sectionIndex, final int localIndex) {
        if (nibbles == null || sectionIndex < 0 || sectionIndex >= nibbles.length) {
            return "-";
        }
        final SWMRNibbleArray nibble = nibbles[sectionIndex];

        return nibble == null ? "null" : String.valueOf(nibble.getUpdating(localIndex));
    }

    private static StarLightInterface engineOf(final ServerLevel level) {
        if (level.getChunkSource().getLightEngine() instanceof StarLightLightingProvider provider) {
            return provider.scalablelux$getLightEngine();
        }
        return null;
    }
}
