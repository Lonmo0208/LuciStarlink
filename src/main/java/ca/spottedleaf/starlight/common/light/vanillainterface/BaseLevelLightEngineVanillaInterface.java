package ca.spottedleaf.starlight.common.light.vanillainterface;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.light.ClientStarLightLightingProvider;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.LongAdder;

public class BaseLevelLightEngineVanillaInterface extends LevelLightEngine implements StarLightLightingProvider, ClientStarLightLightingProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("LuciStarlink");

    // Client-side probe. The client half of this engine is the one the server-side rig cannot reach, so the numbers
    // below exist to make it measurable: how many light sections arrive, how many of them find a loaded chunk (only
    // those can dirty the renderer), and what the client's own Level reports at a fixed position.
    // -Dlucistarlink.clientPos=x,y,z turns the per-section line on; it fires only for that section.
    public static final LongAdder clientUpdatesBlock = new LongAdder();
    public static final LongAdder clientUpdatesSky = new LongAdder();
    public static final LongAdder clientUpdatesNoChunk = new LongAdder();

    private static final BlockPos CLIENT_PROBE_POS = parseClientProbePos();

    /** Rate limit for the periodic summary: without it, "the hook never fires" and "the section never matches"
     *  look identical in the log, and they mean opposite things. */
    private static volatile long clientProbeLastLog;

    private static BlockPos parseClientProbePos() {
        final String raw = System.getProperty("lucistarlink.clientPos");
        if (raw == null) {
            return null;
        }
        final String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    protected void probeClientLight(final LightLayer lightType, final SectionPos pos, final DataLayer nibble, final ChunkAccess chunk) {
        if (lightType == LightLayer.BLOCK) {
            clientUpdatesBlock.increment();
        } else {
            clientUpdatesSky.increment();
        }
        if (chunk == null) {
            clientUpdatesNoChunk.increment();
        }
        final BlockPos probe = CLIENT_PROBE_POS;
        if (probe == null) {
            return;
        }
        final long nowNanos = System.nanoTime();
        if (nowNanos - clientProbeLastLog > 2_000_000_000L) {
            clientProbeLastLog = nowNanos;
            LOGGER.info("ClientLightProbe tick blk=" + clientUpdatesBlock.sum() + " sky=" + clientUpdatesSky.sum()
                    + " noChunk=" + clientUpdatesNoChunk.sum()
                    + " last=" + lightType + "@" + pos.x() + "," + pos.y() + "," + pos.z());
        }
        if (pos.x() != (probe.getX() >> 4) || pos.z() != (probe.getZ() >> 4) || pos.y() != (probe.getY() >> 4)) {
            return;
        }
        try {
            final Level level = this.lightEngine.getWorld();
            LOGGER.info("ClientLightProbe " + lightType + " sec=" + pos.x() + "," + pos.y() + "," + pos.z()
                    + " chunkNull=" + (chunk == null) + " nibbleNull=" + (nibble == null)
                    + " block=" + level.getBrightness(LightLayer.BLOCK, probe)
                    + " sky=" + level.getBrightness(LightLayer.SKY, probe)
                    + " raw=" + level.getRawBrightness(probe, 0)
                    + " totals blk=" + clientUpdatesBlock.sum() + " sky=" + clientUpdatesSky.sum()
                    + " noChunk=" + clientUpdatesNoChunk.sum());
        } catch (final Throwable ex) {
            LOGGER.info("ClientLightProbe readback failed " + ex);
        }
    }

    protected final StarLightInterface lightEngine;
    protected final LongOpenHashSet lightingEnabledChunks = new LongOpenHashSet();
    protected final Long2ObjectOpenHashMap<SWMRNibbleArray[]> blockLightMap = new Long2ObjectOpenHashMap<>();
    protected final Long2ObjectOpenHashMap<SWMRNibbleArray[]> skyLightMap = new Long2ObjectOpenHashMap<>();

    public BaseLevelLightEngineVanillaInterface(LightChunkGetter chunkSource, boolean hasBlockLight, boolean hasSkyLight) {
        super(chunkSource, false, false);

        // avoid ClassCastException in cases where custom LightChunkGetters do not return a Level from getLevel()
        if (chunkSource.getLevel() instanceof Level) {
            this.lightEngine = new StarLightInterface(chunkSource, hasSkyLight, hasBlockLight, this);
        } else {
            this.lightEngine = new StarLightInterface(null, hasSkyLight, hasBlockLight, this);
        }
    }

    @Override
    public void checkBlock(BlockPos pos) {
        CommonLightEngineUtils.checkBlock(this, pos);
    }

    @Override
    public boolean hasLightWork() {
        return CommonLightEngineUtils.hasLightWork(this);
    }

    @Override
    public int runLightUpdates() {
        return CommonLightEngineUtils.runLightUpdates(this);
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean sectionEmpty) {
        CommonLightEngineUtils.updateSectionStatus(this, pos, sectionEmpty);
    }

    @Override
    public void setLightEnabled(ChunkPos pos, boolean enable) {
        CommonLightEngineUtils.setLightEnabled(this, pos, enable);
    }

    @Override
    public void propagateLightSources(ChunkPos pos) {
        CommonLightEngineUtils.propagateLightSources(this, pos);
    }

    @Override
    public LayerLightEventListener getLayerListener(LightLayer layer) {
        return CommonLightEngineUtils.getLayerListener(this, layer);
    }

    @Override
    public String getDebugData(LightLayer layer, SectionPos pos) {
        return CommonLightEngineUtils.getDebugData(this, layer, pos);
    }

    @Override
    public LayerLightSectionStorage.SectionType getDebugSectionType(LightLayer layer, SectionPos pos) {
        return CommonLightEngineUtils.getDebugSectionType(this, layer, pos);
    }

    @Override
    public void queueSectionData(LightLayer layer, SectionPos pos, @Nullable DataLayer data) {
        CommonLightEngineUtils.queueSectionData(this, layer, pos, data);
    }

    @Override
    public void retainData(ChunkPos pos, boolean retain) {
        CommonLightEngineUtils.retainData(this, pos, retain);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int skyDampen) {
        return CommonLightEngineUtils.getRawBrightness(this, pos, skyDampen);
    }

    @Override
    public boolean lightOnInSection(SectionPos sectionPos) {
        return CommonLightEngineUtils.lightOnInSection(this, sectionPos);
    }

    @Override
    public int getLightSectionCount() {
        return super.getLightSectionCount(); // use vanilla impl
    }

    @Override
    public int getMinLightSection() {
        return super.getMinLightSection(); // use vanilla impl
    }

    @Override
    public int getMaxLightSection() {
        return super.getMaxLightSection(); // use vanilla impl
    }

    @Override
    public void updateSectionStatus(BlockPos pos, boolean sectionEmpty) {
        super.updateSectionStatus(pos, sectionEmpty); // use vanilla impl
    }

    @Override
    public StarLightInterface scalablelux$getLightEngine() {
        return this.lightEngine;
    }

    @Override
    public LongOpenHashSet scalablelux$getLightingEnabledChunks() {
        return this.lightingEnabledChunks;
    }

    @Override
    public Long2ObjectOpenHashMap<SWMRNibbleArray[]> scalablelux$getBlockLightMap() {
        return this.blockLightMap;
    }

    @Override
    public Long2ObjectOpenHashMap<SWMRNibbleArray[]> scalablelux$getSkyLightMap() {
        return this.skyLightMap;
    }

    @Override
    public void scalablelux$clientUpdateLight(final LightLayer lightType, final SectionPos pos,
                                              final DataLayer nibble, final boolean trustEdges) {
        // data storage changed with new light impl
        final ChunkAccess chunk = this.scalablelux$getLightEngine().getAnyChunkNow(pos.getX(), pos.getZ());
        this.probeClientLight(lightType, pos, nibble, chunk);
        switch (lightType) {
            case BLOCK: {
                final SWMRNibbleArray[] blockNibbles = this.blockLightMap.computeIfAbsent(CoordinateUtils.getChunkKey(pos), (final long keyInMap) -> {
                    return StarLightEngine.getFilledEmptyLight(this.lightEngine.getWorld());
                });

                blockNibbles[pos.getY() - WorldUtil.getMinLightSection(this.lightEngine.getWorld())] = SWMRNibbleArray.fromVanilla(nibble);

                if (chunk != null) {
                    ((ExtendedChunk)chunk).scalablelux$setBlockNibbles(blockNibbles);
                    this.lightEngine.getLightAccess().onLightUpdate(LightLayer.BLOCK, pos);
                }
                break;
            }
            case SKY: {
                final SWMRNibbleArray[] skyNibbles = this.skyLightMap.computeIfAbsent(CoordinateUtils.getChunkKey(pos), (final long keyInMap) -> {
                    return StarLightEngine.getFilledEmptyLight(this.lightEngine.getWorld());
                });

                skyNibbles[pos.getY() - WorldUtil.getMinLightSection(this.lightEngine.getWorld())] = SWMRNibbleArray.fromVanilla(nibble);

                if (chunk != null) {
                    ((ExtendedChunk)chunk).scalablelux$setSkyNibbles(skyNibbles);
                    this.lightEngine.getLightAccess().onLightUpdate(LightLayer.SKY, pos);
                }
                break;
            }
        }
    }

    @Override
    public void scalablelux$clientRemoveLightData(final ChunkPos chunkPos) {
        this.blockLightMap.remove(CoordinateUtils.getChunkKey(chunkPos));
        this.skyLightMap.remove(CoordinateUtils.getChunkKey(chunkPos));
    }

    @Override
    public void scalablelux$clientChunkLoad(final ChunkPos pos, final LevelChunk chunk) {
        final long key = CoordinateUtils.getChunkKey(pos);
        final SWMRNibbleArray[] blockNibbles = this.blockLightMap.get(key);
        final SWMRNibbleArray[] skyNibbles = this.skyLightMap.get(key);
        if (blockNibbles != null) {
            ((ExtendedChunk)chunk).scalablelux$setBlockNibbles(blockNibbles);
        }
        if (skyNibbles != null) {
            ((ExtendedChunk)chunk).scalablelux$setSkyNibbles(skyNibbles);
        }
    }
}
