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
    }
}
