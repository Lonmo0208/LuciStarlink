package ca.spottedleaf.starlight.common;

import ca.spottedleaf.starlight.common.config.Config;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LuciStarlink 2.0 - the light engine is ScalableLux's (see NOTICE and docs/LS2-PLAN.md); this class is the mod
 * entrypoint that carries our identity, our telemetry and our commands on top of it.
 */
@Mod("lucistarlink")
public class LuciStarlinkEntrypoint {
    public static final Logger LOGGER = LoggerFactory.getLogger("LuciStarlink");

    public LuciStarlinkEntrypoint() {
        Config.init();
        // NOTE: /lucistarlink and LuxTelemetry are wired with @EventBusSubscriber, not registered here:
        // bus 8.x has no register(Class) overload, and register(SomeClass.class) binds to
        // register(Object), which scans instance methods and silently registers nothing.
        LOGGER.info("LuciStarlink 2.0 active (ScalableLux base): telemetry every {}s (0 disables), /lucistarlink for stats and relight",
                Long.getLong("scalablelux.telemetrySeconds", 30L));
        if (Boolean.getBoolean("scalablelux.ownFieldSelfTest")) {
            // R1 acceptance for the new engine's foundation (docs/NEW-ENGINE-TEARDOWN.md): the byte-level round trip
            // between a section's vanilla light layers and the self-owned field, verified cell by cell and timed.
            ca.spottedleaf.starlight.common.light.own.OwnLightField.selfTest();
        }
        if (Boolean.getBoolean("scalablelux.ownFlatSelfTest")) {
            // R5-1: the flat byte-per-cell field (docs/NEW-ENGINE-TEARDOWN.md §13), timed against the base's nibble
            // layout over the structure_cube touch pattern - the number that decides whether R5 is worth building.
            ca.spottedleaf.starlight.common.light.own.OwnFlatField.selfTest();
        }
        // R5-2's OwnSkySweepProbe is called reflectively by the measurement harness at fingerprint time - it has no hook
        // here because a probe running inside the measured window would perturb the reading it exists to explain.
    }
}
