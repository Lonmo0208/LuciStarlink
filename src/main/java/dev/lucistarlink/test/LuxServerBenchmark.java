package dev.lucistarlink.test;

import com.mojang.logging.LogUtils;
import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.config.LuxConfig;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.engine.LuxServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = LuciStarlink.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LuxServerBenchmark {
    /**
     * How long the pre-measure quiesce must stay clean before the measured passes may start.
     *
     * <p>The quiesce is a snapshot: it proceeds on the first poll with nothing pending. A chunk in the prepare
     * ring that is still being generated can finish a moment later and queue its light work, which then lands
     * inside the measured passes - the measured passes have measured 1.1-1.6 ms each with zero publish work of
     * their own in about a quarter of the runs, against 0.44-0.6 ms in the rest, while the world-generation work
     * total is the same in both. Requiring a clean window instead of a clean instant is how that race is removed.
     * 0 keeps the historical behaviour.
     */
    private static final long QUIESCE_SETTLE_NANOS =
            Math.max(0L, Long.getLong("lucistarlink.benchmark.quiesceSettleMs", 0L)) * 1_000_000L;

    /**
     * How many chunks beyond the workload box are force-loaded and prepared.
     *
     * <p>The measured passes wait on the engine's per-chunk pending tasks, and generating a chunk that borders a
     * measured chunk queues light work *for that measured chunk* (light crosses the border). A ring of one chunk
     * is therefore not enough: chunks at the ring's edge are still being generated while the passes run, and the
     * cascade keeps handing work to the measured chunks. Default 1 keeps the historical behaviour.
     */
    private static final int PREPARE_RING_CHUNKS = Math.max(0,
            Integer.getInteger("lucistarlink.benchmark.prepareRing", 1));

    /**
     * Whether a pass also waits for the light engine's GLOBAL queue to be empty.
     *
     * <p>Default true keeps the historical behaviour. Set
     * {@code -Dlucistarlink.benchmark.globalEngineBarrier=false} to settle on the measured box's own chunk futures
     * instead. Why it matters, measured 2026-09-22: on the ScalableLux line the engine's global queue also holds the
     * light work of chunks the server keeps generating in the background, so the barrier measures that backlog
     * rather than the edit's latency - a single-block edit (1 change per pass) cost the same ~14 ticks as 81
     * changes, and `bench.barrier.enginePending` was the only waiter that ever fired. 1.x is unaffected because its
     * engine lights generated chunks synchronously, so its queue is empty during the measured passes.
     */
    private static final boolean GLOBAL_ENGINE_BARRIER =
            !"false".equalsIgnoreCase(System.getProperty("lucistarlink.benchmark.globalEngineBarrier", "true"));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int FULL_CHUNK_TICKET_RADIUS = 1;
    private static final int PREPARE_TICKET_BUDGET_PER_TICK = 2048;
    private static final int PREPARE_POLL_BUDGET_PER_TICK = 2048;
    private static final TicketType<ChunkPos> BENCHMARK_TICKET_TYPE = TicketType.create("lucistarlink_revisited_benchmark", Comparator.comparingLong(ChunkPos::toLong));

    private static BenchmarkRun activeRun;

    private LuxServerBenchmark() {
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("lucistarlink.benchmark") || !LuxConfig.debug) {
            return;
        }
        BenchmarkConfig config = BenchmarkConfig.fromProperties();
        String expectedMod = config.expectedMod();
        if (!expectedMod.isBlank() && !ModList.get().isLoaded(expectedMod)) {
            throw new IllegalStateException("Lux benchmark expected mod is not loaded: " + expectedMod);
        }
        LOGGER.info("Lux light benchmark starting: mode={} workload={} radiusChunks={} chunkSpan={} origin=({}, {}, {})",
                config.mode(), config.workload(), config.radiusChunks(), config.chunkSpan(), config.originX(), config.y(), config.originZ());
        activeRun = new BenchmarkRun(event.getServer(), config);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        BenchmarkRun run = activeRun;
        if (run != null && run.server == event.getServer()) {
            run.tick();
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        if (activeRun != null && activeRun.server == event.getServer()) {
            activeRun.releaseTickets();
            activeRun = null;
        }
    }

    private static int applyPattern(ServerLevel level, BenchmarkConfig config, BlockState state) {
        return switch (config.workload()) {
            case "structure_cube" -> applyStructureCube(level, config, state, FLAGS);
            case "block_toggle_sparse" -> applySparsePattern(level, config, state, FLAGS);
            case "block_toggle_border" -> applyBorderPattern(level, config, state, FLAGS);
            case "block_toggle_column" -> applyColumnPattern(level, config, state, FLAGS);
            case "roof_toggle" -> applyRoofPattern(level, config, state, FLAGS);
            case "edge_toggle" -> applyEdgePattern(level, config, state, FLAGS);
            case "sky_hole" -> applySkyHolePattern(level, config, state, FLAGS);
            case "dense_chunk_patch" -> applyDenseChunkPatch(level, config, state, FLAGS);
            default -> applyPreparedPositions(level, config, state, FLAGS);
        };
    }

    private static int applyPreparationPattern(ServerLevel level, BenchmarkConfig config) {
        return switch (config.workload()) {
            case "structure_cube" -> applyStructureCube(level, config, Blocks.AIR.defaultBlockState(), FLAGS);
            case "sky_hole" -> applySkyHolePreparationPattern(level, config, Blocks.STONE.defaultBlockState(), FLAGS);
            default -> applyPattern(level, config, Blocks.STONE.defaultBlockState());
        };
    }

    /** Radius of the sky_hole patch: 0 -> 1 change, 1 -> 9, 2 -> 25 (the historical shape), n -> (2n+1)^2. */
    private static int skyHoleRadius() {
        return Math.max(0, Math.min(8, Integer.getInteger("lucistarlink.benchmark.skyHoleRadius", 2)));
    }

    /*
     * Per-pass window for the ScalableLux-side profiler. Reflection on purpose: the profiler lives in the other
     * mod's jar, which is not on this project's compile classpath, and in a run without it these calls must be
     * no-ops. Without per-pass scoping the engine counters are cumulative over the whole run, and a 484-chunk
     * worldgen phase (millions of sky writes) buries a measured window of a few milliseconds - which is what made
     * `block_toggle_border` unattributable.
     */
    private static java.lang.reflect.Method ENGINE_BEGIN_WINDOW;
    private static java.lang.reflect.Method ENGINE_WINDOW_SUMMARY;
    private static boolean ENGINE_HOOK_RESOLVED;

    private static void resolveEngineHook() {
        if (ENGINE_HOOK_RESOLVED) {
            return;
        }
        ENGINE_HOOK_RESOLVED = true;
        try {
            final Class<?> profiler = Class.forName("ca.spottedleaf.starlight.common.debug.LuxProfiler");
            ENGINE_BEGIN_WINDOW = profiler.getMethod("beginWindow");
            ENGINE_WINDOW_SUMMARY = profiler.getMethod("windowSummary");
        } catch (Throwable ignored) {
            ENGINE_BEGIN_WINDOW = null;
            ENGINE_WINDOW_SUMMARY = null;
        }
    }

    private static void engineWindowBegin() {
        resolveEngineHook();
        if (ENGINE_BEGIN_WINDOW == null) {
            return;
        }
        try {
            ENGINE_BEGIN_WINDOW.invoke(null);
        } catch (Throwable ignored) {
            // a profiler that cannot be driven must never fail the benchmark
        }
    }

    private static String engineWindowSummary() {
        resolveEngineHook();
        if (ENGINE_WINDOW_SUMMARY == null) {
            return null;
        }
        try {
            return (String) ENGINE_WINDOW_SUMMARY.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int applySkyHolePreparationPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int roofY = config.y() + 24;
        final int radius = skyHoleRadius();
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                pos.set(config.originX() + x, roofY, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int applyPreparedPositions(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 2) {
            for (int x = min; x < max; x += 2) {
                int y = config.y() + ((x * 31 + z * 17) & 15);
                pos.set(config.originX() + x, y, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static BlockState stateForPass(BenchmarkConfig config, int pass) {
        if ("structure_cube".equals(config.workload())) {
            return (pass & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (isSkyWorkload(config.workload())) {
            return (pass & 1) == 0 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        return (pass & 1) == 0 ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private static boolean isSkyWorkload(String workload) {
        return switch (workload) {
            case "roof_toggle", "edge_toggle", "sky_hole" -> true;
            default -> false;
        };
    }

    private static int applySparsePattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 8) {
            for (int x = min; x < max; x += 8) {
                int y = config.y() + ((x * 13 + z * 7) & 15);
                pos.set(config.originX() + x, y, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    /**
     * Optional differential-correctness probe: an inclusive box whose light (and block-state) content is hashed
     * once the measured passes are done. Two runs of different engines over the same fixed seed and the same edit
     * sequence must produce identical hashes; a mismatch is a correctness divergence, not a performance reading.
     *
     * <p>{@code -Dlucistarlink.benchmark.lightFingerprint="x1,y1,z1,x2,y2,z2"}. The block-state hash is the
     * control: if it differs the terrain differs (feature placement), and the light hashes are not comparable.</p>
     */
    private static final String LIGHT_FINGERPRINT_BOX =
            System.getProperty("lucistarlink.benchmark.lightFingerprint", "").trim();

    /** Optional full dump of the same box (sky bytes then block bytes, one byte per cell, y/z/x order)
     *  so two runs can be diffed cell by cell offline - a hash localises nothing, a dump does. */
    private static final String LIGHT_DUMP_PATH =
            System.getProperty("lucistarlink.benchmark.lightDump", "").trim();

    /** Optional reference dump to diff against in-run: reports where light differs AND whether the blocks
     *  differ there too, which is what separates an engine divergence from worldgen/terrain noise. */
    private static final String LIGHT_DIFF_PATH =
            System.getProperty("lucistarlink.benchmark.lightDiff", "").trim();

    /**
     * Takes the fingerprint only once two consecutive full passes over the box agree.
     *
     * <p>A single pass is not trustworthy: the engine can still have light in flight (its queue may drain
     * before its worker threads finish), which shows up as cells that are lit in the first read and dark in
     * the next. That is exactly how an earlier version of this probe reported a "+15 per chunk" sky-light
     * difference against vanilla that no dump could reproduce - an artefact of reading a moving target.
     * Two agreeing passes are the settle criterion; the pass count is logged so a slow settle is visible.</p>
     */
    private static void logLightFingerprint(final ServerLevel level) {
        // reference dump for the in-run diff, when one was given
        byte[] refSky = null;
        byte[] refBlock = null;
        int[] refStates = null;
        if (!LIGHT_DIFF_PATH.isEmpty()) {
            try {
                final Path refPath = Path.of(LIGHT_DIFF_PATH);
                refSky = Files.readAllBytes(refPath);
                refBlock = Files.readAllBytes(refPath.resolveSibling(refPath.getFileName() + ".block"));
                refStates = readIntArray(refPath.resolveSibling(refPath.getFileName() + ".states"));
                LOGGER.info("Lux light diff reference loaded from {} ({} cells)", LIGHT_DIFF_PATH, refSky.length);
            } catch (IOException exception) {
                LOGGER.error("Lux light diff reference could not be loaded from {}", LIGHT_DIFF_PATH, exception);
                refSky = null;
            }
        }
        String previous = null;
        for (int attempt = 1; attempt <= FINGERPRINT_MAX_PASSES; attempt++) {
            final String current = fingerprintOnce(level, false, refSky, refBlock, refStates);
            if (current == null) {
                return;
            }
            if (current.equals(previous)) {
                fingerprintOnce(level, true, refSky, refBlock, refStates); // settled: dump and diff from a stable read
                LOGGER.info("Lux light fingerprint settled after {} passes: {}", attempt, current);
                return;
            }
            previous = current;
            level.getChunkSource().getLightEngine().tryScheduleUpdate();
        }
        LOGGER.warn("Lux light fingerprint did not settle after {} passes, last: {}", FINGERPRINT_MAX_PASSES, previous);
    }

    private static final int FINGERPRINT_MAX_PASSES = 24;

    /** One full read of the box; returns the summary line, or null when the box is not configured. */
    private static String fingerprintOnce(final ServerLevel level, final boolean writeDump,
                                          final byte[] refSky, final byte[] refBlock, final int[] refStates) {
        if (LIGHT_FINGERPRINT_BOX.isEmpty()) {
            return null;
        }
        final String[] parts = LIGHT_FINGERPRINT_BOX.split(",");
        if (parts.length != 6) {
            LOGGER.error("Lux light fingerprint box must be x1,y1,z1,x2,y2,z2, got '{}'", LIGHT_FINGERPRINT_BOX);
            return null;
        }
        final int[] v = new int[6];
        for (int i = 0; i < 6; i++) {
            try {
                v[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException exception) {
                LOGGER.error("Lux light fingerprint box has a non-integer component: '{}'", LIGHT_FINGERPRINT_BOX);
                return null;
            }
        }
        final int minX = Math.min(v[0], v[3]);
        final int minY = Math.min(v[1], v[4]);
        final int minZ = Math.min(v[2], v[5]);
        final int maxX = Math.max(v[0], v[3]);
        final int maxY = Math.max(v[1], v[4]);
        final int maxZ = Math.max(v[2], v[5]);

        final LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        final LayerLightEventListener block = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        // per-chunk sky-light sums, so a cross-engine difference can be localised instead of only detected
        final int chunkCols = (maxX >> 4) - (minX >> 4) + 1;
        final int chunkRows = (maxZ >> 4) - (minZ >> 4) + 1;
        final long[] skySumPerChunk = new long[chunkCols * chunkRows];
        long skyHash = FNV_OFFSET;
        long blockHash = FNV_OFFSET;
        long stateHash = FNV_OFFSET;
        long cells = 0L;
        long skyNonZero = 0L;
        long skySum = 0L;
        long blockNonZero = 0L;
        long blockSum = 0L;
        long airCells = 0L;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    pos.set(x, y, z);
                    final int skyValue = sky.getLightValue(pos);
                    final int blockValue = block.getLightValue(pos);
                    final BlockState state = level.getBlockState(pos);
                    skyHash = fnv(skyHash, skyValue);
                    blockHash = fnv(blockHash, blockValue);
                    stateHash = fnv(stateHash, Block.getId(state));
                    if (skyValue != 0) {
                        skyNonZero++;
                        skySum += skyValue;
                        skySumPerChunk[((z >> 4) - (minZ >> 4)) * chunkCols + ((x >> 4) - (minX >> 4))] += skyValue;
                    }
                    if (blockValue != 0) {
                        blockNonZero++;
                        blockSum += blockValue;
                    }
                    if (state.isAir()) {
                        airCells++;
                    }
                    cells++;
                }
            }
        }
        int chunks = 0;
        int chunksNotLightCorrect = 0;
        for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
            for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
                final var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                chunks++;
                if (!chunk.isLightCorrect()) {
                    chunksNotLightCorrect++;
                }
            }
        }
        final String summary = "box=" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ
                + " cells=" + cells
                + " sky=" + Long.toHexString(skyHash)
                + " block=" + Long.toHexString(blockHash)
                + " states=" + Long.toHexString(stateHash)
                + " chunks=" + chunks
                + " chunksNotLightCorrect=" + chunksNotLightCorrect
                + " skyNonZero=" + skyNonZero
                + " skySum=" + skySum
                + " blockNonZero=" + blockNonZero
                + " blockSum=" + blockSum
                + " airCells=" + airCells;
        if (!writeDump) {
            return summary;
        }
        LOGGER.info("Lux light fingerprint {}", summary);
        final StringBuilder perChunk = new StringBuilder(chunkCols * chunkRows * 7);
        for (int i = 0; i < skySumPerChunk.length; i++) {
            if (i != 0) {
                perChunk.append(',');
            }
            perChunk.append(skySumPerChunk[i]);
        }
        LOGGER.info("Lux light fingerprint per-chunk skySum (z rows of {} x cols, chunk {},{} .. {},{}): {}",
                chunkCols, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4, perChunk);

        final int sizeX = maxX - minX + 1;
        final int sizeZ = maxZ - minZ + 1;
        final int sizeY = maxY - minY + 1;
        final byte[] skyBytes = new byte[sizeX * sizeZ * sizeY];
        final byte[] blockBytes = new byte[skyBytes.length];
        int cursor = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    pos.set(x, y, z);
                    skyBytes[cursor] = (byte) sky.getLightValue(pos);
                    blockBytes[cursor] = (byte) block.getLightValue(pos);
                    cursor++;
                }
            }
        }
        if (!LIGHT_DUMP_PATH.isEmpty()) {
            try {
                final Path path = Path.of(LIGHT_DUMP_PATH);
                Files.write(path, skyBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                Files.write(path.resolveSibling(path.getFileName() + ".block"), blockBytes,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                final java.nio.ByteBuffer states = java.nio.ByteBuffer.allocate(skyBytes.length * 4);
                cursor = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int x = minX; x <= maxX; x++) {
                            pos.set(x, y, z);
                            states.putInt(Block.getId(level.getBlockState(pos)));
                            cursor++;
                        }
                    }
                }
                Files.write(path.resolveSibling(path.getFileName() + ".states"), states.array(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                LOGGER.info("Lux light dump written: {} (sky/block/states), {} cells, box {} {} {} {} {} {}",
                        LIGHT_DUMP_PATH, skyBytes.length, minX, minY, minZ, maxX, maxY, maxZ);
            } catch (IOException exception) {
                LOGGER.error("Lux light dump failed for {}", LIGHT_DUMP_PATH, exception);
            }
        }
        if (refSky != null && refSky.length == skyBytes.length) {
            int skyDiff = 0;
            int blockDiff = 0;
            int stateDiff = 0;
            int lightDiffWithStateSame = 0;
            int shown = 0;
            final StringBuilder examples = new StringBuilder(512);
            cursor = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        pos.set(x, y, z);
                        final int refSkyValue = refSky[cursor] & 0xFF;
                        final int skyValue = skyBytes[cursor] & 0xFF;
                        final int refBlockValue = refBlock != null && cursor < refBlock.length ? refBlock[cursor] & 0xFF : -1;
                        final int blockValue = blockBytes[cursor] & 0xFF;
                        final int refState = refStates != null && cursor < refStates.length ? refStates[cursor] : Integer.MIN_VALUE;
                        final boolean stateDifferent = refState != Integer.MIN_VALUE && Block.getId(level.getBlockState(pos)) != refState;
                        final boolean skyDifferent = skyValue != refSkyValue;
                        final boolean blockDifferent = refBlockValue >= 0 && blockValue != refBlockValue;
                        if (skyDifferent) {
                            skyDiff++;
                        }
                        if (blockDifferent) {
                            blockDiff++;
                        }
                        if (stateDifferent) {
                            stateDiff++;
                        }
                        if ((skyDifferent || blockDifferent) && !stateDifferent) {
                            lightDiffWithStateSame++;
                        }
                        if ((skyDifferent || blockDifferent) && shown < 16) {
                            examples.append(" [(").append(x).append(',').append(y).append(',').append(z).append(") sky ")
                                    .append(refSkyValue).append("->").append(skyValue).append(" block ")
                                    .append(refBlockValue).append("->").append(blockValue)
                                    .append(stateDifferent ? " STATE-DIFFERS" : " state-same").append(']');
                            shown++;
                        }
                        cursor++;
                    }
                }
            }
            LOGGER.info("Lux light diff vs {}: skyDiff={} blockDiff={} stateDiff={} lightDiffWithStateSame={}{}",
                    LIGHT_DIFF_PATH, skyDiff, blockDiff, stateDiff, lightDiffWithStateSame, examples);
        }
        return summary;
    }

    private static int[] readIntArray(final Path path) throws IOException {
        final byte[] bytes = Files.readAllBytes(path);
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        final int[] ret = new int[bytes.length / 4];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = buffer.getInt();
        }
        return ret;
    }

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long fnv(final long hash, final int value) {
        return (hash ^ (value & 0xFFFFL)) * FNV_PRIME;
    }

    private static int applyBorderPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 2) {
            int y = config.y() + (z & 15);
            pos.set(config.originX(), y, config.originZ() + z);
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
        }
        for (int x = min; x < max; x += 2) {
            int y = config.y() + ((x * 3) & 15);
            pos.set(config.originX() + x, y, config.originZ());
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
        }
        return changes;
    }

    private static int applyColumnPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 8) {
            for (int x = min; x < max; x += 8) {
                for (int dy = 0; dy < 24; dy++) {
                    pos.set(config.originX() + x, config.y() + dy, config.originZ() + z);
                    if (level.setBlock(pos, state, flags)) {
                        changes++;
                    }
                }
            }
        }
        return changes;
    }

    private static int applyRoofPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        int roofY = config.y() + 20;
        for (int z = min; z < max; z++) {
            for (int x = min; x < max; x++) {
                pos.set(config.originX() + x, roofY, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int applyEdgePattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z++) {
            pos.set(config.originX() + 15, config.y() + (z & 7), config.originZ() + z);
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
            pos.set(config.originX() - 16, config.y() + (z & 7), config.originZ() + z);
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
        }
        return changes;
    }

    private static int applySkyHolePattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int roofY = config.y() + 24;
        final int radius = skyHoleRadius();
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                pos.set(config.originX() + x, roofY, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int applyDenseChunkPatch(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int dy = 0; dy < 8; dy++) {
                    pos.set(config.originX() + x, config.y() + dy, config.originZ() + z);
                    if (level.setBlock(pos, state, flags)) {
                        changes++;
                    }
                }
            }
        }
        return changes;
    }

    private static int applyStructureCube(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        int width = config.structureWidth();
        int height = config.structureHeight();
        int depth = config.structureDepth();
        long maxAttempts = config.maxApplyBlocks() <= 0L
                ? config.plannedChanges()
                : Math.min(config.plannedChanges(), config.maxApplyBlocks());
        long attempts = 0L;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LuxServices.controller().beginRuntimeBulkWrite();
        LuxServices.controller().recordRuntimeBulkBounds(level,
                config.originX(), config.originZ(), config.originX() + width, config.originZ() + depth,
                config.plannedChanges());
        if (config.lightOnly()) {
            LuxServices.controller().endRuntimeBulkWrite();
            return (int) Math.min(Integer.MAX_VALUE, config.plannedChanges());
        }
        try {
            outer:
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    for (int x = 0; x < width; x++) {
                        if (attempts++ >= maxAttempts) {
                            break outer;
                        }
                        pos.set(config.originX() + x, config.y() + y, config.originZ() + z);
                        if (level.setBlock(pos, state, flags)) {
                            changes++;
                        }
                    }
                }
            }
        } finally {
            LuxServices.controller().endRuntimeBulkWrite();
        }
        return changes;
    }

    private static void writeResult(MinecraftServer server, BenchmarkConfig config, BenchmarkStats stats) throws IOException {
        Path output = Path.of(config.output());
        if (!output.isAbsolute()) {
            output = server.getServerDirectory().resolve(output);
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{"
                + "\"label\":\"" + escape(config.label()) + "\","
                + "\"mode\":\"" + escape(config.mode()) + "\","
                + "\"workload\":\"" + escape(config.workload()) + "\","
                // the publish-path switches all change what is measured, so an independent reader of this file
                // must be able to tell them apart without trusting anyone's summary
                + "\"directSectionInstall\":" + LuxFlags.directSectionInstall + ","
                + "\"worldgenHaloPublish\":" + LuxFlags.worldgenHaloPublish + ","
                + "\"piggybackPublish\":" + LuxFlags.piggybackPublish + ","
                + "\"promptRuntimePublish\":" + LuxFlags.promptRuntimePublish + ","
                + "\"syncRuntimeDrain\":" + LuxFlags.syncRuntimeDrain + ","
                + "\"lucistarlinkEnabled\":" + LuxConfig.enabled + ","
                + "\"enableWorldgen\":" + LuxConfig.enableWorldgen + ","
                + "\"enableRuntime\":" + LuxConfig.enableRuntime + ","
                + "\"regionChunks\":" + LuxConfig.regionChunks + ","
                + "\"haloChunks\":" + LuxConfig.haloChunks + ","
                + "\"radiusChunks\":" + config.radiusChunks() + ","
                + "\"chunkSpan\":" + config.chunkSpan() + ","
                + "\"structureSize\":" + config.structureSize() + ","
                + "\"structureWidth\":" + config.structureWidth() + ","
                + "\"structureHeight\":" + config.structureHeight() + ","
                + "\"structureDepth\":" + config.structureDepth() + ","
                + "\"plannedChanges\":" + config.plannedChanges() + ","
                + "\"maxApplyBlocks\":" + config.maxApplyBlocks() + ","
                + "\"skipPreparation\":" + config.skipPreparation() + ","
                + "\"lightOnly\":" + config.lightOnly() + ","
                + "\"trackRegionAnchorsOnly\":" + config.trackRegionAnchorsOnly() + ","
                + "\"prepareMaxTicks\":" + config.prepareMaxTicks() + ","
                + "\"strictDrainBeforeMeasure\":" + config.strictDrainBeforeMeasure() + ","
                + "\"waitWorldgenDuringMeasured\":" + config.waitWorldgenDuringMeasured() + ","
                + "\"capped\":" + config.capped() + ","
                + "\"passes\":" + config.passes() + ","
                + "\"warmupPasses\":" + config.warmupPasses() + ","
                + "\"changes\":" + stats.measuredChanges() + ","
                + "\"nanos\":" + stats.measuredNanos() + ","
                + "\"millis\":" + stats.measuredNanos() / 1_000_000.0D + ","
                + "\"applyNanos\":" + stats.measuredApplyNanos() + ","
                + "\"applyMillis\":" + stats.measuredApplyNanos() / 1_000_000.0D + ","
                + "\"waitNanos\":" + stats.measuredWaitNanos() + ","
                + "\"waitMillis\":" + stats.measuredWaitNanos() / 1_000_000.0D + ","
                + "\"prepareLoadNanos\":" + stats.prepareLoadNanos() + ","
                + "\"prepareWaitNanos\":" + stats.prepareWaitNanos() + ","
                + "\"preMeasureQuiesceNanos\":" + stats.preMeasureQuiesceNanos() + ","
                + "\"measuredBarrierNanos\":" + stats.measuredBarrierNanos() + ","
                + "\"measuredBarrierCount\":" + stats.measuredBarrierCount() + ","
                + "\"measuredPasses\":" + stats.measuredPasses() + ","
                + "\"measuredChangesPerPass\":" + (stats.measuredPasses() == 0 ? 0.0D : (double) stats.measuredChanges() / stats.measuredPasses()) + ","
                + "\"minPassNanos\":" + stats.minPassNanosOrZero() + ","
                + "\"maxPassNanos\":" + stats.maxPassNanos() + ","
                + "\"projectedMillis\":" + stats.projectedMillis(config) + ","
                + "\"status\":\"" + escape(stats.status()) + "\","
                + "\"nsPerChangeApplyOnly\":" + (stats.measuredChanges() == 0 ? 0.0D : (double) stats.measuredApplyNanos() / stats.measuredChanges()) + ","
                + "\"nsPerChangeWaitOnly\":" + (stats.measuredChanges() == 0 ? 0.0D : (double) stats.measuredWaitNanos() / stats.measuredChanges()) + ","
                + "\"msPerMeasuredPass\":" + (stats.measuredPasses() == 0 ? 0.0D : stats.measuredNanos() / 1_000_000.0D / stats.measuredPasses()) + ","
                + "\"worldgenPendingBeforeMeasuredPass\":" + stats.worldgenPendingBeforeMeasuredPass() + ","
                + "\"runtimePendingBeforeMeasuredPass\":" + stats.runtimePendingBeforeMeasuredPass() + ","
                + "\"worldgenPendingAfterApply\":" + stats.worldgenPendingAfterApply() + ","
                + "\"runtimePendingAfterApply\":" + stats.runtimePendingAfterApply() + ","
                + "\"measuredWorldgenWaitEnabled\":" + config.waitWorldgenDuringMeasured() + ","
                + "\"nsPerChange\":" + (stats.measuredChanges() == 0 ? 0.0D : (double) stats.measuredNanos() / stats.measuredChanges())
                + "}";
        Files.writeString(output, json + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private enum Phase {
        PREPARE,
        WAIT_PREPARE_LIGHT,
        QUIESCE_BEFORE_MEASURE,
        START_PASS,
        WAIT_LIGHT,
        SETTLE_BEFORE_FINGERPRINT,
        COMPLETE
    }

    /**
     * Ticks to keep the world quiet before the differential fingerprint is taken.
     *
     * <p>"The light queue is empty" is not "the engine has settled": the threaded engines hand their
     * propagation to worker threads, so the queue can drain while the work is still in flight. The first
     * version of this probe took the fingerprint on the pass barrier and reported a +15-per-chunk sky-light
     * difference against vanilla that vanished (byte-identical dumps) once the world was given time to settle -
     * a measurement artefact, not an engine difference. Do not remove this settle window.</p>
     */
    private static final int FINGERPRINT_SETTLE_TICKS = 40;

    private enum WaitMode {
        FULL_DRAIN(true, true),
        RUNTIME_ONLY(true, false);

        private final boolean waitRuntime;
        private final boolean waitWorldgen;

        WaitMode(boolean waitRuntime, boolean waitWorldgen) {
            this.waitRuntime = waitRuntime;
            this.waitWorldgen = waitWorldgen;
        }
    }

    private static final class BenchmarkRun {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final BenchmarkConfig config;
        private final int totalPasses;
        private final int minChunkX;
        private final int maxChunkX;
        private final int minChunkZ;
        private final int maxChunkZ;
        private final ChunkPos[] prepareChunks;
        private final ChunkPos[] waitChunks;
        private final boolean[] preparedChunkLoaded;
        private CompletableFuture<?>[] waitFutures;
        private ChunkPos[] activeWaitChunks;
        private final long prepareStartNanos;
        private long prepareChunksLoadedNanos;
        private int prepareChanges;
        private int remainingPrepareChunks;
        private int prepareTicketIndex;
        private int preparePollIndex;
        private boolean prepareChunksLoaded;
        private Phase phase = Phase.PREPARE;
        private int pass;
        private int waitTicks;
        private int currentChanges;
        private long currentStartNanos;
        private long currentApplyNanos;
        private long waitStartNanos;
        private volatile long waitCompleteNanos;
        private volatile Throwable waitFailure;
        private CompletableFuture<Void> waitFuture;
        private WaitMode waitMode = WaitMode.FULL_DRAIN;
        private boolean preMeasureQuiesced;
        private long prepareLoadNanos;
        private long prepareWaitNanos;
        private long preMeasureQuiesceStartNanos;
        private long preMeasureQuiesceNanos;
        private long measuredBarrierStartNanos;
        private long measuredBarrierNanos;
        private long measuredNanos;
        private long measuredApplyNanos;
        private long measuredWaitNanos;
        private long minPassNanos = Long.MAX_VALUE;
        private long maxPassNanos;
        private int measuredChanges;
        private int measuredPasses;
        private int measuredBarrierCount;
        private int worldgenPendingBeforeMeasuredPass;
        private int runtimePendingBeforeMeasuredPass;
        private int worldgenPendingAfterApply;
        private int runtimePendingAfterApply;
        /** Snapshot taken just before a pass applies its changes, so the pass line can report what that pass cost. */
        private LuxBenchmarkSupport.Snapshot passStartSnapshot = LuxBenchmarkSupport.snapshot();
        /** Which other work was in flight when the pass began waiting (1=runtime, 2=worldgen, 4=engine queue). */
        private int passWaitPendingBits;
        /** Start of the current clean streak during the pre-measure settle window; 0 while not settling. */
        private long quiesceSettleStartNanos;
        /** The two candidate pass ends, so the pass line shows which one governed (µs from the pass start). */
        private long passEndFutureUs;
        private long passEndDrainUs;
        /** Our publish-side completion for the current pass; 0 until it completes. */
        private java.util.concurrent.CompletableFuture<Void> passFlushMarker;
        private volatile long passFlushMarkerNanos;

        private BenchmarkRun(MinecraftServer server, BenchmarkConfig config) {
            this.server = server;
            this.level = server.overworld();
            int originX = config.originX();
            int originZ = config.originZ();
            if (originX == 0 && originZ == 0) {
                BlockPos spawn = this.level.getSharedSpawnPos();
                originX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(spawn.getX()));
                originZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(spawn.getZ()));
            }
            this.config = config.withOrigin(originX, originZ);
            this.totalPasses = config.warmupPasses() + config.passes();
            int minX = this.config.originX() + this.config.minOffsetBlocks();
            int maxX = this.config.originX() + this.config.maxOffsetBlocksExclusive() - 1;
            int minZ = this.config.originZ() + this.config.minOffsetBlocks();
            int maxZ = this.config.originZ() + this.config.maxOffsetBlocksExclusive() - 1;
            this.minChunkX = minX >> 4;
            this.maxChunkX = maxX >> 4;
            this.minChunkZ = minZ >> 4;
            this.maxChunkZ = maxZ >> 4;
            this.prepareChunks = createChunkGrid(this.minChunkX - PREPARE_RING_CHUNKS, this.maxChunkX + PREPARE_RING_CHUNKS,
                    this.minChunkZ - PREPARE_RING_CHUNKS, this.maxChunkZ + PREPARE_RING_CHUNKS, 1);
            int runtimeRegionChunks = runtimeRegionChunks();
            int waitMinChunkX = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.minChunkX, runtimeRegionChunks) * runtimeRegionChunks
                    : this.minChunkX;
            int waitMaxChunkX = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.maxChunkX, runtimeRegionChunks) * runtimeRegionChunks
                    : this.maxChunkX;
            int waitMinChunkZ = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.minChunkZ, runtimeRegionChunks) * runtimeRegionChunks
                    : this.minChunkZ;
            int waitMaxChunkZ = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.maxChunkZ, runtimeRegionChunks) * runtimeRegionChunks
                    : this.maxChunkZ;
            int waitStepChunks = this.config.trackRegionAnchorsOnly() ? runtimeRegionChunks : 1;
            this.waitChunks = createChunkGrid(waitMinChunkX, waitMaxChunkX, waitMinChunkZ, waitMaxChunkZ, waitStepChunks);
            this.preparedChunkLoaded = new boolean[this.prepareChunks.length];
            this.waitFutures = new CompletableFuture<?>[0];
            this.remainingPrepareChunks = this.prepareChunks.length;
            this.prepareStartNanos = System.nanoTime();
            LuxBenchmarkSupport.reset();
            LOGGER.info("Lux light benchmark created: mode={} prepareChunks={} waitChunks={} chunkRange=[{}, {}]x[{}, {}]",
                    this.config.mode(), this.prepareChunks.length, this.waitChunks.length,
                    this.minChunkX, this.maxChunkX, this.minChunkZ, this.maxChunkZ);
        }

        /** Countdown for {@link #FINGERPRINT_SETTLE_TICKS}. */
        private int fingerprintSettleTicks;

        private void settleBeforeFingerprint() {
            // keep nudging the engine while we wait: a threaded engine may still have work in flight
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            if (--this.fingerprintSettleTicks > 0) {
                return;
            }
            logLightFingerprint(this.level);
            this.finish();
        }

        private void tick() {
            try {
                switch (this.phase) {
                    case PREPARE -> this.prepare();
                    case WAIT_PREPARE_LIGHT -> this.waitPrepareLight();
                    case QUIESCE_BEFORE_MEASURE -> this.waitPreMeasureQuiesce();
                    case START_PASS -> this.startPass();
                    case WAIT_LIGHT -> this.waitLight();
                    case SETTLE_BEFORE_FINGERPRINT -> this.settleBeforeFingerprint();
                    case COMPLETE -> {
                    }
                }
            } catch (Throwable throwable) {
                LOGGER.error("Lux light benchmark failed", throwable);
                this.finish();
            }
        }

        private void prepare() {
            if (!this.prepareChunksLoaded) {
                ChunkSource chunkSource = this.level.getChunkSource();
                int ticketBudget = Math.min(PREPARE_TICKET_BUDGET_PER_TICK, this.prepareChunks.length - this.prepareTicketIndex);
                while (ticketBudget > 0 && this.prepareTicketIndex < this.prepareChunks.length) {
                    ChunkPos chunkPos = this.prepareChunks[this.prepareTicketIndex++];
                    this.level.getChunkSource().addRegionTicket(BENCHMARK_TICKET_TYPE, chunkPos, FULL_CHUNK_TICKET_RADIUS, chunkPos);
                    ticketBudget--;
                }

                int pollBudget = Math.min(PREPARE_POLL_BUDGET_PER_TICK, this.remainingPrepareChunks);
                int firstPendingIndex = -1;
                while (pollBudget > 0 && this.remainingPrepareChunks > 0) {
                    int pollIndex = this.preparePollIndex++;
                    if (this.preparePollIndex >= this.prepareChunks.length) {
                        this.preparePollIndex = 0;
                    }
                    if (this.preparedChunkLoaded[pollIndex]) {
                        continue;
                    }
                    ChunkPos chunkPos = this.prepareChunks[pollIndex];
                    // A chunk that merely exists can still be generating: an edit landing in one pays the
                    // ticket/load path instead of its own light work, which is what made the measured pass wall
                    // bimodal (1..29 ticks for identical work, same engine - measured 2026-09-22). Require the
                    // chunk to be at LIGHT before it counts as prepared.
                    final var preparedChunk = chunkSource.getChunkNow(chunkPos.x, chunkPos.z);
                    if (preparedChunk != null && preparedChunk.getPersistedStatus().isOrAfter(ChunkStatus.LIGHT)) {
                        this.preparedChunkLoaded[pollIndex] = true;
                        this.remainingPrepareChunks--;
                    } else if (firstPendingIndex < 0) {
                        firstPendingIndex = pollIndex;
                    }
                    pollBudget--;
                }

                if (this.remainingPrepareChunks > 0) {
                    if ((this.waitTicks++ & 31) == 0) {
                        int pendingIndex = firstPendingIndex >= 0 ? firstPendingIndex : this.firstPendingPrepareChunkIndex();
                        ChunkPos chunkPos = pendingIndex >= 0 ? this.prepareChunks[pendingIndex] : this.prepareChunks[Math.min(this.preparePollIndex, this.prepareChunks.length - 1)];
                        LOGGER.info("Lux light benchmark prepare waiting: mode={} ticketed={}/{} loaded={}/{} pending={} at ({}, {})",
                                this.config.mode(), this.prepareTicketIndex, this.prepareChunks.length,
                                this.prepareChunks.length - this.remainingPrepareChunks, this.prepareChunks.length,
                                this.remainingPrepareChunks, chunkPos.x, chunkPos.z);
                    }
                    if (this.config.prepareMaxTicks() > 0 && this.waitTicks > this.config.prepareMaxTicks()) {
                        LOGGER.error("Lux light benchmark prepare timed out: mode={} workload={} pending={} ticks={}",
                                this.config.mode(), this.config.workload(), this.remainingPrepareChunks, this.waitTicks);
                        this.writeAndFinish("prepare_timeout", 0, System.nanoTime() - this.prepareStartNanos);
                    }
                    return;
                }
                this.prepareChunksLoaded = true;
                long prepareEndNanos = System.nanoTime();
                this.prepareChunksLoadedNanos = prepareEndNanos;
                LOGGER.info("Lux light benchmark prepare chunks loaded: mode={} prepareChunks={} waitChunks={} elapsedMs={}",
                        this.config.mode(), this.prepareChunks.length, this.waitChunks.length,
                        (prepareEndNanos - this.prepareStartNanos) / 1_000_000L);
                LuxBenchmarkSupport.logResult("server_chunk_load_" + this.config.workload(), this.prepareChunks.length,
                        this.prepareStartNanos, prepareEndNanos);
                LuxBenchmarkSupport.reset();
                this.waitTicks = 0;
            }
            if (this.config.skipPreparation()) {
                this.prepareChanges = 0;
            } else {
                this.prepareChanges = applyPreparationPattern(this.level, this.config);
            }
            this.beginLightWait(WaitMode.FULL_DRAIN, this.prepareChunks);
            this.phase = Phase.WAIT_PREPARE_LIGHT;
        }

        private void waitPrepareLight() {
            if (!this.waitForLight(true)) {
                return;
            }
            if (this.waitFailure != null) {
                LOGGER.error("Lux light benchmark prepare wait failed mode={}", this.config.mode(), this.waitFailure);
                this.finish();
                return;
            }
            long completedNanos = this.waitCompleteNanos == 0L ? System.nanoTime() : this.waitCompleteNanos;
            this.prepareLoadNanos = Math.max(0L, this.prepareChunksLoadedNanos - this.prepareStartNanos);
            this.prepareWaitNanos = Math.max(0L, completedNanos - this.waitStartNanos);
            LOGGER.info("Lux light benchmark prepare complete: mode={} waitTicks={} changes={} loadMs={} lightWaitMs={} totalMs={}",
                    this.config.mode(), this.waitTicks, this.prepareChanges,
                    this.prepareLoadNanos / 1_000_000L,
                    this.prepareWaitNanos / 1_000_000L,
                    (completedNanos - this.prepareStartNanos) / 1_000_000L);
            LuxBenchmarkSupport.record("bench.prepare_light_wait", this.prepareWaitNanos);
            LuxBenchmarkSupport.logResult("server_chunk_prepare_" + this.config.workload(), this.prepareChunks.length,
                    this.prepareStartNanos, completedNanos);
            this.waitTicks = 0;
            LuxBenchmarkSupport.reset();
            if (this.config.strictDrainBeforeMeasure() && this.config.warmupPasses() == 0) {
                this.beginPreMeasureQuiesce();
            } else {
                this.phase = Phase.START_PASS;
            }
        }

        private void beginLightWait(WaitMode mode, ChunkPos[] chunks) {
            if (this.waitFutures.length != chunks.length) {
                this.waitFutures = new CompletableFuture<?>[chunks.length];
            }
            this.waitMode = mode;
            this.activeWaitChunks = chunks;
            this.passWaitPendingBits = pendingFlagBits();
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            for (int index = 0; index < chunks.length; index++) {
                ChunkPos chunkPos = chunks[index];
                this.waitFutures[index] = this.level.getChunkSource().getLightEngine().waitForPendingTasks(chunkPos.x, chunkPos.z);
            }
            this.waitTicks = 0;
            this.waitFailure = null;
            this.waitCompleteNanos = 0L;
            this.waitStartNanos = System.nanoTime();
            this.waitFuture = CompletableFuture.allOf(this.waitFutures).whenComplete((ignored, throwable) -> {
                this.waitCompleteNanos = System.nanoTime();
                this.waitFailure = throwable;
            });
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            LOGGER.info("Lux light benchmark light wait started: mode={} pass={} phase={} waitMode={} futures={}",
                    this.config.mode(), this.pass, this.phase, this.waitMode, this.waitFutures.length);
        }

        private boolean waitForLight(boolean prepare) {
            CompletableFuture<Void> future = this.waitFuture;
            boolean runtimePending = this.waitMode.waitRuntime && LuxServices.controller().hasPendingRuntimeWork();
            boolean worldgenPending = this.shouldWaitWorldgen() && LuxServices.controller().hasPendingWorldgenWork();
            // generic barrier: also wait for the vanilla light engine to have no queued work, so a competing
            // engine (ScalableLux installs itself as the light engine) is measured with its async tasks settled
            // instead of excluding whatever spilled past the measurement window
            boolean enginePending = GLOBAL_ENGINE_BARRIER && this.level.getChunkSource().getLightEngine().hasLightWork();
            boolean futurePending = future != null && !future.isDone();
            LuxBenchmarkSupport.count("bench.barrier.poll");
            if (futurePending) {
                LuxBenchmarkSupport.count("bench.barrier.futurePending");
            }
            if (runtimePending) {
                LuxBenchmarkSupport.count("bench.barrier.runtimePending");
            }
            if (worldgenPending) {
                LuxBenchmarkSupport.count("bench.barrier.worldgenPending");
            }
            if (enginePending) {
                LuxBenchmarkSupport.count("bench.barrier.enginePending");
            }
            if (!futurePending && !runtimePending && !worldgenPending && !enginePending) {
                if (prepare && QUIESCE_SETTLE_NANOS > 0L) {
                    if (this.quiesceSettleStartNanos == 0L) {
                        this.quiesceSettleStartNanos = System.nanoTime();
                    } else if (System.nanoTime() - this.quiesceSettleStartNanos >= QUIESCE_SETTLE_NANOS) {
                        this.quiesceSettleStartNanos = 0L;
                        return true;
                    }
                    this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
                    this.waitTicks++;
                    return false;
                }
                return true;
            }
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            if (System.nanoTime() - this.waitStartNanos > this.config.maxWaitNanos()) {
                LOGGER.error("Lux light benchmark {}timed out after {} ms mode={} pass={}/{}",
                        prepare ? "prepare " : "", this.config.maxWaitNanos() / 1_000_000L,
                        this.config.mode(), this.pass + 1, this.totalPasses);
                this.finish();
                return false;
            }
            this.waitTicks++;
            if ((this.waitTicks & 31) == 0) {
                int pending = 0;
                String pendingChunk = "";
                for (int index = 0; index < this.waitFutures.length; index++) {
                    CompletableFuture<?> wait = this.waitFutures[index];
                    if (wait == null || wait.isDone()) {
                        continue;
                    }
                    pending++;
                    if (pendingChunk.isEmpty()) {
                        ChunkPos[] chunks = this.activeWaitChunks == null ? this.waitChunks : this.activeWaitChunks;
                        ChunkPos chunkPos = chunks[index];
                        pendingChunk = chunkPos.x + "," + chunkPos.z;
                    }
                }
                LOGGER.info("Lux light benchmark still waiting: mode={} phase={} pass={}/{} waitMode={} waitTicks={} pendingFutures={}{}",
                        this.config.mode(), this.phase, this.pass + 1, this.totalPasses, this.waitMode, this.waitTicks, pending,
                        (pendingChunk.isEmpty() ? "" : " firstPending=" + pendingChunk)
                                + (runtimePending ? " runtimePending=true" : "")
                                + (worldgenPending ? " worldgenPending=true" : ""));
            }
            return false;
        }

        private boolean shouldWaitWorldgen() {
            return this.waitMode.waitWorldgen || (this.phase == Phase.WAIT_LIGHT && this.config.waitWorldgenDuringMeasured());
        }

        private void beginPreMeasureQuiesce() {
            this.preMeasureQuiesceStartNanos = System.nanoTime();
            this.beginLightWait(WaitMode.FULL_DRAIN, this.prepareChunks);
            this.phase = Phase.QUIESCE_BEFORE_MEASURE;
        }

        private void beginMeasuredBarrierDrain() {
            this.measuredBarrierStartNanos = System.nanoTime();
            this.measuredBarrierCount++;
            this.beginLightWait(WaitMode.FULL_DRAIN, this.prepareChunks);
            this.phase = Phase.QUIESCE_BEFORE_MEASURE;
        }

        private void waitPreMeasureQuiesce() {
            if (!this.waitForLight(false)) {
                return;
            }
            if (this.waitFailure != null) {
                LOGGER.error("Lux light benchmark pre-measure quiesce failed mode={}", this.config.mode(), this.waitFailure);
                this.finish();
                return;
            }
            long completedNanos = this.waitCompleteNanos == 0L ? System.nanoTime() : this.waitCompleteNanos;
            boolean measuredBarrier = this.measuredBarrierStartNanos != 0L;
            long drainNanos;
            if (this.measuredBarrierStartNanos != 0L) {
                drainNanos = Math.max(0L, completedNanos - this.measuredBarrierStartNanos);
                this.measuredBarrierNanos += drainNanos;
                this.measuredBarrierStartNanos = 0L;
            } else {
                drainNanos = Math.max(0L, completedNanos - this.preMeasureQuiesceStartNanos);
                this.preMeasureQuiesceNanos += drainNanos;
            }
            this.preMeasureQuiesced = true;
            this.waitTicks = 0;
            LuxBenchmarkSupport.reset();
            LOGGER.info("Lux light benchmark {} complete: mode={} elapsedMs={}",
                    measuredBarrier ? "measured barrier drain" : "pre-measure quiesce",
                    this.config.mode(), drainNanos / 1_000_000L);
            this.phase = Phase.START_PASS;
        }

        private void startPass() {
            if (this.pass >= this.totalPasses) {
                try {
                    BenchmarkStats stats = this.stats("ok", this.measuredChanges, this.measuredNanos);
                    writeResult(this.server, this.config, stats);
                    LOGGER.info("Lux light benchmark complete: mode={} changes={} measuredMs={} nsPerChange={}",
                            this.config.mode(), stats.measuredChanges(), stats.measuredNanos() / 1_000_000L,
                            stats.measuredChanges() == 0 ? 0L : stats.measuredNanos() / stats.measuredChanges());
                    LuxBenchmarkSupport.logResult("server_light_" + this.config.workload(),
                            (int) Math.min(Integer.MAX_VALUE, stats.measuredChanges()), 0L, stats.measuredNanos());
                    if (LIGHT_FINGERPRINT_BOX.isEmpty()) {
                        logLightFingerprint(this.level);
                    } else {
                        // let the light engine settle before hashing: a drained queue is not a finished engine
                        this.fingerprintSettleTicks = FINGERPRINT_SETTLE_TICKS;
                        this.phase = Phase.SETTLE_BEFORE_FINGERPRINT;
                        return;
                    }
                } catch (IOException exception) {
                    LOGGER.error("Failed to write Lux light benchmark result", exception);
                }
                this.finish();
                return;
            }
            if (this.config.strictDrainBeforeMeasure()
                    && !this.preMeasureQuiesced
                    && this.pass >= this.config.warmupPasses()) {
                this.beginPreMeasureQuiesce();
                return;
            }
            if (this.config.strictDrainBeforeMeasure()
                    && this.pass >= this.config.warmupPasses()
                    && (LuxServices.controller().hasPendingWorldgenWork()
                    || LuxServices.controller().hasPendingRuntimeWork())) {
                this.beginMeasuredBarrierDrain();
                return;
            }
            if (this.pass >= this.config.warmupPasses()) {
                if (LuxServices.controller().hasPendingWorldgenWork()) {
                    this.worldgenPendingBeforeMeasuredPass++;
                }
                if (LuxServices.controller().hasPendingRuntimeWork()) {
                    this.runtimePendingBeforeMeasuredPass++;
                }
            }
            BlockState state = stateForPass(this.config, this.pass);
            this.passStartSnapshot = LuxBenchmarkSupport.snapshot();
            engineWindowBegin(); // per-pass scope for the ScalableLux-side profiler (no-op without that mod)
            this.currentStartNanos = System.nanoTime();            this.currentChanges = applyPattern(this.level, this.config, state);
            this.currentApplyNanos = System.nanoTime() - this.currentStartNanos;
            LuxBenchmarkSupport.record("bench.apply_pattern", this.currentApplyNanos);
            // our own publish-side completion for this pass, so the number measures this engine's work too
            this.passFlushMarkerNanos = 0L;
            this.passFlushMarker = LuxServices.controller().flushRuntimeAndMarker(this.minChunkX, this.minChunkZ);
            this.passFlushMarker.whenComplete((ignored, throwable) -> this.passFlushMarkerNanos = System.nanoTime());
            LOGGER.info("Lux light benchmark pass start {}/{} state={} changes={}",
                    this.pass + 1, this.totalPasses, state.getBlock().builtInRegistryHolder().key().location(), this.currentChanges);
            if (this.pass >= this.config.warmupPasses()) {
                if (LuxServices.controller().hasPendingWorldgenWork()) {
                    this.worldgenPendingAfterApply++;
                }
                if (LuxServices.controller().hasPendingRuntimeWork()) {
                    this.runtimePendingAfterApply++;
                }
            }
            this.beginLightWait(this.pass >= this.config.warmupPasses() ? WaitMode.RUNTIME_ONLY : WaitMode.FULL_DRAIN, this.waitChunks);
            this.phase = Phase.WAIT_LIGHT;
        }

        private void waitLight() {
            if (!this.waitForLight(false)) {
                return;
            }
            if (this.waitFailure != null) {
                LOGGER.error("Lux light benchmark wait failed on pass {}/{} mode={}",
                        this.pass + 1, this.totalPasses, this.config.mode(), this.waitFailure);
                this.finish();
                return;
            }
            long completedNanos = this.waitCompleteNanos;
            long endNanos = completedNanos == 0L ? System.nanoTime() : completedNanos;
            this.passEndFutureUs = (endNanos - this.currentStartNanos) / 1000L;
            // A pass is only complete when BOTH sides are done: the engine's own waitForPendingTasks timestamp
            // (which for a synchronous engine like ScalableLux is the whole story) and our publish-side marker
            // (which is the only thing that sees our light work at all - see LuxLightPublisher#lucistarlink$flushMarker).
            long markerNanos = this.passFlushMarkerNanos;
            if (markerNanos > endNanos) {
                endNanos = markerNanos;
            }
            this.passEndDrainUs = (endNanos - this.currentStartNanos) / 1000L;
            long elapsed = endNanos - this.currentStartNanos;
            long waitNanos = Math.max(0L, endNanos - this.waitStartNanos);
            LuxBenchmarkSupport.record("bench.wait_after_apply", waitNanos);
            // real wall of the pass (apply -> first barrier that passed), which unlike the future timestamp
            // above includes the server tick boundaries the light work had to cross
            LuxBenchmarkSupport.record("bench.pass_wall_actual", Math.max(0L, System.nanoTime() - this.currentStartNanos));
            final String engineWindow = engineWindowSummary();
            if (engineWindow != null) {
                LOGGER.info("LUCIS_BENCH_ENGINE pass={} measured={} {}", this.pass,
                        this.pass >= this.config.warmupPasses(), engineWindow);
            }
            LuxBenchmarkSupport.count("bench.pass_ticks", this.waitTicks);
            if (this.pass >= this.config.warmupPasses()) {
                this.measuredNanos += elapsed;
                this.measuredApplyNanos += this.currentApplyNanos;
                this.measuredWaitNanos += waitNanos;
                this.measuredChanges += this.currentChanges;
                this.measuredPasses++;
                this.minPassNanos = Math.min(this.minPassNanos, elapsed);
                this.maxPassNanos = Math.max(this.maxPassNanos, elapsed);
            }
            LOGGER.info("Lux light benchmark pass {}/{} state={} changes={} endFutureUs={} endDrainUs={} {} waitTicks={}",
                    this.pass + 1, this.totalPasses,
                    stateForPass(this.config, this.pass).getBlock().builtInRegistryHolder().key().location(),
                    this.currentChanges, this.passEndFutureUs, this.passEndDrainUs,
                    passDeltaLine(elapsed, this.currentApplyNanos, waitNanos), this.waitTicks);
            this.pass++;
            if (this.pass == this.config.warmupPasses()) {
                LuxBenchmarkSupport.reset();
                if (this.config.strictDrainBeforeMeasure()) {
                    this.beginPreMeasureQuiesce();
                    return;
                }
            }
            this.phase = Phase.START_PASS;
        }

        /**
         * Per-pass delta of the publish pipeline, in microseconds plus call/entry counts.
         *
         * <p>The phase totals cannot say why one pass costs twice what the next one does - whether it published
         * more, waited longer for the drain, or simply paid an extra drain round. Those are different problems
         * with different fixes, and the pass-to-pass spread is larger than the gap we are chasing, so it has to
         * be attributable before anything is optimised.
         */
        /** Which of the three queues had work when a pass began waiting; the bits are printed on the pass line. */
        private int pendingFlagBits() {
            int bits = 0;
            if (this.waitMode.waitRuntime && LuxServices.controller().hasPendingRuntimeWork()) {
                bits |= 1;
            }
            if (this.shouldWaitWorldgen() && LuxServices.controller().hasPendingWorldgenWork()) {
                bits |= 2;
            }
            if (this.level.getChunkSource().getLightEngine().hasLightWork()) {
                bits |= 4;
            }
            return bits;
        }

        private String passDeltaLine(long elapsedNanos, long applyNanos, long waitNanos) {
            LuxBenchmarkSupport.Snapshot end = LuxBenchmarkSupport.snapshot();
            LuxBenchmarkSupport.Snapshot start = this.passStartSnapshot;
            StringBuilder line = new StringBuilder(200);
            line.append("elapsedUs=").append(elapsedNanos / 1000L)
                    .append(" applyUs=").append(applyNanos / 1000L)
                    .append(" waitUs=").append(waitNanos / 1000L)
                    .append(" pend=")
                    .append((this.passWaitPendingBits & 1) != 0 ? 'r' : '-')
                    .append((this.passWaitPendingBits & 2) != 0 ? 'w' : '-')
                    .append((this.passWaitPendingBits & 4) != 0 ? 'e' : '-');
            String[][] metrics = {
                    {"drainUs", "lucistarlink.publish_batch.drain"},
                    {"queueLatUs", "lucistarlink.publish_batch.queueLatency"},
                    {"notifyUs", "lucistarlink.publish_batch.notify"},
                    {"runUpdUs", "lucistarlink.publish_batch.runLightUpdates"},
                    {"publishUs", "lucistarlink.publish_direct"},
                    {"notifyPostUs", "lucistarlink.publish.notify.post"},
                    // the concurrent background activity: a slow pass has to be attributable to one of these
                    {"wgLightUs", "lucistarlink.light_chunk"},
                    {"wgComputeUs", "lucistarlink.light_chunk.worker_compute"},
                    {"wgPubWaitUs", "lucistarlink.light_chunk.publish_wait"},
                    {"rtTickUs", "lucistarlink.runtime_tick"},
                    {"rtJobUs", "lucistarlink.stage.runtime.job.runtime"},
            };
            for (String[] entry : metrics) {
                LuxBenchmarkSupport.Metric before = start.metrics().get(entry[1]);
                LuxBenchmarkSupport.Metric after = end.metrics().get(entry[1]);
                long nanos = (after == null ? 0L : after.nanos()) - (before == null ? 0L : before.nanos());
                long calls = (after == null ? 0L : after.calls()) - (before == null ? 0L : before.calls());
                line.append(' ').append(entry[0]).append('=').append(nanos / 1000L).append('/').append(calls);
            }
            String[][] counters = {
                    {"drains", "lucistarlink.publish_batch.queued"},
                    {"tasks", "lucistarlink.publish_batch.tasks"},
                    {"sections", "lucistarlink.publish_direct.sections"},
                    {"notifies", "lucistarlink.publish.notify.posted"},
                    {"identical", "lucistarlink.publish.skippedIdentical.sections"},
                    {"qDuringDrain", "lucistarlink.publish_batch.queuedDuringDrain"},
                    {"wgChunks", "lucistarlink.light_chunk"},
            };
            for (String[] entry : counters) {
                long before = start.counters().getOrDefault(entry[1], 0L);
                long after = end.counters().getOrDefault(entry[1], 0L);
                line.append(' ').append(entry[0]).append('=').append(after - before);
            }
            return line.toString();
        }

        private BenchmarkStats stats(String status, long changes, long nanos) {
            return new BenchmarkStats(changes, nanos, this.measuredApplyNanos, this.measuredWaitNanos,
                    this.prepareLoadNanos, this.prepareWaitNanos, this.preMeasureQuiesceNanos,
                    this.measuredBarrierNanos, this.measuredBarrierCount,
                    this.measuredPasses, this.minPassNanos, this.maxPassNanos,
                    this.worldgenPendingBeforeMeasuredPass, this.runtimePendingBeforeMeasuredPass,
                    this.worldgenPendingAfterApply, this.runtimePendingAfterApply, status);
        }

        private void finish() {
            this.phase = Phase.COMPLETE;
            this.releaseTickets();
            activeRun = null;
            this.server.halt(false);
        }

        private void writeAndFinish(String status, long changes, long nanos) {
            try {
                writeResult(this.server, this.config, this.stats(status, changes, nanos));
            } catch (IOException exception) {
                LOGGER.error("Failed to write Lux light benchmark result", exception);
            }
            this.finish();
        }

        private void releaseTickets() {
            for (ChunkPos chunkPos : this.prepareChunks) {
                this.level.getChunkSource().removeRegionTicket(BENCHMARK_TICKET_TYPE, chunkPos, FULL_CHUNK_TICKET_RADIUS, chunkPos);
            }
        }

        private int firstPendingPrepareChunkIndex() {
            for (int index = 0; index < this.preparedChunkLoaded.length; index++) {
                if (!this.preparedChunkLoaded[index]) {
                    return index;
                }
            }
            return -1;
        }

        private static ChunkPos[] createChunkGrid(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, int stepChunks) {
            int step = Math.max(1, stepChunks);
            int chunkCount = ((maxChunkX - minChunkX) / step + 1) * ((maxChunkZ - minChunkZ) / step + 1);
            ChunkPos[] chunks = new ChunkPos[chunkCount];
            int index = 0;
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ += step) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX += step) {
                    chunks[index++] = new ChunkPos(chunkX, chunkZ);
                }
            }
            return chunks;
        }
    }

    private record BenchmarkConfig(String label, String mode, String workload, String expectedMod, String output, int radiusChunks, int chunkSpan,
                                   int structureSize, int structureWidth, int structureHeight, int structureDepth,
                                   long maxApplyBlocks, boolean skipPreparation, boolean lightOnly,
                                   boolean trackRegionAnchorsOnly, int prepareMaxTicks,
                                   boolean strictDrainBeforeMeasure, boolean waitWorldgenDuringMeasured,
                                   int passes, int warmupPasses, int originX, int originZ, int y, long maxWaitNanos) {
        private static BenchmarkConfig fromProperties() {
            int structureSize = intProperty("lucistarlink.benchmark.structureSize", 16);
            return new BenchmarkConfig(
                    stringProperty("lucistarlink.benchmark.label", ""),
                    stringProperty("lucistarlink.benchmark.mode", "lucistarlink"),
                    stringProperty("lucistarlink.benchmark.workload", "block_toggle_dense"),
                    stringProperty("lucistarlink.benchmark.expectedMod", ""),
                    stringProperty("lucistarlink.benchmark.output", "lucistarlink-light-benchmark.jsonl"),
                    intProperty("lucistarlink.benchmark.radiusChunks", 3),
                    intProperty("lucistarlink.benchmark.chunkSpan", 0),
                    structureSize,
                    intProperty("lucistarlink.benchmark.structureWidth", structureSize),
                    intProperty("lucistarlink.benchmark.structureHeight", structureSize),
                    intProperty("lucistarlink.benchmark.structureDepth", structureSize),
                    longProperty("lucistarlink.benchmark.maxApplyBlocks", 0L),
                    booleanProperty("lucistarlink.benchmark.skipPreparation", false),
                    booleanProperty("lucistarlink.benchmark.lightOnly", false),
                    booleanProperty("lucistarlink.benchmark.trackRegionAnchorsOnly", false),
                    intProperty("lucistarlink.benchmark.prepareMaxTicks", 0),
                    booleanProperty("lucistarlink.benchmark.strictDrainBeforeMeasure", true),
                    booleanProperty("lucistarlink.benchmark.waitWorldgenDuringMeasured", false),
                    intProperty("lucistarlink.benchmark.passes", 6),
                    intProperty("lucistarlink.benchmark.warmupPasses", 2),
                    intProperty("lucistarlink.benchmark.originX", 0),
                    intProperty("lucistarlink.benchmark.originZ", 0),
                    intProperty("lucistarlink.benchmark.y", 80),
                    longProperty("lucistarlink.benchmark.maxWaitNanos", 120_000_000_000L)
            );
        }

        private BenchmarkConfig withOrigin(int originX, int originZ) {
            return new BenchmarkConfig(label, mode, workload, expectedMod, output, radiusChunks, chunkSpan,
                    structureSize, structureWidth, structureHeight, structureDepth,
                    maxApplyBlocks, skipPreparation, lightOnly, trackRegionAnchorsOnly, prepareMaxTicks,
                    strictDrainBeforeMeasure, waitWorldgenDuringMeasured,
                    passes, warmupPasses,
                    originX, originZ, y, maxWaitNanos);
        }

        private int spanChunks() {
            return chunkSpan > 0 ? chunkSpan : radiusChunks * 2;
        }

        private int minOffsetBlocks() {
            if ("structure_cube".equals(workload)) {
                return 0;
            }
            return -(spanChunks() / 2) * 16;
        }

        private int maxOffsetBlocksExclusive() {
            if ("structure_cube".equals(workload)) {
                return Math.max(1, Math.max(structureWidth, structureDepth));
            }
            return minOffsetBlocks() + spanChunks() * 16;
        }

        private long plannedChanges() {
            if ("structure_cube".equals(workload)) {
                return (long) Math.max(1, structureWidth)
                        * Math.max(1, structureHeight)
                        * Math.max(1, structureDepth);
            }
            return 0L;
        }

        private boolean capped() {
            return maxApplyBlocks > 0L && plannedChanges() > maxApplyBlocks;
        }

        private static String stringProperty(String key, String fallback) {
            String value = System.getProperty(key);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static int intProperty(String key, int fallback) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static long longProperty(String key, long fallback) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static boolean booleanProperty(String key, boolean fallback) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Boolean.parseBoolean(value.trim());
        }
    }

    private record BenchmarkStats(long measuredChanges, long measuredNanos, long measuredApplyNanos,
                                  long measuredWaitNanos, long prepareLoadNanos, long prepareWaitNanos,
                                  long preMeasureQuiesceNanos, long measuredBarrierNanos,
                                  int measuredBarrierCount, int measuredPasses, long minPassNanos,
                                  long maxPassNanos, int worldgenPendingBeforeMeasuredPass,
                                  int runtimePendingBeforeMeasuredPass, int worldgenPendingAfterApply,
                                  int runtimePendingAfterApply, String status) {
        private double projectedMillis(BenchmarkConfig config) {
            if (measuredChanges <= 0L || config.plannedChanges() <= 0L || !config.capped()) {
                return 0.0D;
            }
            return measuredNanos / 1_000_000.0D * ((double) config.plannedChanges() / measuredChanges);
        }

        private long minPassNanosOrZero() {
            return minPassNanos == Long.MAX_VALUE ? 0L : minPassNanos;
        }
    }

    private static int runtimeRegionChunks() {
        return Math.max(1, Math.min(Integer.getInteger("lucistarlink.runtimeRegionChunks", 1), 16));
    }
}

