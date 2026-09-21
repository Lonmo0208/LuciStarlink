package ca.spottedleaf.starlight.common.debug;

/**
 * Benchmark-side counters and timers for the "Lucis ideas on ScalableLux" branch.
 *
 * <p>Off unless {@code -Dscalablelux.profile=true}. Per-change timing is <b>sampled</b>
 * (1 in {@link #SAMPLE_SIZE}) and scaled when printed, so the instrument itself stays
 * below ~0.5% of a bulk workload's cost (a {@code System.nanoTime()} pair is ~25 ns and
 * a bulk pass is ~4000 changes; timing every call would distort the measurement it is
 * meant to explain). Counters are plain increments.</p>
 *
 * <p>The print interval is {@code -Dscalablelux.profileIntervalNanos} (default 2 s) and a
 * final line is printed from {@code close()}. Lines are prefixed {@code SLPROF} so the rig
 * can grep them out of the server log.</p>
 */
public final class LuxProfiler {

    private LuxProfiler() {}

    public static final int SAMPLE_SIZE = 16;
    private static final int SAMPLE_MASK = SAMPLE_SIZE - 1;

    private static final boolean ENABLED = Boolean.getBoolean("scalablelux.profile");
    private static final long PRINT_INTERVAL_NANOS =
            Long.getLong("scalablelux.profileIntervalNanos", 2_000_000_000L);

    // --- counters -----------------------------------------------------------
    public static long checkBlockCalls;
    public static long checkBlockSampled;
    public static long checkBlockSampledNanos;

    public static long queueTaskCalls;
    public static long queueTaskNotReady;      // chunk missing or not at LIGHT status
    public static long queueTaskInline;        // ran inline (non-full ticket / gen thread)
    public static long queueTaskRescheduled;   // bounced to the main thread
    public static long queueTaskNotScheduled;  // blockChange returned null
    public static long queueTaskAlreadyAdded;  // ticket already present
    public static long queueTaskTicketAdds;

    public static long sectionStatusCalls;
    public static long blockChangeCalls;

    public static long runLightUpdateCalls;
    public static long runLightUpdateNanos;
    public static long runLightUpdateHadWork;

    public static long lightChunkCalls;
    public static long lightChunkNanos;

    public static long sampleCounter;

    private static long lastPrint = System.nanoTime();

    public static boolean enabled() {
        return ENABLED;
    }

    /** True when this call should be timed (sampling); also bumps the sample counter. */
    public static boolean sample() {
        return (sampleCounter++ & SAMPLE_MASK) == 0;
    }

    public static long scale(final long sampledNanos) {
        return sampledNanos * SAMPLE_SIZE;
    }

    public static void maybePrint() {
        if (!ENABLED) {
            return;
        }
        final long now = System.nanoTime();
        if (now - lastPrint < PRINT_INTERVAL_NANOS) {
            return;
        }
        lastPrint = now;
        print();
    }

    public static void print() {
        if (!ENABLED) {
            return;
        }
        System.out.println("SLPROF"
                + " checkBlock=" + checkBlockCalls
                + " checkBlockSampled=" + checkBlockSampled
                + " checkBlockNanosEst=" + scale(checkBlockSampledNanos)
                + " queueTask=" + queueTaskCalls
                + " qNotReady=" + queueTaskNotReady
                + " qInline=" + queueTaskInline
                + " qResched=" + queueTaskRescheduled
                + " qNotSched=" + queueTaskNotScheduled
                + " qAlready=" + queueTaskAlreadyAdded
                + " qTicketAdds=" + queueTaskTicketAdds
                + " sectStatus=" + sectionStatusCalls
                + " blockChange=" + blockChangeCalls
                + " runUpdCalls=" + runLightUpdateCalls
                + " runUpdHadWork=" + runLightUpdateHadWork
                + " runUpdNanos=" + runLightUpdateNanos
                + " lightChunk=" + lightChunkCalls
                + " lightChunkNanos=" + lightChunkNanos);
    }
}
