package dev.lucistarlink.light;

/**
 * Feature flags for the light-engine optimization paths, isolated from the NeoForge
 * config stack so engine code stays loadable in headless tests. Values are resolved
 * from system properties at class initialization and re-synced from {@code LuxConfig}
 * when the in-game config loads.
 *
 * Defaults reflect the validated state:
 * - sectionFastPath, skySeedSkip, denseIncremental: validated correctness (differential
 *   suite) and performance (end-to-end benchmarks); on by default.
 * - inlineRuntime: no regression measured in the benchmark harness and removes the
 *   cross-tick commit latency; on by default.
 * - runtimeAdoption: adoption-backed region init; on by default, falls back to full
 *   compute automatically whenever adoption is not possible.
 * - boundaryDeltas: cross-region boundary continuation prototype; OFF - the prototype
 *   oscillates for roof-crossing batches (see docs/light-engine-architecture.md).
 */
public final class LuxFlags {
    public static volatile boolean sectionFastPath =
            Boolean.parseBoolean(System.getProperty("lucistarlink.experimentalSectionFastPath", "true"));
    public static volatile boolean skySeedSkip =
            Boolean.parseBoolean(System.getProperty("lucistarlink.experimentalSkySeedSkip", "true"));
    public static volatile boolean denseIncremental =
            Boolean.parseBoolean(System.getProperty("lucistarlink.experimentalDenseIncremental", "true"));
    public static volatile boolean inlineRuntime =
            Boolean.parseBoolean(System.getProperty("lucistarlink.experimentalInlineRuntime", "true"));
    /** V3 M1：小批量改动走「同线程算完并写库、不等待」的新路径（默认关，先只计量不改变行为）。 */
    public static volatile boolean syncSmallEdits =
            Boolean.parseBoolean(System.getProperty("lucistarlink.syncSmallEdits", "false"));
    public static volatile boolean boundaryDeltas =
            Boolean.parseBoolean(System.getProperty("lucistarlink.experimentalBoundaryDeltas", "false"));
    public static volatile boolean runtimeAdoption =
            Boolean.parseBoolean(System.getProperty("lucistarlink.experimentalRuntimeAdoption", "true"));
    /**
     * Publishes the halo chunks' dirty sections, so light a job computed across a region border reaches the
     * neighbouring chunk immediately. Off is the inherited behaviour (only the owned chunk is published), which
     * leaves a stale seam at chunk borders until the neighbour region is touched again.
     */
    /** Lazy halo light: materialise halo light per job for the sections a change can reach instead of the whole image. */
    public static volatile boolean lazyHaloLight =
            Boolean.parseBoolean(System.getProperty("lucistarlink.lazyHaloLight", "true"));

    public static volatile boolean haloPublish =
            Boolean.parseBoolean(System.getProperty("lucistarlink.haloPublish", "true"));

    /**
     * Whether the worldgen relight publishes the halo chunks' dirty sections. The halo half of a worldgen
     * publish writes light into already-generated neighbours; those neighbours are relit on their own when the
     * generation pipeline reaches them, so the value only matters for a neighbour that was relit *before* this
     * chunk. Off means a border seam can survive until something relights the neighbour (the case
     * {@code forceLightIncorrectOnSave} covers), in exchange for not handing the neighbours' sections to the
     * engine at all.
     */
    public static volatile boolean worldgenHaloPublish =
            Boolean.parseBoolean(System.getProperty("lucistarlink.worldgenHaloPublish", "true"));

    /**
     * Publish a computed section straight into the light engine's section maps instead of handing it over as
     * queued section data. Measured faster (the engine's next light update pass has nothing to re-derive), but
     * it also removes the engine's re-check of the handed-over sections, so an image older than the engine's
     * data for that section wins instead of being corrected. Off by default: the worldgen path can produce
     * exactly that (a chunk's light is computed before the engine applies later block changes), and it was
     * measured to diverge from vanilla block light there. See docs/ARCH-V2-GLOBAL-STORAGE.md.
     */
    public static volatile boolean directSectionInstall =
            Boolean.parseBoolean(System.getProperty("lucistarlink.directSectionInstall", "true"));

    /**
     * Wake the runtime pipeline inside the tick that produced a block change instead of at the next
     * {@code tickRuntime}. Dispatch otherwise waits for {@code ServerChunkCache.tick}, which lands a full tick
     * (50 ms) after a change made later in the tick (measured: the pass's own publish 55 ms after it applied,
     * against ScalableLux's synchronous ~0.35 ms). The work itself is unchanged, only when it starts.
     */
    public static volatile boolean promptDispatch =
            Boolean.parseBoolean(System.getProperty("lucistarlink.promptDispatch", "false"));

    /**
     * Schedule publications on the light engine's own task list (with the engine's {@code runUpdate} doing the
     * write, its own light update pass and any wait task in one go) instead of through our private queue with
     * its coalescing delay. See {@code net.minecraft.server.level.LuciStarlinkLightEngineTaskAccess}.
     */
    public static volatile boolean piggybackPublish =
            Boolean.parseBoolean(System.getProperty("lucistarlink.piggybackPublish", "false"));

    /**
     * Hands a runtime publication (a small edit's result) to the light thread immediately instead of going
     * through the coalescing timer. The timer batches, but it also costs a hop through the timer thread on the
     * critical path of every small edit; worldgen keeps coalescing because there throughput matters more than
     * latency. Measured as the "prompt" half of the scheduling question - see docs/ARCH-V2-GLOBAL-STORAGE.md.
     */
    public static volatile boolean promptRuntimePublish =
            Boolean.parseBoolean(System.getProperty("lucistarlink.promptRuntimePublish", "false"));

    /**
     * Waits, at the end of a runtime tick that published something, for the light thread to commit it. The write
     * still happens on the light thread; only the waiting moves, so the light a block edit produced is final when
     * the tick ends instead of being committed after it.
     */
    public static volatile boolean syncRuntimeDrain =
            Boolean.parseBoolean(System.getProperty("lucistarlink.syncRuntimeDrain", "true"));

    private LuxFlags() {
    }

    public static void set(boolean sectionFastPath, boolean skySeedSkip, boolean denseIncremental, boolean inlineRuntime) {
        LuxFlags.sectionFastPath = sectionFastPath;
        LuxFlags.skySeedSkip = skySeedSkip;
        LuxFlags.denseIncremental = denseIncremental;
        LuxFlags.inlineRuntime = inlineRuntime;
    }

    public static void setAll(boolean sectionFastPath, boolean skySeedSkip, boolean denseIncremental,
                              boolean inlineRuntime, boolean runtimeAdoption) {
        set(sectionFastPath, skySeedSkip, denseIncremental, inlineRuntime);
        LuxFlags.runtimeAdoption = runtimeAdoption;
    }
}
