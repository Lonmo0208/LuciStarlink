package ca.spottedleaf.starlight.mixin.common.chunk;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second link of the server-side residue probe.
 *
 * <p>Our engine's {@code onLightUpdate} reaches {@code ServerChunkCache.onLightUpdate}, which defers the work to the
 * main thread and there calls {@code ChunkHolder.sectionLightChanged(layer, sectionY)} — and that method returns
 * <b>silently</b> in two cases: no chunk at INITIALIZE_LIGHT, or no ticking chunk. Its only other early return is the
 * section-range guard, which cannot fire for an in-range section.</p>
 *
 * <p>This is exactly the distinction the client-side probe cannot make on its own: "the notify never arrived" and
 * "the notify arrived and was thrown away one frame later" produce the identical client observation. -Dlucistarlink
 * .clientPos=x,y,z arms this and the other two probe sites with the same one section.</p>
 */
@Mixin(ChunkHolder.class)
public class ChunkHolderProbeMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("LuciStarlink");
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

    @Inject(method = "sectionLightChanged", at = @At("HEAD"))
    private void lucis$probeSectionLightChanged(final LightLayer layer, final int sectionY, final CallbackInfo ci) {
        final int[] probe = LUCIS_PROBE_SECTION;
        if (probe == null) {
            return;
        }
        final ChunkHolder self = (ChunkHolder) (Object) this;
        if (sectionY != probe[1] || self.getPos().x != probe[0] || self.getPos().z != probe[2]) {
            return;
        }
        LOGGER.info("ServerLightProbe sectionLightChanged " + layer + " y=" + sectionY
                + " chunkPresent=" + (self.getChunkIfPresent(ChunkStatus.INITIALIZE_LIGHT) != null)
                + " ticking=" + (self.getTickingChunk() != null));
    }
}
