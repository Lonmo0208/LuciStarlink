package dev.lucistarlink.light.engine;

import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.config.LuxConfig;
import dev.lucistarlink.light.region.RegionBounds;
import dev.lucistarlink.light.region.RegionLightData;
import dev.lucistarlink.light.runtime.LuxRelightResult;
import dev.lucistarlink.light.runtime.LuxSectionData;
import dev.lucistarlink.light.util.NibblePacker;
import dev.lucistarlink.test.LuxBenchmarkSupport;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class LuxPublishEngine {
    /** Reads the engines current light for a section, used to skip publishing sections that did not change. */
    public interface SectionLightSource {
        byte[] current(net.minecraft.core.SectionPos sectionPos, LightLayer layer);

        SectionLightSource NONE = (sectionPos, layer) -> null;
    }

    // 按线程而不是全局：发布由作业线程自己完成（设光源 → 计算 → 在同一个线程里 collect），但同一时刻可以有多个
    // 作业在跑，多维度服务器上还可能是不同 level 的作业。用单个 volatile 字段时，B 线程会读到 A 线程或另一个维度
    // 刚设进去的光源，于是「这个 section 是否没变」的身份比较拿到的是别的世界的字节 —— 一旦相等就会**跳过一次
    // 本来必须的发布（丢光）**。按线程后，设置与读取必然落在同一个线程内，跨线程和跨维度都不再共享。
    private final ThreadLocal<SectionLightSource> lightSourceByThread =
            ThreadLocal.withInitial(() -> SectionLightSource.NONE);

    public void setCurrentLightSource(SectionLightSource source) {
        this.lightSourceByThread.set(source == null ? SectionLightSource.NONE : source);
    }

    /** 用完必须清掉：光源 lambda 捕获着那个 level 的光照引擎，留在工作线程的 ThreadLocal 里会把已卸载的维度钉住不回收。 */
    public void clearCurrentLightSource() {
        this.lightSourceByThread.remove();
    }

    private SectionLightSource currentLightSource() {
        return lightSourceByThread.get();
    }

    public LuxRelightResult publishChunk(ChunkPos chunkPos, RegionLightData data) {
        List<LuxSectionData> sections = new ArrayList<>(data.bounds.sectionCount() << 1);
        RegionBounds bounds = data.bounds;
        // the whole image may be published, halo included: halo chunks carry the light an edit pushed across a
        // region border, and refusing them here silently turns halo publishing into a no-op
        int minChunkX = bounds.originChunkX() - bounds.haloChunks();
        int minChunkZ = bounds.originChunkZ() - bounds.haloChunks();
        int maxChunkX = minChunkX + bounds.regionChunks() + bounds.haloChunks() * 2;
        int maxChunkZ = minChunkZ + bounds.regionChunks() + bounds.haloChunks() * 2;
        if (chunkPos.x < minChunkX || chunkPos.x >= maxChunkX || chunkPos.z < minChunkZ || chunkPos.z >= maxChunkZ) {
            return new LuxRelightResult(chunkPos, List.of());
        }

        collect(data, chunkPos, LightLayer.BLOCK, false, data.dirtyBlockSections, sections);
        collect(data, chunkPos, LightLayer.SKY, true, data.dirtySkySections, sections);
        LuxBenchmarkSupport.count("lucistarlink.publish.chunk.calls");
        LuxBenchmarkSupport.count("lucistarlink.publish.chunk.sections", sections.size());
        return new LuxRelightResult(chunkPos, sections);
    }

    public List<LuxRelightResult> publishRegion(RegionLightData data) {
        int regionChunks = data.bounds.regionChunks();
        List<LuxRelightResult> results = new ArrayList<>(regionChunks * regionChunks);
        if (regionChunks == 1) {
            LuxRelightResult result = publishChunk(new ChunkPos(data.bounds.originChunkX(), data.bounds.originChunkZ()), data);
            if (!result.sections().isEmpty()) {
                results.add(result);
            }
            return results;
        }
        for (int chunkZ = 0; chunkZ < regionChunks; chunkZ++) {
            for (int chunkX = 0; chunkX < regionChunks; chunkX++) {
                if (!hasDirtySections(data, chunkX, chunkZ)) {
                    continue;
                }
                ChunkPos chunkPos = new ChunkPos(data.bounds.originChunkX() + chunkX, data.bounds.originChunkZ() + chunkZ);
                LuxRelightResult result = publishChunk(chunkPos, data);
                if (!result.sections().isEmpty()) {
                    results.add(result);
                }
            }
        }
        return results;
    }

    /**
     * Publishes every chunk in the image whose sections are dirty, core chunks first and then the halo.
     *
     * <p>The halo half is what carries an edit's light across a region border: a job computes light into the
     * halo, but if only the core is published the neighbouring chunk's stored light stays stale until
     * something else touches that region, which players see as a black seam at the border. Halo results are
     * dirty-section only, so this costs proportionally to the light that actually crossed, not to the image
     * size.
     */
    public List<LuxRelightResult> publishRegionWithHalo(RegionLightData data) {
        List<LuxRelightResult> results = publishRegion(data);
        int halo = data.bounds.haloChunks();
        if (halo <= 0) {
            return results;
        }
        int chunkCount = data.bounds.regionChunks() + halo * 2;
        int dirtyCore = 0;
        int dirtyHalo = 0;
        int publishedHalo = 0;
        for (int localChunkZ = 0; localChunkZ < chunkCount; localChunkZ++) {
            for (int localChunkX = 0; localChunkX < chunkCount; localChunkX++) {
                // core chunks were already published above
                boolean coreChunk = localChunkX >= halo && localChunkX < chunkCount - halo
                        && localChunkZ >= halo && localChunkZ < chunkCount - halo;
                if (!hasDirtySectionsLocal(data, localChunkX, localChunkZ)) {
                    continue;
                }
                if (coreChunk) {
                    dirtyCore++;
                    continue;
                }
                dirtyHalo++;
                ChunkPos chunkPos = new ChunkPos(
                        data.bounds.originChunkX() - halo + localChunkX,
                        data.bounds.originChunkZ() - halo + localChunkZ);
                LuxRelightResult result = publishChunk(chunkPos, data);
                if (!result.sections().isEmpty()) {
                    LuxBenchmarkSupport.count("lucistarlink.publish.halo.sections", result.sections().size());
                    publishedHalo++;
                    results.add(result);
                }
            }
        }
        if (LuxConfig.verboseLogging) {
            LuciStarlink.LOGGER.info("LuciStarlink publish: halo={} image={}x{} chunks, dirty core {} dirty halo {}, "
                            + "halo chunks published {}",
                    halo, chunkCount, chunkCount, dirtyCore, dirtyHalo, publishedHalo);
        }
        return results;
    }

    private boolean hasDirtySections(RegionLightData data, int coreChunkX, int coreChunkZ) {
        int localChunkX = data.coreLocalChunkStart + coreChunkX;
        int localChunkZ = data.coreLocalChunkStart + coreChunkZ;
        return hasDirtySectionsLocal(data, localChunkX, localChunkZ);
    }

    private boolean hasDirtySectionsLocal(RegionLightData data, int localChunkX, int localChunkZ) {
        int sectionIndexBase = localChunkX + localChunkZ * data.sectionWidth;
        int sectionsPerPlane = data.sectionsPerPlane;
        for (int sectionY = 0; sectionY < data.bounds.sectionCount(); sectionY++) {
            int linear = sectionIndexBase + sectionY * sectionsPerPlane;
            if (data.dirtyBlockSections.get(linear) || data.dirtySkySections.get(linear)) {
                return true;
            }
        }
        return false;
    }

    private void collect(RegionLightData data, ChunkPos chunkPos, LightLayer layer, boolean sky, BitSet dirtyMask, List<LuxSectionData> out) {
        int sectionWidth = data.sectionWidth;
        int localChunkX = chunkPos.x - (data.bounds.originChunkX() - data.bounds.haloChunks());
        int localChunkZ = chunkPos.z - (data.bounds.originChunkZ() - data.bounds.haloChunks());
        int sectionIndexBase = localChunkX + localChunkZ * sectionWidth;
        int sectionsPerPlane = data.sectionsPerPlane;
        byte[] source = sky ? data.skyLight : data.blockLight;

        for (int sectionY = 0; sectionY < data.bounds.sectionCount(); sectionY++) {
            int linear = sectionIndexBase + sectionY * sectionsPerPlane;
            if (!dirtyMask.get(linear)) {
                continue;
            }

            int worldSectionX = chunkPos.x << 4;
            int worldSectionZ = chunkPos.z << 4;
            int worldSectionY = data.bounds.minSectionY() + sectionY;
            DataLayer packed = NibblePacker.packSection(data, source, worldSectionX, worldSectionY, worldSectionZ);
            byte[] current = currentLightSource().current(SectionPos.of(chunkPos, data.bounds.minSectionY() + sectionY), layer);
            if (current != null && java.util.Arrays.equals(current, packed.getData())) {
                LuxBenchmarkSupport.count("lucistarlink.publish.skippedIdentical.sections");
                continue;
            }
            out.add(new LuxSectionData(SectionPos.of(chunkPos, data.bounds.minSectionY() + sectionY), layer, packed));
            LuxBenchmarkSupport.count(sky ? "lucistarlink.publish.sky.sections" : "lucistarlink.publish.block.sections");
        }
    }
}
