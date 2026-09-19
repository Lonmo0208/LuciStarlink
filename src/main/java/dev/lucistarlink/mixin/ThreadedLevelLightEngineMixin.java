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

    /**
     * Whether a published section also notifies its 26 neighbours (3x3x3), or only itself.
     *
     * <p>ScalableLux notifies exactly the sections whose visible copy changed ({@code nibble.updateVisible()} in
     * {@code StarLightEngine.updateVisible}) and nothing else, so the fan-out is not what makes bordered light
     * render correctly: the client's renderer dirties the neighbouring render sections itself when a light packet
     * arrives. The fan-out costs ~6.6 posted notifications per published section here (10208 posts for 1541
     * sections in one sky_hole run), which is the same order as the whole gap to ScalableLux on that workload.
     *
     * <p>Default stays "true" (the shipped behaviour) until the interleaved A/B and the client-sync case both
     * pass with it off - a narrower notification set is only correct if the client still shows border light.
     */
    @Unique
    private static final boolean LUCIS_NOTIFY_NEIGHBOURS =
            Boolean.parseBoolean(System.getProperty("lucistarlink.notifyNeighbourSections", "true"));

    /**
     * Whether runtime publishes get their own lane ahead of world-generation publishes in the drain batch, and
     * whether a single drain round is capped.
     *
     * <p>Both publish through the same light thread, so a player's edit is queued behind whatever bulk work is in
     * flight. Measured on {@code sky_hole}: the publish batch's queue latency (queued -> drain starts) averages
     * 0.91 ms, the largest single term on the pass path, while the edit's own compute is 0.13 ms and its publish
     * 0.06 ms. Shortening the coalescing window does not help (250 -> 50 us measured p=0.73) because the wait is
     * mostly "the previous drain has not finished yet", and a world-generation drain can hold the light thread
     * for a long time. This lane is meant to bound that: the runtime task is taken first by the next round, and a
     * bulk round cannot grow past {@code lucistarlink.publishDrainBulkCap} tasks.
     *
     * <p>Off by default (FIFO, unchanged behaviour) until the interleaved A/B and the correctness gates pass.
     */
    @Unique
    private static final boolean LUCIS_PRIORITISE_RUNTIME =
            Boolean.parseBoolean(System.getProperty("lucistarlink.publishPrioritiseRuntime", "false"));

    @Unique
    private static final int LUCIS_DRAIN_BULK_CAP =
            Math.max(1, Integer.getInteger("lucistarlink.publishDrainBulkCap", 64));

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
    /** Runtime (edit-sized) publishes, drained ahead of the bulk lane when prioritisation is on. */
    @Unique
    private final Deque<LuxQueuedLightTask> lucistarlink$pendingRuntimeTasks = new ArrayDeque<>();
    @Unique
    private final ArrayDeque<LuxQueuedLightTask> lucistarlink$publishBatch = new ArrayDeque<>(1000);
    @Unique
    private final ArrayDeque<LuxLightNotification> lucistarlink$pendingLightNotifications = new ArrayDeque<>(1000);
    @Unique
    private final HashSet<LuxLightNotification> lucistarlink$pendingLightNotificationKeys = new HashSet<>(1000);
    @Unique
    private final AtomicBoolean lucistarlink$publishScheduled = new AtomicBoolean();
    /** True while a drain round is executing on the light thread; used to attribute queue latency to it. */
    @Unique
    private final AtomicBoolean lucistarlink$drainRunning = new AtomicBoolean();
    /** Set by a publish inside the current drain, so only drains that published complete a benchmark pass. */
    @Unique
    private boolean lucistarlink$drainPublished;
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
    private volatile long lucistarlink$batchFirstQueuedNanos;    @Unique
    private volatile boolean lucistarlink$batchPrompt;
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

        // 区块自称「光照已完成」= 盘上那份光照是权威的，不要重算它。这是必须有的一道闸：世界生成出来的区块
        // 是「未完成」（false），照旧走下面的重算；而从盘上加载、带着自己光照的区块（true）一旦被我们重算，
        // 我们算出来的就是「只有光源自己那一格」的坏场（加载时序里引擎还没有那份数据可作传播基线），再盖回去
        // 就等于把好数据毁掉 —— 实测：加这道闸之前，同一份世界 gen→reload 方块光指纹三组全变；关掉本路径后
        // 三组逐位相同；闸本身也让 gen→reload 三组逐位相同（对照：引擎关闭时本来就逐位相同）。
        if (chunk.isLightCorrect()) {
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

    /**
     * 每个方块改动都会走到这里：要么我们接管（取消原版 checkBlock），要么交给原版。守卫是这条路上最贵的一步
     * （配置、世界生成压制、背压、任务队列容量、Sable），所以整个方法只问它一次 —— 之前为了记录「原版花了
     * 多久」在三个注入点各问一次，纯仪表开销就占了每次改方块的一大半。计时现在挂在本方法与 RETURN 之间。
     */
    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void lucistarlink$checkBlock(BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        boolean handled = LuxServices.controller().shouldHandleBlockChange(lucistarlink$chunkSource, pos);
        if (!LuxBenchmarkSupport.enabled()) {
            if (handled) {
                ci.cancel();
            }
            return;
        }
        if (handled) {
            lucistarlink$checkBlockStartedAt.remove();
            LuxBenchmarkSupport.recordSince("lucistarlink.check_block", LuxBenchmarkSupport.start());
            ci.cancel();
            return;
        }
        lucistarlink$checkBlockStartedAt.set(LuxBenchmarkSupport.start());
    }

    @Inject(method = "checkBlock", at = @At("RETURN"))
    private void lucistarlink$finishVanillaCheckBlock(BlockPos pos, CallbackInfo ci) {
        Long startedAt = lucistarlink$checkBlockStartedAt.get();
        if (startedAt == null) {
            return;
        }
        lucistarlink$checkBlockStartedAt.remove();
        LuxBenchmarkSupport.recordSince("engine.check_block", startedAt);
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

    @Override
    public void lucistarlink$drainNow() {
        if (this.lucistarlink$taskMailbox == null) {
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        long startedAt = System.nanoTime();
        this.lucistarlink$taskMailbox.tell(() -> {
            try {
                lucistarlink$drainQueuedLightTasks();
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(50L, TimeUnit.MILLISECONDS)) {
                LuxBenchmarkSupport.count("lucistarlink.runtime.syncDrain.timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        LuxBenchmarkSupport.recordSince("lucistarlink.runtime.syncDrain", startedAt);
    }

    @Override
    public CompletableFuture<Void> lucistarlink$flushMarker(int chunkX, int chunkZ) {
        return lucistarlink$addPostTask(chunkX, chunkZ, () -> {
            // no-op: its only job is to complete after everything queued before it has been published
        });
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
        if (!result.sections().isEmpty()) {
            // this drain actually hands light to the engine, so it is the drain that "completes" a pass's work
            this.lucistarlink$drainPublished = true;
        }
        for (LuxSectionData section : result.sections()) {
            if (LuxFlags.directSectionInstall) {
                // section state first: for a section the engine does not know yet this is what creates its
                // layer (and its neighbour bookkeeping), which the install below then overwrites with our data
                super.updateSectionStatus(section.sectionPos(), false);
                lucistarlink$installSection(section.layer(), section.sectionPos(), section.dataLayer());
            } else {
                super.queueSectionData(section.layer(), section.sectionPos(), section.dataLayer());
                super.updateSectionStatus(section.sectionPos(), false);
                // 写盘与发包读的是 visibleSectionData，而 queueSectionData 只填 updatingSectionData，靠引擎
                // 自己的更新周期去提升。既然我们替换了引擎、那条提升路径并不保证为这些 section 跑过，写盘就会
                // 拿到旧的（常常是全空的）那一份 —— 实测：加了这一步之前，同一份世界重启后方块光指纹三组全变，
                // 关掉引擎的对照三组逐位不变。两份都写，游戏内与存盘才是同一份数据（installSection 就地覆盖，
                // 不替换对象，遵守光照线程仍持有该对象的约束）。
                lucistarlink$installSection(section.layer(), section.sectionPos(), section.dataLayer());
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
     * both must carry the data.
     *
     * <p>When the engine already holds a layer for the section, its bytes are overwritten **in place** rather
     * than replacing the object: the light thread keeps writing into that very object during its own passes,
     * and swapping it would send those writes into the orphaned array - lost, with nothing marked inconsistent
     * to re-derive them (measured: a run in three diverged from vanilla on a band probe when the object was
     * swapped, never with the in-place copy). Only a section with no layer yet receives ours.
     *
     * <p>A pending queued layer is dropped because it would shadow ours in {@code getDataLayerData}, and the
     * lookup cache is cleared because it may still hold the object this call replaced.
     */
    /**
     * 客户端优化的核心判据：这个 section 的**六个面**里，哪些面真的有格子变了。
     *
     * <p>为什么需要它：原版（以及我们目前的默认）在发布一个 section 时会把 3×3×3 共 27 个 section 都标成
     * 「光照变了」，而这些标记会让服务端给客户端发光照数据 —— 实测通知量因此是必要的 7.1 倍
     * （10015 → 1401），第一轮客户端实测进服两分钟就收到 11,729 个 section（约 24 MB）。
     *
     * <p>规则**构造上正确**：邻区的光照与网格只可能因为与它共享的那一层变化而变化。所以「没变的面 → 不通知那个
     * 邻区」永远不会漏掉必需的通知（比直接关掉扇出安全），而「变了的面 → 照旧扇出」也永远不会少通知（保守一侧）。
     *
     * @return 位掩码：1=-X 2=+X 4=-Y 8=+Y 16=-Z 32=+Z；`before` 为 null 时返回 63（全变，按最保守处理）
     */
    @Unique
    private static int lucistarlink$changedFaces(byte[] before, byte[] after) {
        if (before == null || after == null) {
            return 63;
        }
        int mask = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                if (lucistarlink$nibble(before, y, z, 0) != lucistarlink$nibble(after, y, z, 0)) {
                    mask |= 1;
                }
                if (lucistarlink$nibble(before, y, z, 15) != lucistarlink$nibble(after, y, z, 15)) {
                    mask |= 2;
                }
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (lucistarlink$nibble(before, 0, z, x) != lucistarlink$nibble(after, 0, z, x)) {
                    mask |= 4;
                }
                if (lucistarlink$nibble(before, 15, z, x) != lucistarlink$nibble(after, 15, z, x)) {
                    mask |= 8;
                }
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                if (lucistarlink$nibble(before, y, 0, x) != lucistarlink$nibble(after, y, 0, x)) {
                    mask |= 16;
                }
                if (lucistarlink$nibble(before, y, 15, x) != lucistarlink$nibble(after, y, 15, x)) {
                    mask |= 32;
                }
            }
        }
        return mask;
    }

    /** 按原版 DataLayer 的打包方式取一格光照：索引 (y&lt;&lt;8)|(z&lt;&lt;4)|x，偶数索引取低四位。 */
    @Unique
    private static int lucistarlink$nibble(byte[] data, int y, int z, int x) {
        int index = (y << 8) | (z << 4) | x;
        return (data[index >> 1] >> ((index & 1) * 4)) & 15;
    }

    /** 引擎当前持有的这一层 section 字节（用于在写入前算出六面变化掩码）；没有则返回 null。 */
    @Unique
    private byte[] lucistarlink$currentLayerBytes(LightLayer layer, SectionPos sectionPos) {
        Object listener = super.getLayerListener(layer);
        if (!(listener instanceof LightEngineAccessor engineAccessor)) {
            return null;
        }
        LayerLightSectionStorageAccessor storage =
                (LayerLightSectionStorageAccessor) (Object) engineAccessor.lucistarlink$storage();
        net.minecraft.world.level.lighting.DataLayerStorageMap updating =
                (net.minecraft.world.level.lighting.DataLayerStorageMap) storage.lucistarlink$updatingSectionData();
        net.minecraft.world.level.chunk.DataLayer layerData = updating.getLayer(sectionPos.asLong());
        return layerData == null ? null : layerData.getData();
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void lucistarlink$installSection(LightLayer layer, SectionPos sectionPos, net.minecraft.world.level.chunk.DataLayer dataLayer) {
        Object listener = super.getLayerListener(layer);
        // 没有光照引擎的层（例如无方块光的维度）拿到的是 DummyLightLayerEventListener，它没有 storage 可写。
        // 实测：在 directSectionInstall 路径上会抛 ClassCastException（Worker 线程，被 Util 捕获后只留一行日志），
        // 所以这里必须先认类型 —— 没有引擎就没什么可装的，直接返回就是正确行为。
        if (!(listener instanceof LightEngineAccessor engineAccessor)) {
            return;
        }
        LayerLightSectionStorageAccessor storage =
                (LayerLightSectionStorageAccessor) (Object) engineAccessor.lucistarlink$storage();
        long packedPos = sectionPos.asLong();
        net.minecraft.world.level.lighting.DataLayerStorageMap updating =
                (net.minecraft.world.level.lighting.DataLayerStorageMap) storage.lucistarlink$updatingSectionData();
        net.minecraft.world.level.lighting.DataLayerStorageMap visible =
                (net.minecraft.world.level.lighting.DataLayerStorageMap) storage.lucistarlink$visibleSectionData();
        byte[] source = dataLayer.getData();
        net.minecraft.world.level.chunk.DataLayer existing = updating.getLayer(packedPos);
        if (existing == null) {
            updating.setLayer(packedPos, dataLayer);
            updating.clearCache();
        } else {
            System.arraycopy(source, 0, existing.getData(), 0, net.minecraft.world.level.chunk.DataLayer.SIZE);
        }
        net.minecraft.world.level.chunk.DataLayer existingVisible = visible.getLayer(packedPos);
        if (existingVisible == null) {
            visible.setLayer(packedPos, dataLayer);
        } else if (existingVisible != existing) {
            System.arraycopy(source, 0, existingVisible.getData(), 0, net.minecraft.world.level.chunk.DataLayer.SIZE);
        }
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
        if (!LUCIS_NOTIFY_NEIGHBOURS) {
            lucistarlink$queueLightNotification(layer, sectionPos);
            return;
        }
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
        lucistarlink$enqueueLightTask(chunkX, chunkZ, lucistarlink$queueLevel(chunkX, chunkZ), task, null,
                LuxFlags.promptRuntimePublish, true);
    }

    @Unique
    private CompletableFuture<Void> lucistarlink$addPostTask(int chunkX, int chunkZ, Runnable preTask) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        lucistarlink$enqueueLightTask(chunkX, chunkZ, lucistarlink$queueLevel(chunkX, chunkZ), preTask, future, false, false);
        return future;
    }

    @Unique
    private IntSupplier lucistarlink$queueLevel(int chunkX, int chunkZ) {
        return ((ChunkMapAccessor) this.lucistarlink$chunkMap).lucistarlink$getChunkQueueLevel(ChunkPos.asLong(chunkX, chunkZ));
    }

    @Unique
    private void lucistarlink$enqueueLightTask(int chunkX, int chunkZ, IntSupplier queueLevel, Runnable preTask,
                                               CompletableFuture<Void> completion, boolean prompt, boolean runtime) {
        this.lucistarlink$sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            synchronized (this.lucistarlink$publishLock) {
                Deque<LuxQueuedLightTask> lane = runtime && LUCIS_PRIORITISE_RUNTIME
                        ? this.lucistarlink$pendingRuntimeTasks
                        : this.lucistarlink$pendingLightTasks;
                if (this.lucistarlink$pendingLightTasks.isEmpty() && this.lucistarlink$pendingRuntimeTasks.isEmpty()) {
                    this.lucistarlink$batchFirstQueuedNanos = System.nanoTime();
                    this.lucistarlink$batchPrompt = prompt;
                }
                lane.addLast(new LuxQueuedLightTask(preTask, completion));
                LuxBenchmarkSupport.count("lucistarlink.publish_batch.queued");
                LuxBenchmarkSupport.count(runtime ? "lucistarlink.publish_batch.lane.runtime"
                        : "lucistarlink.publish_batch.lane.bulk");
                if (this.lucistarlink$drainRunning.get()) {
                    // the queued task cannot start until the round in flight finishes: this is the part of queue
                    // latency that is not the coalescing window, and it is what the lane split was meant to bound
                    LuxBenchmarkSupport.count("lucistarlink.publish_batch.queuedDuringDrain");
                }
            }
            lucistarlink$schedulePublishDrain(this.lucistarlink$batchPrompt ? 0L : LUCIS_PUBLISH_COALESCE_NANOS);
        }, ChunkPos.asLong(chunkX, chunkZ), queueLevel));
    }

    /**
     * Hands the batch to the light thread. A zero delay posts the drain straight onto the mailbox; any positive
     * delay goes through {@code CompletableFuture.delayedExecutor}, which costs an extra hop through the timer
     * thread even when the delay is zero - which is why "coalesce delay = 0" measured no improvement earlier,
     * while posting directly is what the promptness of the private drain actually came from.
     */
    @Unique
    private void lucistarlink$schedulePublishDrain(long delayNanos) {
        if (this.lucistarlink$taskMailbox == null || !this.lucistarlink$publishScheduled.compareAndSet(false, true)) {
            return;
        }
        if (delayNanos <= 0L) {
            this.lucistarlink$taskMailbox.tell(this::lucistarlink$drainQueuedLightTasks);
            return;
        }
        CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS)
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
        boolean runtimeQueued = false;
        synchronized (this.lucistarlink$publishLock) {
            // the runtime lane is taken first: an edit must not wait behind a bulk world-generation round
            int runtimeCount = Math.min(1000, this.lucistarlink$pendingRuntimeTasks.size());
            for (int index = 0; index < runtimeCount; index++) {
                batch.add(this.lucistarlink$pendingRuntimeTasks.removeFirst());
            }
            runtimeQueued = !this.lucistarlink$pendingRuntimeTasks.isEmpty();
            int bulkCap = LUCIS_PRIORITISE_RUNTIME ? Math.max(1, LUCIS_DRAIN_BULK_CAP - runtimeCount) : 1000;
            int count = Math.min(bulkCap, this.lucistarlink$pendingLightTasks.size());
            for (int index = 0; index < count; index++) {
                batch.add(this.lucistarlink$pendingLightTasks.removeFirst());
            }
            firstQueuedNanos = this.lucistarlink$batchFirstQueuedNanos;
        }
        if (firstQueuedNanos != 0L) {
            LuxBenchmarkSupport.record("lucistarlink.publish_batch.queueLatency", startedAt - firstQueuedNanos);
        }
        this.lucistarlink$drainRunning.set(true);

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
            this.lucistarlink$drainRunning.set(false);
            // a stale timestamp from an earlier batch would be reported as this batch's queue latency, and the
            // metric is what the tuning decisions in docs/TASK-PERF-SKY.md are based on
            this.lucistarlink$batchFirstQueuedNanos = 0L;
            this.lucistarlink$drainPublished = false;
            batch.clear();
            this.lucistarlink$pendingLightNotifications.clear();
            this.lucistarlink$pendingLightNotificationKeys.clear();
            this.lucistarlink$publishScheduled.set(false);
            boolean hasMore;
            boolean hasRuntime;
            synchronized (this.lucistarlink$publishLock) {
                hasMore = !this.lucistarlink$pendingLightTasks.isEmpty() || !this.lucistarlink$pendingRuntimeTasks.isEmpty();
                hasRuntime = !this.lucistarlink$pendingRuntimeTasks.isEmpty();
            }
            if (hasMore) {
                // a waiting runtime edit goes out promptly; bulk work keeps the coalescing window
                boolean prompt = this.lucistarlink$batchPrompt || (LUCIS_PRIORITISE_RUNTIME && (hasRuntime || runtimeQueued));
                lucistarlink$schedulePublishDrain(prompt ? 0L : LUCIS_PUBLISH_COALESCE_NANOS);
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
