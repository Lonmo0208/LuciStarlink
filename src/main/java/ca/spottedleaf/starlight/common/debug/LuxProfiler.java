package ca.spottedleaf.starlight.common.debug;

import ca.spottedleaf.starlight.common.config.Config;

import java.util.concurrent.atomic.LongAdder;

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

    private static final boolean ENABLED = Config.PROFILE;
    private static final long PRINT_INTERVAL_NANOS =
            Config.PROFILE_INTERVAL_NANOS;

    // --- counters -----------------------------------------------------------
    public static long checkBlockCalls;
    public static long checkBlockSampled;
    public static long checkBlockSampledNanos;

    public static long queueTaskCalls;
    public static long queueTaskSampled;
    public static long ownEditInline;
    public static long ownEditRejThread;
    public static long ownEditRejChunk;
    public static long ownEditRejStatus;
    public static long ownEditCalls;
    public static long ownEditBatched;
    public static long ownEditNanos;
    public static long ownEditSetupNanos;
    public static long ownEditWorkNanos;
    public static long ownEditPropagateNanos;
    public static long ownEditSkyNanos;
    public static long ownEditBlkNanos;
    public static long ownEditVisibleNanos;
    public static long ownEditFallback;
    public static long queueTaskSampledNanos;
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

    // --- M2-2a: propagation work, flushed from the pooled engine instances on release ---
    // "how much work does one batch of changes actually generate": tasks = per-chunk propagations,
    // positions = changed positions processed, writes = nibble cells actually written by setLightLevel.
    // writes/changes is the per-change propagation touch count the M2-2 decision rests on.
    public static final LongAdder skyTasks = new LongAdder();
    public static final LongAdder blockTasks = new LongAdder();
    public static final LongAdder skyPositions = new LongAdder();
    public static final LongAdder blockPositions = new LongAdder();
    public static final LongAdder skyWrites = new LongAdder();
    public static final LongAdder blockWrites = new LongAdder();
    public static final LongAdder skyQueueAdds = new LongAdder();
    public static final LongAdder blockQueueAdds = new LongAdder();

    // M2-2c: identical-content skip in SWMRNibbleArray.updateVisible()
    public static final LongAdder identicalSkips = new LongAdder();
    // M2-2c: sections actually notified to the client (one per layer, = packet mask bits)
    public static final LongAdder skyNotify = new LongAdder();
    public static final LongAdder blockNotify = new LongAdder();

    public static void flushPropagation(final boolean sky, final long tasks, final long positions, final long writes, final long queueAdds) {
        if (!ENABLED || (tasks | positions | writes | queueAdds) == 0L) {
            return;
        }
        (sky ? skyTasks : blockTasks).add(tasks);
        (sky ? skyPositions : blockPositions).add(positions);
        (sky ? skyWrites : blockWrites).add(writes);
        (sky ? skyQueueAdds : blockQueueAdds).add(queueAdds);
    }

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
        System.out.println(windowSummary());
    }

    /**
     * Zeroes every counter, so the next {@link #windowSummary()} describes one measurement window instead of the
     * whole run. The harness calls it at each measured pass boundary (reflectively, so a run without this mod is
     * unaffected): without it a measured window of a few ms is invisible next to a 484-chunk worldgen phase that
     * produces millions of sky writes, which is exactly what blocked attribution on `block_toggle_border`.
     */
    public static void beginWindow() {
        checkBlockCalls = 0;
        checkBlockSampled = 0;
        checkBlockSampledNanos = 0;
        queueTaskCalls = 0;
        queueTaskSampled = 0;
        ownEditInline = 0;
        ownEditRejThread = 0;
        ownEditRejChunk = 0;
        ownEditRejStatus = 0;
        ownEditCalls = 0;
        ownEditBatched = 0;
        ownEditNanos = 0;
        ownEditSetupNanos = 0;
        ownEditWorkNanos = 0;
        ownEditPropagateNanos = 0;
        ownEditSkyNanos = 0;
        ownEditBlkNanos = 0;
        ownEditVisibleNanos = 0;
        ownEditFallback = 0;
        queueTaskSampledNanos = 0;
        queueTaskNotReady = 0;
        queueTaskInline = 0;
        queueTaskRescheduled = 0;
        queueTaskNotScheduled = 0;
        queueTaskAlreadyAdded = 0;
        queueTaskTicketAdds = 0;
        sectionStatusCalls = 0;
        blockChangeCalls = 0;
        runLightUpdateCalls = 0;
        runLightUpdateNanos = 0;
        runLightUpdateHadWork = 0;
        lightChunkCalls = 0;
        lightChunkNanos = 0;
        skyTasks.reset();
        blockTasks.reset();
        skyPositions.reset();
        blockPositions.reset();
        skyWrites.reset();
        blockWrites.reset();
        skyQueueAdds.reset();
        blockQueueAdds.reset();
        identicalSkips.reset();
        skyNotify.reset();
        blockNotify.reset();
    }

    public static String windowSummary() {
        return "SLPROF"
                + " checkBlock=" + checkBlockCalls
                + " checkBlockSampled=" + checkBlockSampled
                + " checkBlockNanosEst=" + scale(checkBlockSampledNanos)
                + " queueTask=" + queueTaskCalls
                + " queueTaskNanosEst=" + scale(queueTaskSampledNanos)
                + " ownEditInline=" + ownEditInline
                + " ownEditBatched=" + ownEditBatched
                + " ownEditNanos=" + ownEditNanos
                + " setupNanos=" + ownEditSetupNanos
                + " workNanos=" + ownEditWorkNanos
                + " propNanos=" + ownEditPropagateNanos
                + " visNanos=" + ownEditVisibleNanos
                + " skyNanos=" + ownEditSkyNanos
                + " blkNanos=" + ownEditBlkNanos
                + " ownEditFallback=" + ownEditFallback
                + " ownEditCalls=" + ownEditCalls
                + " rejThread=" + ownEditRejThread
                + " rejChunk=" + ownEditRejChunk
                + " rejStatus=" + ownEditRejStatus
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
                + " lightChunkNanos=" + lightChunkNanos
                + " skyTasks=" + skyTasks.sum()
                + " skyPos=" + skyPositions.sum()
                + " skyWrites=" + skyWrites.sum()
                + " skyQAdds=" + skyQueueAdds.sum()
                + " blkTasks=" + blockTasks.sum()
                + " blkPos=" + blockPositions.sum()
                + " blkWrites=" + blockWrites.sum()
                + " blkQAdds=" + blockQueueAdds.sum()
                + " idSkip=" + identicalSkips.sum()
                + " skyNotif=" + skyNotify.sum()
                + " blkNotif=" + blockNotify.sum();
    }
}
