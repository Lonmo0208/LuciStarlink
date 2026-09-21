package ca.spottedleaf.starlight.common;

import ca.spottedleaf.starlight.common.config.Config;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("scalablelux")
public class ScalableLuxEntrypoint {
    public static final Logger LOGGER = LoggerFactory.getLogger("ScalableLux");

    public ScalableLuxEntrypoint() {
        Config.init();
        // NOTE: /scalablelux and LuxTelemetry are wired with @EventBusSubscriber, not registered here:
        // bus 8.x has no register(Class) overload, and register(SomeClass.class) binds to
        // register(Object), which scans instance methods and silently registers nothing.
        LOGGER.info("ScalableLux active: telemetry every {}s (0 disables), /scalablelux for stats and relight",
                Long.getLong("scalablelux.telemetrySeconds", 30L));
    }
}
