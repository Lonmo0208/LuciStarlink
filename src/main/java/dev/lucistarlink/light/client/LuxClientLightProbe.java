package dev.lucistarlink.light.client;

import dev.lucistarlink.LuciStarlink;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端光照接管的探针与开关。
 *
 * <p>探针回答一个问题：客户端的原版光照引擎在我们接管之前到底做了多少事、花了多少时间。数据来源是
 * {@code LevelLightEngine} 上那几个「自己算」的入口（见 {@code ClientLightEngineProbeMixin}）：
 * <ul>
 *   <li>{@code queueSectionData} —— 服务端送来最终光照数据的次数（我们其实不需要再算的证据）；</li>
 *   <li>{@code checkBlock} / {@code propagateLightSources} / {@code runLightUpdates} —— 客户端自己触发的重算。</li>
 * </ul>
 *
 * <p>输出受 {@code -Dlucistarlink.clientProbe=true} 控制（默认关闭，连日志都不打），所以对玩家零影响。
 */
public final class LuxClientLightProbe {
    /** 统计输出开关；客户端探针专用，与基准测试的 debug 开关无关。 */
    public static final boolean ENABLED = Boolean.getBoolean("lucistarlink.clientProbe");

    private static final long REPORT_INTERVAL_NANOS = 30_000_000_000L;
    private static final AtomicLong checkBlock = new AtomicLong();
    private static final AtomicLong skippedCheckBlock = new AtomicLong();
    private static final AtomicLong skippedPropagate = new AtomicLong();
    private static final AtomicLong propagate = new AtomicLong();
    private static final AtomicLong queueSectionData = new AtomicLong();
    private static final AtomicLong runUpdatesCalls = new AtomicLong();
    private static final AtomicLong runUpdatesNanos = new AtomicLong();
    private static final ThreadLocal<Long> runUpdatesStartedAt = new ThreadLocal<>();
    private static volatile long lastReportNanos;

    private LuxClientLightProbe() {
    }

    public static void countCheckBlock() {
        if (ENABLED) {
            checkBlock.incrementAndGet();
            reportIfDue();
        }
    }

    /** 接管模式下被省掉的「本地方块变化重算」次数。 */
    public static void countSkippedCheckBlock() {
        if (ENABLED) {
            skippedCheckBlock.incrementAndGet();
            reportIfDue();
        }
    }

    /** 接管模式下被省掉的「按世界光源重推区块光照」次数。 */
    public static void countSkippedPropagate() {
        if (ENABLED) {
            skippedPropagate.incrementAndGet();
            reportIfDue();
        }
    }

    public static void countPropagate() {
        if (ENABLED) {
            propagate.incrementAndGet();
            reportIfDue();
        }
    }

    public static void countQueueSectionData() {
        if (ENABLED) {
            queueSectionData.incrementAndGet();
            reportIfDue();
        }
    }

    public static void beginRunLightUpdates() {
        if (ENABLED) {
            runUpdatesStartedAt.set(System.nanoTime());
        }
    }

    public static void endRunLightUpdates(int work) {
        if (!ENABLED) {
            return;
        }
        Long startedAt = runUpdatesStartedAt.get();
        if (startedAt != null) {
            runUpdatesNanos.addAndGet(System.nanoTime() - startedAt);
            runUpdatesStartedAt.remove();
        }
        runUpdatesCalls.incrementAndGet();
        reportIfDue();
    }

    /** 每 30 秒最多打一行，避免刷屏；只统计总数与累计耗时，不做任何推测。 */
    private static void reportIfDue() {
        long now = System.nanoTime();
        long last = lastReportNanos;
        if (now - last < REPORT_INTERVAL_NANOS || !lastReportNanosCas(last, now)) {
            return;
        }
        LuciStarlink.LOGGER.info(
                "LuciStarlink client probe: engine data from server {} section(s), client recompute: checkBlock {} "
                        + "propagateLightSources {} runLightUpdates {} taking {} ms total",
                queueSectionData.get(), checkBlock.get(), propagate.get(), runUpdatesCalls.get(),
                runUpdatesNanos.get() / 1_000_000L);
    }

    private static boolean lastReportNanosCas(long expected, long update) {
        // 只有一个线程需要打这行；失败就说明别的线程刚打过，直接跳过
        if (expected == lastReportNanos && REPORT_LOCK.compareAndSet(false, true)) {
            try {
                if (lastReportNanos == expected) {
                    lastReportNanos = update;
                    return true;
                }
                return false;
            } finally {
                REPORT_LOCK.set(false);
            }
        }
        return false;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean REPORT_LOCK =
            new java.util.concurrent.atomic.AtomicBoolean();
}
