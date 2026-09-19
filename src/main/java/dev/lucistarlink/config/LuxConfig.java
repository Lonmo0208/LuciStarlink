package dev.lucistarlink.config;

import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.light.LuxFlags;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = LuciStarlink.MODID)
public final class LuxConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable LuciStarlink light engine hooks")
            .define("enabled", true);

    private static final ModConfigSpec.IntValue REGION_CHUNKS = BUILDER
            .comment("Owned region size in chunks per axis, for both the world-generation and the runtime path.",
                    "Larger regions amortise bookkeeping over more chunks but make each job's working image",
                    "bigger and its latency longer; 1 is the measured default.")
            .defineInRange("regionChunks", 1, 1, 16);

    private static final ModConfigSpec.IntValue HALO_CHUNKS = BUILDER
            .comment("[世界生成路径 worldgen] 生成的区块向外多算几格光照，好让它能发布进已经加载的邻居。",
                    "**不要与 runtimeHaloChunks 混淆**：那个只管运行期（方块更新）作业，这个只管世界生成。",
                    "1 覆盖 15 格的光传播距离；填 0 会在区块边缘截断传播、留下跨区光缝，因此这条路径上按 1 处理",
                    "（旧版本写下的配置里 0 也出现过 —— 那时这个键其实没被读取，代码固定用 1）。",
                    "Read-only halo size in chunks for the world-generation image: how far beyond the owned",
                    "chunk a generated chunk computes light so that it can be published into an already-loaded",
                    "neighbour. Distinct from runtimeHaloChunks, which only affects runtime (block update) jobs.")
            .defineInRange("haloChunks", 1, 0, 2);

    private static final ModConfigSpec.IntValue RUNTIME_HALO_CHUNKS = BUILDER
            .comment("[运行期路径 runtime] 方块更新作业的光环大小。**不要与 haloChunks 混淆**：那个只管世界生成。",
                    "0 最快，但跨区传播会不足：作业的传播在区域边缘停住，原版会带进邻区的那部分光会一直缺着，",
                    "直到别的改动碰到那片区域。1 给每个作业一个区块（15 格）的光环 —— 正好是光传播距离 ——",
                    "代价是每个作业的工作映像更大。",
                    "Halo size in chunks for runtime (block update) jobs. 0 is fastest but leaves light",
                    "under-propagated across chunk borders; 1 gives every job a one chunk (15 block) halo,",
                    "exactly the light travel distance, at the cost of a larger working image per job.")
            .defineInRange("runtimeHaloChunks", 1, 0, 2);

    private static final ModConfigSpec.IntValue MAX_BATCH_CHUNKS = BUILDER
            .comment("Max chunks per runtime batch")
            .defineInRange("maxBatchChunks", 64, 1, 512);

    private static final ModConfigSpec.IntValue MAX_CACHED_REGIONS = BUILDER
            .comment("Max runtime-owned regions kept in memory")
            .defineInRange("maxCachedRegions", 128, 1, 4096);

    private static final ModConfigSpec.IntValue MAX_CACHED_REGION_MEGABYTES = BUILDER
            .comment("Memory budget for the cached light regions, in MiB.",
                    "One region costs about 4 * (regionChunks + 2*halo)^2 * 16 * dimensionHeight bytes,",
                    "so maxCachedRegions alone does not bound the heap: 128 one-chunk regions are ~48 MiB,",
                    "while the same entries with a halo or regionChunks=16 are several hundred MiB.")
            .defineInRange("maxCachedRegionMegabytes", 256, 16, 4096);

    private static final ModConfigSpec.BooleanValue ENABLE_WORLDGEN = BUILDER
            .comment("Replace worldgen light with LuciStarlink")
            .define("enableWorldgen", true);

    private static final ModConfigSpec.BooleanValue ENABLE_RUNTIME = BUILDER
            .comment("Replace block update light with LuciStarlink")
            .define("enableRuntime", true);

    private static final ModConfigSpec.BooleanValue ENABLE_SKY = BUILDER
            .comment("Compute skylight in LuciStarlink")
            .define("enableSky", true);

    private static final ModConfigSpec.BooleanValue ENABLE_BLOCK = BUILDER
            .comment("Compute block light in LuciStarlink")
            .define("enableBlock", true);

    private static final ModConfigSpec.BooleanValue VERBOSE_LOGGING = BUILDER
            .comment("Extra engine diagnostics")
            .define("verboseLogging", false);

    private static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("Enable debug-only metrics and diagnostics")
            .define("debug", false);

    private static final ModConfigSpec.BooleanValue SECTION_FAST_PATH = BUILDER
            .comment("Skip per-cell material extraction for homogeneous sections (air / fully opaque)")
            .define("experimentalSectionFastPath", true);

    private static final ModConfigSpec.BooleanValue SKY_SEED_SKIP = BUILDER
            .comment("Skip frontier seeding scans for sections that are uniformly lit skylight sources")
            .define("experimentalSkySeedSkip", true);

    private static final ModConfigSpec.BooleanValue DENSE_INCREMENTAL = BUILDER
            .comment("Keep dense record-backed batches incremental instead of promoting them to full region relights")
            .define("experimentalDenseIncremental", true);

    private static final ModConfigSpec.BooleanValue INLINE_RUNTIME = BUILDER
            .comment("Publish runtime light results directly from worker threads")
            .define("experimentalInlineRuntime", true);

    private static final ModConfigSpec.BooleanValue RUNTIME_ADOPTION = BUILDER
            .comment("Adopt engine-stored light at region init instead of recomputing it")
            .define("experimentalRuntimeAdoption", true);

    private static final ModConfigSpec.BooleanValue BOUNDARY_DELTAS = BUILDER
            .comment("Cross-region boundary light continuation (prototype, may oscillate)")
            .define("experimentalBoundaryDeltas", false);

    private static final ModConfigSpec.BooleanValue HALO_PUBLISH = BUILDER
            .comment("Publish the halo chunks' dirty sections so light computed across a region border reaches",
                    "the neighbouring chunk immediately. Disabling it restores the inherited behaviour where only",
                    "the owned chunk is published, which leaves a stale light seam at chunk borders until something",
                    "else touches the neighbouring region.")
            .define("haloPublish", true);

    private static final ModConfigSpec.BooleanValue FORCE_LIGHT_INCORRECT_ON_SAVE = BUILDER
            .comment("Force every chunk to be marked as light-not-correct when it is written to disk, so the",
                    "game relights it on load instead of trusting saved light. This is the conservative setting",
                    "(what ScalableLux ships) and also covers a chunk whose neighbour was generated after it was",
                    "saved; the cost is a relight of every chunk on load. Off (default) forces it only where the",
                    "engine still has queued or in-flight work for that chunk, which is the case where saved light",
                    "would otherwise come back with light permanently missing.")
            .define("forceLightIncorrectOnSave", false);

    private static final ModConfigSpec.BooleanValue WORLGEEN_HALO_PUBLISH = BUILDER
            .comment("Publish the halo chunks' dirty sections for worldgen relights. Off means a chunk generated next",
                    "to an already-loaded neighbour does not write the light it computed into that neighbour; the",
                    "neighbour then keeps its previous border light until it is relit (on its next load, or by",
                    "anything else touching that region), which can show as a seam on the border. In exchange the",
                    "worldgen path hands far fewer sections to the light engine. Runtime edits are unaffected and",
                    "keep publishing their halo either way.")
            .define("worldgenHaloPublish", true);

    private static final ModConfigSpec.BooleanValue DIRECT_SECTION_INSTALL = BUILDER
            .comment("Publish a computed section by writing it straight into the light engine's own section maps",
                    "instead of handing it over as queued section data. The hand-over route marks the engine",
                    "inconsistent, and its next light update pass then re-derives light from data that is already",
                    "final (plus a full section map copy per swap); the direct route leaves that pass nothing to do.",
                    "It also stops the engine from re-checking the handed-over sections, so a computed image that is",
                    "older than the engine's data for that section wins instead of being corrected - which is how it",
                    "diverges from vanilla block light during world generation. A sequential group measured",
                    "-31% on sky_hole once, but that did NOT reproduce in interleaved A/B (sky_hole -13%,",
                    "dense flat, block_toggle +4%, structure -3%; no workload significant), so the earlier gain was",
                    "group drift. Keep off. See docs/ARCH-V2-GLOBAL-STORAGE.md and docs/SUPERVISOR-NEXT-ROUND.md 10.3.")
            .define("directSectionInstall", true);

    private static final ModConfigSpec.BooleanValue PIGGYBACK_PUBLISH = BUILDER
            .comment("Schedule a computed section on the light engine's own task list instead of on a private",
                    "queue. The engine's runUpdate() then runs the write, its own light update pass and any wait",
                    "task a caller installed in one go, which removes a mailbox wakeup and the 250 us publish",
                    "coalescing delay. Off by default until it has the same measured backing as the direct install.")
            .define("piggybackPublish", false);

    private static final ModConfigSpec.BooleanValue PROMPT_RUNTIME_PUBLISH = BUILDER
            .comment("Hand a small runtime edit's publication to the light thread immediately instead of",
                    "through the publish coalescing timer. The timer batches worldgen publications usefully, but on",
                    "the latency path of a single edit it is a hop through the timer thread.",
                    "See docs/ARCH-V2-GLOBAL-STORAGE.md, stage 2.")
            .define("promptRuntimePublish", false);

    private static final ModConfigSpec.BooleanValue SYNC_RUNTIME_DRAIN = BUILDER
            .comment("At the end of a runtime tick that published something, wait (bounded) for the light thread to",
                    "commit it. The write still happens on the light thread; only the waiting moves, so a block",
                    "edit's light is final when the tick ends. See docs/HANDOVER.md, Unresolved 1.")
            .define("syncRuntimeDrain", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enabled = true;
    public static int regionChunks = 1;
    public static int haloChunks = 0;
    public static int runtimeHaloChunks = 1;
    public static int maxBatchChunks = 64;
    public static int maxCachedRegions = 128;
    public static long maxCachedRegionBytes = 256L * 1024L * 1024L;
    public static boolean enableWorldgen = true;
    public static boolean enableRuntime = true;
    public static boolean enableSky = true;
    public static boolean enableBlock = true;
    public static boolean verboseLogging = false;
    public static boolean debug = false;
    public static boolean experimentalSectionFastPath = true;
    public static boolean experimentalSkySeedSkip = true;
    public static boolean experimentalDenseIncremental = true;
    public static boolean experimentalInlineRuntime = true;
    public static boolean experimentalRuntimeAdoption = true;
    public static boolean experimentalBoundaryDeltas = false;
    public static boolean haloPublish = true;
    public static boolean forceLightIncorrectOnSave = false;
    public static boolean worldgenHaloPublish = true;
    public static boolean directSectionInstall = true;
    public static boolean piggybackPublish = false;
    public static boolean promptRuntimePublish = false;
    public static boolean syncRuntimeDrain = true;

    private LuxConfig() {
    }

    public static void register() {
        ModLoadingContext context = ModLoadingContext.get();
        ModContainer container = context.getActiveContainer();
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        applyOverrides();
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC
                && (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading)) {
            sync();
        }
    }

    private static void sync() {
        enabled = ENABLED.get();
        regionChunks = REGION_CHUNKS.get();
        haloChunks = HALO_CHUNKS.get();
        runtimeHaloChunks = RUNTIME_HALO_CHUNKS.get();
        maxBatchChunks = MAX_BATCH_CHUNKS.get();
        maxCachedRegions = MAX_CACHED_REGIONS.get();
        maxCachedRegionBytes = MAX_CACHED_REGION_MEGABYTES.get() * 1024L * 1024L;
        enableWorldgen = ENABLE_WORLDGEN.get();
        enableRuntime = ENABLE_RUNTIME.get();
        enableSky = ENABLE_SKY.get();
        enableBlock = ENABLE_BLOCK.get();
        verboseLogging = VERBOSE_LOGGING.get();
        debug = DEBUG.get();
        experimentalSectionFastPath = SECTION_FAST_PATH.get();
        experimentalSkySeedSkip = SKY_SEED_SKIP.get();
        experimentalDenseIncremental = DENSE_INCREMENTAL.get();
        experimentalInlineRuntime = INLINE_RUNTIME.get();
        experimentalRuntimeAdoption = RUNTIME_ADOPTION.get();
        experimentalBoundaryDeltas = BOUNDARY_DELTAS.get();
        haloPublish = HALO_PUBLISH.get();
        forceLightIncorrectOnSave = FORCE_LIGHT_INCORRECT_ON_SAVE.get();
        worldgenHaloPublish = WORLGEEN_HALO_PUBLISH.get();
        directSectionInstall = DIRECT_SECTION_INSTALL.get();
        piggybackPublish = PIGGYBACK_PUBLISH.get();
        promptRuntimePublish = PROMPT_RUNTIME_PUBLISH.get();
        syncRuntimeDrain = SYNC_RUNTIME_DRAIN.get();
        applyOverrides();
    }

    private static void applyOverrides() {
        enabled = overrideBoolean("lucistarlink.enabled", enabled);
        enableWorldgen = overrideBoolean("lucistarlink.enableWorldgen", enableWorldgen);
        enableRuntime = overrideBoolean("lucistarlink.enableRuntime", enableRuntime);
        enableSky = overrideBoolean("lucistarlink.enableSky", enableSky);
        enableBlock = overrideBoolean("lucistarlink.enableBlock", enableBlock);
        verboseLogging = overrideBoolean("lucistarlink.verboseLogging", verboseLogging);
        debug = overrideBoolean("lucistarlink.debug", debug);
        experimentalSectionFastPath = overrideBoolean("lucistarlink.experimentalSectionFastPath", experimentalSectionFastPath);
        experimentalSkySeedSkip = overrideBoolean("lucistarlink.experimentalSkySeedSkip", experimentalSkySeedSkip);
        experimentalDenseIncremental = overrideBoolean("lucistarlink.experimentalDenseIncremental", experimentalDenseIncremental);
        experimentalInlineRuntime = overrideBoolean("lucistarlink.experimentalInlineRuntime", experimentalInlineRuntime);
        experimentalRuntimeAdoption = overrideBoolean("lucistarlink.experimentalRuntimeAdoption", experimentalRuntimeAdoption);
        experimentalBoundaryDeltas = overrideBoolean("lucistarlink.experimentalBoundaryDeltas", experimentalBoundaryDeltas);
        haloPublish = overrideBoolean("lucistarlink.haloPublish", haloPublish);
        forceLightIncorrectOnSave = overrideBoolean("lucistarlink.forceLightIncorrectOnSave", forceLightIncorrectOnSave);
        worldgenHaloPublish = overrideBoolean("lucistarlink.worldgenHaloPublish", worldgenHaloPublish);
        directSectionInstall = overrideBoolean("lucistarlink.directSectionInstall", directSectionInstall);
        piggybackPublish = overrideBoolean("lucistarlink.piggybackPublish", piggybackPublish);
        promptRuntimePublish = overrideBoolean("lucistarlink.promptRuntimePublish", promptRuntimePublish);
        syncRuntimeDrain = overrideBoolean("lucistarlink.syncRuntimeDrain", syncRuntimeDrain);
        LuxFlags.set(experimentalSectionFastPath, experimentalSkySeedSkip, experimentalDenseIncremental,
                experimentalInlineRuntime);
        LuxFlags.runtimeAdoption = experimentalRuntimeAdoption;
        LuxFlags.haloPublish = haloPublish;
        LuxFlags.worldgenHaloPublish = worldgenHaloPublish;
        LuxFlags.directSectionInstall = directSectionInstall;
        LuxFlags.piggybackPublish = piggybackPublish;
        LuxFlags.promptRuntimePublish = promptRuntimePublish;
        LuxFlags.syncRuntimeDrain = syncRuntimeDrain;
        LuxFlags.boundaryDeltas = experimentalBoundaryDeltas;
        regionChunks = overrideInt("lucistarlink.regionChunks", regionChunks);
        haloChunks = overrideInt("lucistarlink.haloChunks", haloChunks);
        runtimeHaloChunks = overrideInt("lucistarlink.runtimeHaloChunks", runtimeHaloChunks);
        maxBatchChunks = overrideInt("lucistarlink.maxBatchChunks", maxBatchChunks);
        maxCachedRegions = overrideInt("lucistarlink.maxCachedRegions", maxCachedRegions);
        maxCachedRegionBytes = overrideInt("lucistarlink.maxCachedRegionMegabytes", (int) (maxCachedRegionBytes >> 20))
                * 1024L * 1024L;
        if (maxCachedRegionBytes < 16L * 1024L * 1024L) {
            maxCachedRegionBytes = 16L * 1024L * 1024L;
        }
        runtimeHaloChunks = Math.max(0, Math.min(runtimeHaloChunks, 2));
        if (enableRuntime && runtimeHaloChunks == 0) {
            LuciStarlink.LOGGER.warn("LuciStarlink runtimeHaloChunks=0: cross-chunk light propagation is truncated at "
                    + "region borders (light vanilla would carry into the neighbouring chunk stays missing until that "
                    + "region is touched again). Set runtimeHaloChunks=1 for vanilla-equivalent borders.");
        }
    }

    private static boolean overrideBoolean(String key, boolean current) {
        String value = System.getProperty(key);
        return value == null ? current : Boolean.parseBoolean(value);
    }

    private static int overrideInt(String key, int current) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return current;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return current;
        }
    }
}
