package dev.lucistarlink.mixin;

import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.engine.LuxServices;
import dev.lucistarlink.light.runtime.LuxLightPublisher;
import dev.lucistarlink.light.runtime.LuxRelightResult;
import dev.lucistarlink.light.runtime.LuxSectionData;
import dev.lucistarlink.test.LuxBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin extends LevelLightEngine implements LuxLightPublisher {
    @Unique
    private static final long LUCIS_PUBLISH_COALESCE_NANOS =
            Math.max(0L, Long.getLong("lucistarlink.publishCoalesceNanos", 250_000L));

    @Unique
    private LightChunkGetter lucistarlink$chunkSource;
    @Unique
    private ChunkMap lucistarlink$chunkMap;
    @Unique
    private ProcessorMailbox<Runnable> lucistarlink$taskMailbox;
    @Unique
    private ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> lucistarlink$sorterMailbox;
    @Unique
    private final Object lucistarlink$publishLock = new Object();
    @Unique
    private final Deque<LuxQueuedLightTask> lucistarlink$pendingLightTasks = new ArrayDeque<>();
    @Unique
    private final ArrayDeque<LuxQueuedLightTask> lucistarlink$publishBatch = new ArrayDeque<>(1000);
    @Unique
    private final ArrayDeque<LuxLightNotification> lucistarlink$pendingLightNotifications = new ArrayDeque<>(1000);
    @Unique
    private final HashSet<LuxLightNotification> lucistarlink$pendingLightNotificationKeys = new HashSet<>(1000);
    @Unique
    private final AtomicBoolean lucistarlink$publishScheduled = new AtomicBoolean();
    @Unique
    private final java.util.concurrent.atomic.AtomicInteger lucistarlink$publishesInFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    @Unique
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> lucistarlink$pendingPublishes =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    @Unique
    private final java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Void>> lucistarlink$publishCompletions =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    @Unique
    private volatile long lucistarlink$batchFirstQueuedNanos;
    @Unique
    private final ThreadLocal<Long> lucistarlink$checkBlockStartedAt = new ThreadLocal<>();

    protected ThreadedLevelLightEngineMixin(LightChunkGetter chunkSource, boolean hasBlockLight, boolean hasSkyLight) {
        super(chunkSource, hasBlockLight, hasSkyLight);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lucistarlink$init(LightChunkGetter chunkSource, net.minecraft.server.level.ChunkMap chunkMap, boolean hasSkyLight,
                            net.minecraft.util.thread.ProcessorMailbox<Runnable> taskMailbox,
                            net.minecraft.util.thread.ProcessorHandle<net.minecraft.server.level.ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox,
                            CallbackInfo ci) {
        this.lucistarlink$chunkSource = chunkSource;
        this.lucistarlink$chunkMap = chunkMap;
        this.lucistarlink$taskMailbox = taskMailbox;
        this.lucistarlink$sorterMailbox = sorterMailbox;
    }

    @Inject(method = "lightChunk", at = @At("HEAD"), cancellable = true)
    private void lucistarlink$lightChunk(ChunkAccess chunk, boolean trustEdges, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!LuxServices.controller().shouldHandleWorldgen(lucistarlink$chunkSource, chunk)) {
            return;
        }

        long startedAt = LuxBenchmarkSupport.start();
        ChunkPos chunkPos = chunk.getPos();
        chunk.setLightCorrect(false);
        CompletableFuture<ChunkAccess> future = LuxServices.controller()
                .relightChunkAsync(lucistarlink$chunkSource, chunk, trustEdges)
                .thenCompose(result -> {
                    LuxBenchmarkSupport.recordSince("lucistarlink.light_chunk.compute", startedAt);
                    long publishStartedAt = LuxBenchmarkSupport.start();
                    CompletableFuture<Void> published = lucistarlink$publishAsync(result, chunkPos.x, chunkPos.z);
                    ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
                    return published.thenApply(ignored -> {
                        LuxBenchmarkSupport.recordSince("lucistarlink.light_chunk.publish_wait", publishStartedAt);
                        chunk.setLightCorrect(true);
                        LuxBenchmarkSupport.recordSince("lucistarlink.light_chunk", startedAt);
                        return chunk;
                    });
                });
        cir.setReturnValue(future);
    }

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void lucistarlink$checkBlock(BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!LuxServices.controller().shouldHandleBlockChange(lucistarlink$chunkSource, pos)) {
            return;
        }

        long startedAt = LuxBenchmarkSupport.start();
        LuxBenchmarkSupport.recordSince("lucistarlink.check_block", startedAt);
        ci.cancel();
    }

    @Inject(method = "checkBlock", at = @At("HEAD"))
    private void lucistarlink$startVanillaCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (!LuxServices.controller().shouldHandleBlockChange(lucistarlink$chunkSource, pos) && LuxBenchmarkSupport.enabled()) {
            lucistarlink$checkBlockStartedAt.set(LuxBenchmarkSupport.start());
        }
    }

    @Inject(method = "checkBlock", at = @At("RETURN"))
    private void lucistarlink$finishVanillaCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (!LuxServices.controller().shouldHandleBlockChange(lucistarlink$chunkSource, pos)) {
            Long startedAt = lucistarlink$checkBlockStartedAt.get();
            if (startedAt != null) {
                LuxBenchmarkSupport.recordSince("engine.check_block", startedAt);
            }
            lucistarlink$checkBlockStartedAt.remove();
        }
    }

    @Override
    public void lucistarlink$publish(LuxRelightResult result, LightChunk expectedChunk) {
        if (LuxFlags.piggybackPublish) {
            lucistarlink$scheduleEngineTask(result.chunkPos().x, result.chunkPos().z, lucistarlink$triggerSection(result),
                    () -> lucistarlink$publishDirect(result, expectedChunk), null);
            return;
        }
        lucistarlink$addPreTask(result.chunkPos().x, result.chunkPos().z, () -> lucistarlink$publishDirect(result, expectedChunk));
    }

    @Unique
    private CompletableFuture<Void> lucistarlink$publishAsync(LuxRelightResult result, int chunkX, int chunkZ) {
        if (LuxFlags.piggybackPublish) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            lucistarlink$scheduleEngineTask(chunkX, chunkZ, lucistarlink$triggerSection(result),
                    () -> lucistarlink$publishDirect(result, null), completion);
            return completion;
        }
        return lucistarlink$addPostTask(chunkX, chunkZ, () -> lucistarlink$publishDirect(result, null));
    }

    @Unique
    private static SectionPos lucistarlink$triggerSection(LuxRelightResult result) {
        return result.sections().isEmpty() ? null : result.sections().get(0).sectionPos();
    }

    /**
     * Queues a publication for the next engine update pass and makes sure such a pass happens. The engine task
     * used as the trigger is a section status update, which is what the engine itself uses to announce that a
     * section now holds light; for an already initialised section it changes nothing, so it is a pure wakeup.
     */
    @Unique
    private void lucistarlink$scheduleEngineTask(int chunkX, int chunkZ, SectionPos triggerSection,
                                                 Runnable publish, CompletableFuture<Void> completion) {
        this.lucistarlink$pendingPublishes.add(() -> {
            publish.run();
            if (completion != null) {
                this.lucistarlink$publishCompletions.add(completion);
            }
        });
        this.lucistarlink$publishesInFlight.incrementAndGet();
        ThreadedLevelLightEngine self = (ThreadedLevelLightEngine) (Object) this;
        if (triggerSection != null) {
            // tryScheduleUpdate only posts a pass when the engine already has a task or light work, so the batch
            // needs one ordinary engine task of its own to become visible
            self.updateSectionStatus(triggerSection, false);
        }
        self.tryScheduleUpdate();
    }

    /**
     * Runs the publications that are waiting, on the light thread, at the start of an engine update pass.
     *
     * <p>{@code runUpdate()} is the only place where the section storage may be written, and it is also the
     * pass that runs {@code super.runLightUpdates()} and then the POST_UPDATE tasks a caller installed through
     * {@code waitForPendingTasks} - the ones the benchmark's barrier waits for. Committing here therefore puts
     * our write and the acknowledgement of it into the same pass, instead of adding a mailbox wakeup of our own
     * plus the coalescing delay the private drain needs.
     *
     * <p>The pass is triggered by enqueueing one ordinary engine task (a section status update on a section we
     * are publishing, which is idempotent for an initialised section), so no package-private member of the
     * engine is needed - a mixin living in the engine's own package does not work under NeoForge's module
     * system (the module contains that package already).
     */
    @Unique
    private void lucistarlink$runPendingPublications() {
        int drained = this.lucistarlink$publishesInFlight.getAndSet(0);
        if (drained == 0) {
            return;
        }
        long startedAt = LuxBenchmarkSupport.start();
        Runnable publish;
        while ((publish = this.lucistarlink$pendingPublishes.poll()) != null) {
            publish.run();
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.publish_batch.drain", startedAt);
        LuxBenchmarkSupport.count("lucistarlink.publish_batch.tasks", drained);
        long notifyStartedAt = LuxBenchmarkSupport.start();
        lucistarlink$notifyPublishedLightSections();
        LuxBenchmarkSupport.recordSince("lucistarlink.publish_batch.notify", notifyStartedAt);
        CompletableFuture<Void> completion;
        while ((completion = this.lucistarlink$publishCompletions.poll()) != null) {
            completion.complete(null);
        }
    }

    @Inject(method = "runUpdate", at = @At("HEAD"))
    private void lucistarlink$publishBeforeLightUpdate(CallbackInfo ci) {
        if (LuxFlags.piggybackPublish) {
            lucistarlink$runPendingPublications();
        }
    }

    @Unique
    private void lucistarlink$publishDirect(LuxRelightResult result, LightChunk expectedChunk) {
        if (expectedChunk != null) {
            ChunkPos chunkPos = result.chunkPos();
            if (this.lucistarlink$chunkSource.getChunkForLighting(chunkPos.x, chunkPos.z) != expectedChunk) {
                LuxBenchmarkSupport.count("lucistarlink.runtime.commit.staleChunk");
                return;
            }
        }
        long startedAt = LuxBenchmarkSupport.start();
        long queueStartedAt = LuxBenchmarkSupport.start();
        for (LuxSectionData section : result.sections()) {
            if (LuxFlags.directSectionInstall) {
                // section state first: for a section the engine does not know yet this is what creates its
                // layer (and its neighbour bookkeeping), which the install below then overwrites with our data
                super.updateSectionStatus(section.sectionPos(), false);
                lucistarlink$installSection(section.layer(), section.sectionPos(), section.dataLayer());
            } else {
                super.queueSectionData(section.layer(), section.sectionPos(), section.dataLayer());
                super.updateSectionStatus(section.sectionPos(), false);
            }
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.publish_direct.queueData", queueStartedAt);
        long notifyStartedAt = LuxBenchmarkSupport.start();
        for (LuxSectionData section : result.sections()) {
            lucistarlink$queueAffectedLightNotifications(section.layer(), section.sectionPos());
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.publish_direct.notifyQueue", notifyStartedAt);
        LuxBenchmarkSupport.recordSince("lucistarlink.publish_direct", startedAt);
        LuxBenchmarkSupport.count("lucistarlink.publish_direct.sections", result.sections().size());
    }

    /**
     * Writes final light into the layer's own section maps. The engine reads gameplay light from
     * {@code updatingSectionData} and chunk packets/serialization fall back to {@code visibleSectionData}, so
     * both must carry the layer; the layer object itself is installed by reference exactly as the queued-data
     * route ends up doing. {@code clearCache} is required because that map keeps a two-entry lookup cache that
     * would otherwise keep serving the layer this call replaced, and a pending queued layer is dropped because
     * it would shadow ours in {@code getDataLayerData}.
     */
    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void lucistarlink$installSection(LightLayer layer, SectionPos sectionPos, net.minecraft.world.level.chunk.DataLayer dataLayer) {
        Object listener = super.getLayerListener(layer);
        LayerLightSectionStorageAccessor storage =
                (LayerLightSectionStorageAccessor) (Object) ((LightEngineAccessor) listener).lucistarlink$storage();
        long packedPos = sectionPos.asLong();
        net.minecraft.world.level.lighting.DataLayerStorageMap updating =
                (net.minecraft.world.level.lighting.DataLayerStorageMap) storage.lucistarlink$updatingSectionData();
        net.minecraft.world.level.lighting.DataLayerStorageMap visible =
                (net.minecraft.world.level.lighting.DataLayerStorageMap) storage.lucistarlink$visibleSectionData();
        updating.setLayer(packedPos, dataLayer);
        visible.setLayer(packedPos, dataLayer);
        updating.clearCache();
        storage.lucistarlink$queuedSections().remove(packedPos);
    }

    @Unique
    private void lucistarlink$notifyPublishedLightSections() {
        ArrayDeque<LuxLightNotification> notifications = this.lucistarlink$pendingLightNotifications;
        LuxBenchmarkSupport.count("lucistarlink.publish.notify.posted", notifications.size());
        long startedAt = LuxBenchmarkSupport.start();
        while (!notifications.isEmpty()) {
            LuxLightNotification notification = notifications.removeFirst();
            this.lucistarlink$chunkSource.onLightUpdate(notification.layer(), SectionPos.of(notification.sectionPos()));
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.publish.notify.post", startedAt);
        this.lucistarlink$pendingLightNotificationKeys.clear();
    }

    @Unique
    private void lucistarlink$queueAffectedLightNotifications(LightLayer layer, SectionPos sectionPos) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    lucistarlink$queueLightNotification(layer, SectionPos.of(
                            sectionPos.x() + offsetX,
                            sectionPos.y() + offsetY,
                            sectionPos.z() + offsetZ
                    ));
                }
            }
        }
    }

    @Unique
    private void lucistarlink$queueLightNotification(LightLayer layer, SectionPos sectionPos) {
        LuxLightNotification notification = new LuxLightNotification(layer, sectionPos.asLong());
        if (this.lucistarlink$pendingLightNotificationKeys.add(notification)) {
            this.lucistarlink$pendingLightNotifications.addLast(notification);
        }
    }

    @Unique
    private void lucistarlink$addPreTask(int chunkX, int chunkZ, Runnable task) {
        lucistarlink$enqueueLightTask(chunkX, chunkZ, lucistarlink$queueLevel(chunkX, chunkZ), task, null);
    }

    @Unique
    private CompletableFuture<Void> lucistarlink$addPostTask(int chunkX, int chunkZ, Runnable preTask) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        lucistarlink$enqueueLightTask(chunkX, chunkZ, lucistarlink$queueLevel(chunkX, chunkZ), preTask, future);
        return future;
    }

    @Unique
    private IntSupplier lucistarlink$queueLevel(int chunkX, int chunkZ) {
        return ((ChunkMapAccessor) this.lucistarlink$chunkMap).lucistarlink$getChunkQueueLevel(ChunkPos.asLong(chunkX, chunkZ));
    }

    @Unique
    private void lucistarlink$enqueueLightTask(int chunkX, int chunkZ, IntSupplier queueLevel, Runnable preTask, CompletableFuture<Void> completion) {
        this.lucistarlink$sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            synchronized (this.lucistarlink$publishLock) {
                if (this.lucistarlink$pendingLightTasks.isEmpty()) {
                    this.lucistarlink$batchFirstQueuedNanos = System.nanoTime();
                }
                this.lucistarlink$pendingLightTasks.addLast(new LuxQueuedLightTask(preTask, completion));
                LuxBenchmarkSupport.count("lucistarlink.publish_batch.queued");
            }
            lucistarlink$schedulePublishDrain();
        }, ChunkPos.asLong(chunkX, chunkZ), queueLevel));
    }

    @Unique
    private void lucistarlink$schedulePublishDrain() {
        if (this.lucistarlink$taskMailbox == null || !this.lucistarlink$publishScheduled.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.delayedExecutor(LUCIS_PUBLISH_COALESCE_NANOS, TimeUnit.NANOSECONDS)
                .execute(() -> this.lucistarlink$taskMailbox.tell(this::lucistarlink$drainQueuedLightTasks));
    }

    @Unique
    private void lucistarlink$drainQueuedLightTasks() {
        long startedAt = LuxBenchmarkSupport.start();
        ArrayDeque<LuxQueuedLightTask> batch = this.lucistarlink$publishBatch;
        batch.clear();
        this.lucistarlink$pendingLightNotifications.clear();
        this.lucistarlink$pendingLightNotificationKeys.clear();
        long firstQueuedNanos;
        synchronized (this.lucistarlink$publishLock) {
            int count = Math.min(1000, this.lucistarlink$pendingLightTasks.size());
            for (int index = 0; index < count; index++) {
                batch.add(this.lucistarlink$pendingLightTasks.removeFirst());
            }
            firstQueuedNanos = this.lucistarlink$batchFirstQueuedNanos;
        }
        if (firstQueuedNanos != 0L) {
            LuxBenchmarkSupport.record("lucistarlink.publish_batch.queueLatency", startedAt - firstQueuedNanos);
        }

        try {
            for (LuxQueuedLightTask task : batch) {
                task.runnable().run();
            }
            if (!batch.isEmpty()) {
                long updatesStartedAt = LuxBenchmarkSupport.start();
                super.runLightUpdates();
                LuxBenchmarkSupport.recordSince("lucistarlink.publish_batch.runLightUpdates", updatesStartedAt);
                long notifyStartedAt = LuxBenchmarkSupport.start();
                lucistarlink$notifyPublishedLightSections();
                LuxBenchmarkSupport.recordSince("lucistarlink.publish_batch.notify", notifyStartedAt);
            }
            for (LuxQueuedLightTask task : batch) {
                if (task.completion() != null) {
                    task.completion().complete(null);
                }
            }
            LuxBenchmarkSupport.recordSince("lucistarlink.publish_batch.drain", startedAt);
            LuxBenchmarkSupport.count("lucistarlink.publish_batch.tasks", batch.size());
        } catch (Throwable throwable) {
            for (LuxQueuedLightTask task : batch) {
                if (task.completion() != null) {
                    task.completion().completeExceptionally(throwable);
                }
            }
            throw throwable;
        } finally {
            batch.clear();
            this.lucistarlink$pendingLightNotifications.clear();
            this.lucistarlink$pendingLightNotificationKeys.clear();
            this.lucistarlink$publishScheduled.set(false);
            boolean hasMore;
            synchronized (this.lucistarlink$publishLock) {
                hasMore = !this.lucistarlink$pendingLightTasks.isEmpty();
            }
            if (hasMore) {
                lucistarlink$schedulePublishDrain();
            }
        }
    }

    @Unique
    private record LuxQueuedLightTask(Runnable runnable, CompletableFuture<Void> completion) {
    }

    @Unique
    private record LuxLightNotification(LightLayer layer, long sectionPos) {
    }
}
