package ca.spottedleaf.starlight.common;

import ca.spottedleaf.starlight.common.command.LuciStarlinkCommand;
import ca.spottedleaf.starlight.common.config.Config;
import ca.spottedleaf.starlight.common.debug.LuxTelemetry;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rig-only entrypoint. Build with {@code -Pmod_id=lucistarlinkrig} to produce a jar that the measurement rig can
 * load next to the LuciStarlink 1.x harness mod (whose id is {@code lucistarlink}); publishing always uses
 * {@link LuciStarlinkEntrypoint} and the id {@code lucistarlink}. The handlers are registered as listeners here
 * instead of via {@code @EventBusSubscriber}, because that annotation's modid is a compile-time string and would
 * not match the rig id.
 */
@Mod("lucistarlinkrig")
public class LuciStarlinkRigEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("LuciStarlink");

    public LuciStarlinkRigEntrypoint() {
        Config.init();
        NeoForge.EVENT_BUS.addListener(LuciStarlinkCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(LuxTelemetry::onServerTick);
        LOGGER.info("LuciStarlink 2.0 (rig entrypoint, modId lucistarlinkrig) active on the ScalableLux base");
        if (Boolean.getBoolean("scalablelux.ownFieldSelfTest")) {
            // R1 acceptance for the new engine's foundation (docs/NEW-ENGINE-TEARDOWN.md); wired into BOTH entrypoints
            // because the rig variant replaces the published one, so a hook in only one of them does nothing here.
            ca.spottedleaf.starlight.common.light.own.OwnLightField.selfTest();
        }
        if (Boolean.getBoolean("scalablelux.ownFlatSelfTest")) {
            // R5-1: the flat byte-per-cell field (docs/NEW-ENGINE-TEARDOWN.md §13). Its touch cost against the base's
            // two-hop nibble layout is what decides whether the R5 rewrite is worth building at all.
            ca.spottedleaf.starlight.common.light.own.OwnFlatField.selfTest();
        }
    }
}
