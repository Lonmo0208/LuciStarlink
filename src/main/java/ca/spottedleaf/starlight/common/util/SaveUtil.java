package ca.spottedleaf.starlight.common.util;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.integration.v0.ChunkSystemHooks;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

import java.util.List;
import java.util.ListIterator;

public final class SaveUtil {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void saveVanillaLightHook(final ServerLevel serverLevel, final ChunkAccess chunk, final CompoundTag data) {
        boolean lightCorrect = data.getBoolean("isLightOn");
        data.putBoolean("isLightOn", false);
        try {
            saveVanillaLightHookReal(serverLevel, chunk, data);
            data.putBoolean("isLightOn", lightCorrect);
        } catch (final Throwable ex) {
            // failing to inject is not fatal so we catch anything here. if it fails, it will have correctly set lit to false
            // for Vanilla to relight on load and it will not set our lit tag so we will relight on load
            if (ex instanceof ThreadDeath) {
                throw (ThreadDeath)ex;
            }
            LOGGER.warn("Failed to inject light data into save data for chunk " + chunk.getPos() + ", chunk light will be recalculated on its next load", ex);
        }
    }

    private static void saveVanillaLightHookReal(final ServerLevel serverLevel, final ChunkAccess chunk, final CompoundTag data) {
        boolean avoidLightCopy = ChunkSystemHooks.avoidLightCopy();

        // replace existing lighting data
        SWMRNibbleArray.SaveState[] blockNibbleSaveStates = new SWMRNibbleArray.SaveState[((ExtendedChunk) chunk).scalablelux$getBlockNibbles().length];
        SWMRNibbleArray.SaveState[] skyNibbleSaveStates = new SWMRNibbleArray.SaveState[((ExtendedChunk) chunk).scalablelux$getSkyNibbles().length];
        {
            SWMRNibbleArray[] nibbles = ((ExtendedChunk) chunk).scalablelux$getBlockNibbles();
            for (int i = 0, nibblesLength = nibbles.length; i < nibblesLength; i++) {
                blockNibbleSaveStates[i] = nibbles[i].getSaveState(!avoidLightCopy);
            }
        }
        {
            SWMRNibbleArray[] nibbles = ((ExtendedChunk) chunk).scalablelux$getSkyNibbles();
            for (int i = 0, nibblesLength = nibbles.length; i < nibblesLength; i++) {
                skyNibbleSaveStates[i] = nibbles[i].getSaveState(!avoidLightCopy);
            }
        }

        ListTag sectionsStored = data.getList("sections", 10);

        int minLightSection = WorldUtil.getMinLightSection(serverLevel);
        int maxLightSection = WorldUtil.getMaxLightSection(serverLevel);

        for (Tag tag : sectionsStored) {
            CompoundTag sectionData = (CompoundTag) tag;
            int index = sectionData.getInt("Y") - minLightSection;
            byte[] blockRaw = blockNibbleSaveStates[index] != null ? blockNibbleSaveStates[index].data : null;
            blockNibbleSaveStates[index] = null;
            byte[] skyRaw = skyNibbleSaveStates[index] != null ? skyNibbleSaveStates[index].data : null;
            skyNibbleSaveStates[index] = null;

            sectionData.remove("BlockLight");
            if (blockRaw != null) sectionData.putByteArray("BlockLight", blockRaw);

            sectionData.remove("SkyLight");
            if (skyRaw != null) sectionData.putByteArray("SkyLight", skyRaw);
        }

        // we might still have unsaved data, append them to the section list
        for (int i = minLightSection; i < maxLightSection; i++) {
            int index = i - minLightSection;
            byte[] blockRaw = blockNibbleSaveStates[index] != null ? blockNibbleSaveStates[index].data : null;
            blockNibbleSaveStates[index] = null;
            byte[] skyRaw = skyNibbleSaveStates[index] != null ? skyNibbleSaveStates[index].data : null;
            skyNibbleSaveStates[index] = null;

            if (blockRaw != null || skyRaw != null) {
                CompoundTag sectionData = new CompoundTag();
                if (blockRaw != null) sectionData.putByteArray("BlockLight", blockRaw);
                if (skyRaw != null) sectionData.putByteArray("SkyLight", skyRaw);
                sectionData.putInt("Y", (byte) i);
                sectionsStored.add(sectionData);
            }
        }

//        ListIterator<SerializableChunkData.SectionData> iterator = data.sectionData().listIterator(); // mutable in vanilla
//        while (iterator.hasNext()) {
//            SerializableChunkData.SectionData sectionData = iterator.next();
//            int index = sectionData.y() - WorldUtil.getMinLightSection(serverLevel);
//            byte[] blockRaw = blockNibbleSaveStates[index] != null ? blockNibbleSaveStates[index].data : null;
//            byte[] skyRaw = skyNibbleSaveStates[index] != null ? skyNibbleSaveStates[index].data : null;
//            iterator.set(new SerializableChunkData.SectionData(sectionData.y(), sectionData.chunkSection(), blockRaw != null ? new DataLayer(blockRaw) : null, skyRaw != null ? new DataLayer(skyRaw) : null));
//        }
    }

    public static void loadVanillaLightHook(final ServerLevel world, final ChunkPos chunkPos, final CompoundTag compoundTag, final ProtoChunk into) {
        try {
            loadVanillaLightHookReal(world, chunkPos, compoundTag, into);
        } catch (final Throwable ex) {
            // failing to inject is not fatal so we catch anything here. if it fails, then we simply relight. Not a problem, we get correct
            // lighting in both cases.
            if (ex instanceof ThreadDeath) {
                throw (ThreadDeath)ex;
            }
            LOGGER.warn("Failed to load light for chunk " + chunkPos + ", light will be recalculated", ex);
        }
    }

    private static void loadVanillaLightHookReal(final ServerLevel world, final ChunkPos chunkPos, final CompoundTag compoundTag, final ProtoChunk into) {
        if (into == null) {
            return;
        }
        final int minSection = WorldUtil.getMinLightSection(world);
        final int maxSection = WorldUtil.getMaxLightSection(world);

        ChunkStatus status = into.getPersistedStatus();
        boolean lit = into.isLightCorrect() && status.isOrAfter(ChunkStatus.LIGHT);

        into.setLightCorrect(false); // mark as unlit in case we fail parsing

        SWMRNibbleArray[] blockNibbles = StarLightEngine.getFilledEmptyLight(world);
        SWMRNibbleArray[] skyNibbles = StarLightEngine.getFilledEmptyLight(world);

        if (lit) {
            ListTag sectionsStored = compoundTag.getList("sections", 10);
            for (Tag tag : sectionsStored) {
                CompoundTag sectionData = (CompoundTag) tag;
                int index = sectionData.getInt("Y") - minSection;

                byte[] blockRaw = sectionData.contains("BlockLight", Tag.TAG_BYTE_ARRAY) ? sectionData.getByteArray("BlockLight") : null;
                if (blockRaw != null) blockNibbles[index] = new SWMRNibbleArray(blockRaw);

                byte[] skyRaw = sectionData.contains("SkyLight", Tag.TAG_BYTE_ARRAY) ? sectionData.getByteArray("SkyLight") : null;
                if (skyRaw != null) skyNibbles[index] = new SWMRNibbleArray(skyRaw);
            }
//            for (int i = 0, sectionDataSize = sectionData.size(); i < sectionDataSize; i++) {
//                SerializableChunkData.SectionData section = sectionData.get(i);
//                int y = section.y();
//
//                if (section.blockLight() != null) {
//                    // this is where our diff is
//                    blockNibbles[y - minSection] = SWMRNibbleArray.fromVanilla(section.blockLight()); // clone for data safety
//                }
//
//                if (section.skyLight() != null) {
//                    skyNibbles[y - minSection] = SWMRNibbleArray.fromVanilla(section.skyLight()); // clone for data safety
//                }
//            }
            // workaround vanilla quirk: skylight in sections below sections with initialized skylight is zero
            {
                boolean fillWithZero = false;
                for (int i = skyNibbles.length - 1; i >= 0; i--) {
                    if (!skyNibbles[i].isNullNibbleVisible()) {
                        fillWithZero = true;
                        continue;
                    }
                    if (fillWithZero) {
                        skyNibbles[i].setNonNull();
                        skyNibbles[i].updateVisible();
                    }
                }
            }
        }

        ((ExtendedChunk)into).scalablelux$setBlockNibbles(blockNibbles);
        ((ExtendedChunk)into).scalablelux$setSkyNibbles(skyNibbles);

        into.setLightCorrect(lit); // now we set lit here, only after we've correctly parsed data
    }

    private SaveUtil() {}
}
