package ca.spottedleaf.starlight.common.compat;

import ca.spottedleaf.starlight.mixin.compat.sable.SableServerLevelAccessor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * "Is this chunk one of Sable's plots?" - the chunks Sable gives its own per-plot light engine, which this mod must
 * leave alone (LuciStarlink 1.x did the same; the criterion and the deferral points are ported).
 */
public final class SableCompat {
    private SableCompat() {
    }

    public static boolean isSablePlotChunk(final Level level, final int chunkX, final int chunkZ) {
        if (!SablePresence.isPresent()) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final SubLevelContainer container = ((SableServerLevelAccessor) serverLevel).lucistarlink$getSablePlotContainer();
        return container != null && container.getPlot(chunkX, chunkZ) != null;
    }
}
