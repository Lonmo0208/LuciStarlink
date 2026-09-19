package dev.lucistarlink.light.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class LuxScheduler implements AutoCloseable {
    /**
     * Region jobs retain their change records and the chunks they were captured
     * against, so the queue is bounded and callers requeue when a submit is refused.
     */
    private static final int MIN_QUEUED_JOBS = 4;

    /**
     * 队列上限。**系统属性只读一次**（原来读了两次：一次判断、一次取值），而且配置值低于下限时**打一行警告**再抬到下限
     * —— 原来的写法是静默钳制，运维改了 {@code -Dlucistarlink.runtime.maxQueuedJobs=1} 却完全看不到它被忽略。
     */
    private static final int MAX_QUEUED_JOBS;

    static {
        int configured = Integer.getInteger("lucistarlink.runtime.maxQueuedJobs", 0);
        if (configured > 0 && configured < MIN_QUEUED_JOBS) {
            dev.lucistarlink.LuciStarlink.LOGGER.warn(
                    "LuciStarlink: maxQueuedJobs={} is below the minimum of {}, using {} instead",
                    configured, MIN_QUEUED_JOBS, MIN_QUEUED_JOBS);
        }
        MAX_QUEUED_JOBS = configured > 0 ? Math.max(MIN_QUEUED_JOBS, configured) : 32;
    }

    private final LinkedBlockingQueue<LuxJob> queue = new LinkedBlockingQueue<>(MAX_QUEUED_JOBS);
    private final ExecutorService workers;
    private final AtomicInteger activeJobs = new AtomicInteger();
    private volatile boolean running = true;

    public LuxScheduler(int workerCount) {
        int workerCountResolved = Math.max(1, workerCount);
        this.workers = Executors.newFixedThreadPool(workerCountResolved, new LuxThreadFactory());
        for (int i = 0; i < workerCountResolved; i++) {
            this.workers.execute(this::runLoop);
        }
    }

    public boolean submit(LuxJob job) {
        return running && queue.offer(job);
    }

    private void runLoop() {
        while (running) {
            try {
                LuxJob job = queue.take();
                activeJobs.incrementAndGet();
                try {
                    job.task().run();
                } catch (Throwable throwable) {
                    dev.lucistarlink.LuciStarlink.LOGGER.error("Lux worker job failed", throwable);
                } finally {
                    activeJobs.decrementAndGet();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean hasPendingWork() {
        return !queue.isEmpty() || activeJobs.get() > 0;
    }

    @Override
    public void close() {
        running = false;
        queue.clear();
        workers.shutdownNow();
    }

    private static final class LuxThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lucistarlink-worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
