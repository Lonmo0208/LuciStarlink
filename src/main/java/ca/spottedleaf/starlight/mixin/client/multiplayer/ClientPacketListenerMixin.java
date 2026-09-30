package ca.spottedleaf.starlight.mixin.client.multiplayer;

import ca.spottedleaf.starlight.common.light.ClientStarLightLightingProvider;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.core.SectionPos;
import net.minecraft.network.Connection;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;

@Mixin(value = ClientPacketListener.class, priority = 1001)
public abstract class ClientPacketListenerMixin extends ClientCommonPacketListenerImpl implements ClientGamePacketListener, TickablePacketListener {

    protected ClientPacketListenerMixin(Minecraft minecraft, Connection connection, CommonListenerCookie commonListenerCookie) {
        super(minecraft, connection, commonListenerCookie);
    }

    /*
      The call behaviors in the packet handler are much more clear about how they should affect the light engine,
      and as a result makes the client light load/unload more reliable
    */

    @Shadow
    private ClientLevel level;

    /*
     * Third link of the residue probe, and the only one that can settle the wire. The two server-side probes say the
     * section bit was set on the holder; the per-section client probe says the section never arrived. Those cannot
     * both be true, so read what was actually put on the wire instead of what either side believes it did: the packet
     * carries its masks, and the masks are the truth. -Dlucistarlink.clientPos=x,y,z arms this and the other two.
     */
    private static final Logger LUCIS_PROBE_LOGGER = LoggerFactory.getLogger("LuciStarlink");
    private static final int[] LUCIS_PROBE_SECTION = lucis$parseProbeSection();

    private static int[] lucis$parseProbeSection() {
        final String raw = System.getProperty("lucistarlink.clientPos");
        if (raw == null) {
            return null;
        }
        final String[] parts = raw.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {
                Integer.parseInt(parts[0].trim()) >> 4,
                Integer.parseInt(parts[1].trim()) >> 4,
                Integer.parseInt(parts[2].trim()) >> 4
            };
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    /** Renders a mask as the light-section Ys it actually selects: bit i means {@code minLightSection + i}. */
    private static String lucis$maskSections(final BitSet mask, final int minLightSection, final int count) {
        if (mask == null) {
            return "null";
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (mask.get(i)) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(minLightSection + i);
            }
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    private void lucis$probeLightData(final int chunkX, final int chunkZ, final ClientboundLightUpdatePacketData data) {
        final int[] probe = LUCIS_PROBE_SECTION;
        if (probe == null || data == null || chunkX != probe[0] || chunkZ != probe[2]) {
            return;
        }
        final LevelLightEngine engine = this.level.getChunkSource().getLightEngine();
        final int min = engine.getMinLightSection();
        final int count = engine.getLightSectionCount();
        LUCIS_PROBE_LOGGER.info("ClientLightPacket chunk=" + chunkX + "," + chunkZ
                + " minLightSection=" + min + " count=" + count
                + " blockMask=[" + lucis$maskSections(data.getBlockYMask(), min, count) + "]"
                + " emptyBlockMask=[" + lucis$maskSections(data.getEmptyBlockYMask(), min, count) + "]"
                + " skyMask=[" + lucis$maskSections(data.getSkyYMask(), min, count) + "]"
                + " emptySkyMask=[" + lucis$maskSections(data.getEmptySkyYMask(), min, count) + "]"
                + " blockUpdates=" + data.getBlockUpdates().size()
                + " skyUpdates=" + data.getSkyUpdates().size());
    }

    @Inject(method = "handleLightUpdatePacket", at = @At("HEAD"))
    private void lucis$probeLightUpdatePacket(final ClientboundLightUpdatePacket packet, final CallbackInfo ci) {
        this.lucis$probeLightData(packet.getX(), packet.getZ(), packet.getLightData());
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void lucis$probeLevelChunkWithLight(final ClientboundLevelChunkWithLightPacket packet, final CallbackInfo ci) {
        this.lucis$probeLightData(packet.getX(), packet.getZ(), packet.getLightData());
    }

    /*
      Now in 1.18 Mojang has added logic to delay rendering chunks until their lighting is ready (as they are delaying
      light updates). Fortunately for us, Starlight doesn't take any kind of hit loading in light data. So we have no reason
      to delay the light updates at all (and we shouldn't delay them or else desync might occur - such as with block updates).
     */

    @Shadow
    protected abstract void applyLightData(final int chunkX, final int chunkZ, final ClientboundLightUpdatePacketData clientboundLightUpdatePacketData);

    @Shadow
    protected abstract void enableChunkLight(final LevelChunk levelChunk, final int chunkX, final int chunkZ);

    /**
     * Call the runnable immediately to prevent desync
     * @author Spottedleaf
     */
    @WrapOperation(
            method = "handleLightUpdatePacket",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V"
            )
    )
    private void starlightCallUpdateImmediately(final ClientLevel instance, final Runnable runnable, final Operation<Void> original) {
        if (this.level.getChunkSource().getLightEngine() instanceof ClientStarLightLightingProvider clientStarLightLightingProvider) {
            runnable.run();
        } else {
            original.call(instance, runnable);
        }
    }

    /**
     * Re-route light update packet to our own logic
     * @author Spottedleaf
     */
    @WrapOperation(
            method = "readSectionList",
            at = @At(
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;queueSectionData(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/DataLayer;)V",
                    value = "INVOKE",
                    ordinal = 0
            )
    )
    private void loadLightDataHook(final LevelLightEngine lightEngine, final LightLayer lightType, final SectionPos pos,
                                   final @Nullable DataLayer nibble, final Operation<Void> original) {
        if (this.level.getChunkSource().getLightEngine() instanceof ClientStarLightLightingProvider clientStarLightLightingProvider) {
            clientStarLightLightingProvider.scalablelux$clientUpdateLight(lightType, pos, nibble, true);
        } else {
            original.call(lightEngine, lightType, pos, nibble);
        }
    }


    /**
     * Avoid calling Vanilla's logic here, and instead call our own.
     * @author Spottedleaf
     */
    @WrapOperation(
            method = "handleForgetLevelChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;queueLightRemoval(Lnet/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket;)V"
            )
    )
    private void unloadLightDataHook(final ClientPacketListener instance, final ClientboundForgetLevelChunkPacket packet, final Operation<Void> original) {
        if (this.level.getChunkSource().getLightEngine() instanceof ClientStarLightLightingProvider clientStarLightLightingProvider) {
            clientStarLightLightingProvider.scalablelux$clientRemoveLightData(new ChunkPos(packet.pos().x, packet.pos().z));
        } else {
            original.call(instance, packet);
        }
    }

    /**
     * Don't call vanilla's load logic
     */
    @WrapOperation(
            method = "handleLevelChunkWithLight",
            at = @At(
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V",
                    value = "INVOKE",
                    ordinal = 0
            )
    )
    private void postChunkLoadHookRedirect(final ClientLevel instance, final Runnable runnable, Operation<Void> original) {
        if (this.level.getChunkSource().getLightEngine() instanceof ClientStarLightLightingProvider clientStarLightLightingProvider) {
            // don't call vanilla's logic, see below
        } else {
            original.call(instance, runnable);
        }
    }

    /**
     * Hook for loading in a chunk to the world
     * @author Spottedleaf
     */
    @Inject(
            method = "handleLevelChunkWithLight",
            at = @At(
                    value = "RETURN"
            )
    )
    private void postChunkLoadHook(final ClientboundLevelChunkWithLightPacket packet, final CallbackInfo ci) {
        if (this.level.getChunkSource().getLightEngine() instanceof ClientStarLightLightingProvider clientStarLightLightingProvider) {
            final int chunkX = packet.getX();
            final int chunkZ = packet.getZ();
            final LevelChunk chunk = this.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                // failed to load
                return;
            }
            // load in light data from packet immediately
            this.applyLightData(chunkX, chunkZ, packet.getLightData());
            clientStarLightLightingProvider.scalablelux$clientChunkLoad(new ChunkPos(chunkX, chunkZ), chunk);

            // we need this for the update chunk status call, so that it can tell starlight what sections are empty and such
            this.enableChunkLight(chunk, chunkX, chunkZ);

//            this.minecraft.levelRenderer.onChunkReadyToRender(chunk.getPos());
        }
    }
}
