package ca.spottedleaf.starlight.mixin.compat.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches Sable's per-plot container through the accessor method Sable's own mixin adds to {@code ServerLevel}.
 * Applied only when Sable's marker class exists - see {@code LuciStarlinkMixinPlugin} and {@link ca.spottedleaf.starlight.common.compat.SablePresence}.
 */
@Mixin(value = ServerLevel.class, priority = 500)
public interface SableServerLevelAccessor {
    @Dynamic("Added by Sable's ServerLevel mixin")
    @Invoker(value = "sable$getPlotContainer", remap = false)
    SubLevelContainer lucistarlink$getSablePlotContainer();
}
