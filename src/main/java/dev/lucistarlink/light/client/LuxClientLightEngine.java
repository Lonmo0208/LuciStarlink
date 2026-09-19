package dev.lucistarlink.light.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 客户端光照引擎：**保留原版的数据结构与数据管线，只删掉「客户端自己从方块源头重算」那一半**。
 *
 * <p>为什么是这个形状（见 {@code docs/ARCH-V2-CLIENT-LIGHTING.md} §2）：客户端渲染、Sodium/Embeddium、光影都直接读
 * {@code LevelLightEngine} 的存储结构，所以结构不能动。而客户端本来也不需要自己推光 —— 每个 section 的最终光照
 * 由服务端算好、经 {@code queueSectionData} 送过来（客户端探针就是在量这件事）。
 *
 * 因此这里只把两个「从方块源头重算」的入口变成空实现：
 * <ul>
 *   <li>{@code checkBlock}：本地方块变化时重新评估该格光源（服务端随后会送来结果）；</li>
 *   <li>{@code propagateLightSources}：区块加载时按世界里的光源重新推一遍（这正是我们要省掉的成本）。</li>
 * </ul>
 * {@code runLightUpdates} / {@code updateSectionStatus} / {@code queueSectionData} / 各种查询**全部保留**：它们是
 * 数据管线与读取入口，动了就是兼容性与正确性地狱。
 *
 * <p>代价（如实记）：客户端自己的方块预测（自己刚放的方块）要等服务端的更新到达才有光，多等一个来回（~50 ms）。
 * 换来的是客户端不再为每个区块重推光照。默认关闭，用 {@code -Dlucistarlink.clientLightTakeover=true} 打开。
 */
public final class LuxClientLightEngine extends LevelLightEngine {
    /** 接管开关；默认关闭时这台引擎根本不会被创建（见 {@code ClientChunkCacheMixin}）。 */
    public static final boolean TAKEOVER_ENABLED =
            Boolean.getBoolean("lucistarlink.clientLightTakeover");

    public LuxClientLightEngine(LightChunkGetter chunkGetter, boolean hasBlockLight, boolean hasSkyLight) {
        super(chunkGetter, hasBlockLight, hasSkyLight);
    }

    @Override
    public void checkBlock(BlockPos pos) {
        // 服务端算好并发送结果，客户端不再为本地变化重推光照
        LuxClientLightProbe.countSkippedCheckBlock();
    }

    @Override
    public void propagateLightSources(ChunkPos chunkPos) {
        // 区块加载时不再按世界光源重推 —— 这是接管省下的主要成本
        LuxClientLightProbe.countSkippedPropagate();
    }
}
