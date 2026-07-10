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
    }
}
