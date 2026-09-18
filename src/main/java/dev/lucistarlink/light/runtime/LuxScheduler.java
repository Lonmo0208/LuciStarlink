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
    private static final int MAX_QUEUED_JOBS = Math.max(4,
            Integer.getInteger("lucistarlink.runtime.maxQueuedJobs", 0) > 0
                    ? Integer.getInteger("lucistarlink.runtime.maxQueuedJobs")
                    : 32);

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
