package ca.spottedleaf.starlight.mixin.common.world;

import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import ca.spottedleaf.starlight.common.util.SaveUtil;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
public abstract class SerializableChunkDataMixin {

    @WrapOperation(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;isLightCorrect()Z"))
    private static boolean forceLightIncorrectBeforeSave(ChunkAccess instance, Operation<Boolean> original, ServerLevel world, ChunkAccess chunkAccess1) {
        if (world.getLightEngine() instanceof StarLightLightingProvider) {
            return false;
        } else {
            return original.call(instance);
        }
    }

    /**
     * Overwrites vanilla's light data with our own.
     * TODO this needs to be checked on update to account for format changes
     */
    @Inject(
            method = "write",
            at = @At("RETURN")
    )
    private static void prepareSaveLightHook(ServerLevel world, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> cir) {
        if (world.getLightEngine() instanceof StarLightLightingProvider) {
            SaveUtil.saveVanillaLightHook(world, chunk, cir.getReturnValue());
        }
    }

    /**
     * Loads our light data into the returned chunk object from the tag.
     * TODO this needs to be checked on update to account for format changes
     */
    @Inject(
            method = "read",
            at = @At("RETURN")
    )
    private static void loadLightHook(ServerLevel level, PoiManager poiManager, RegionStorageInfo regionStorageInfo, ChunkPos chunkPos, CompoundTag compoundTag, CallbackInfoReturnable<ProtoChunk> cir) {
        if (level.getLightEngine() instanceof StarLightLightingProvider) {
            SaveUtil.loadVanillaLightHook(level, chunkPos, compoundTag, cir.getReturnValue());
        }
    }

    @WrapOperation(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LayerLightEventListener;getDataLayerData(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/world/level/chunk/DataLayer;"), require = 2)
    private static DataLayer noopVanillaLightRead(LayerLightEventListener instance, SectionPos sectionPos, Operation<DataLayer> original, final ServerLevel level, final ChunkAccess chunk) {
        if (level.getLightEngine() instanceof StarLightLightingProvider) {
            return null;
        } else {
            return original.call(instance, sectionPos);
        }
    }
}
