package dev.lucistarlink;

import com.mojang.logging.LogUtils;
import dev.lucistarlink.config.LuxConfig;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.engine.LuxServices;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(LuciStarlink.MODID)
public class LuciStarlink {
    public static final String MODID = "lucistarlink";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LuciStarlink() {
        LuxConfig.register();
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("LuciStarlink light engine booting (region cache budget: {} regions / {} MiB)",
                LuxConfig.maxCachedRegions, LuxConfig.maxCachedRegionBytes >> 20);
        LOGGER.info("LuciStarlink flags: haloPublish={} runtimeHaloChunks={} regionChunks={} runtimeAdoption={} denseIncremental={} inlineRuntime={}",
                LuxFlags.haloPublish, LuxConfig.runtimeHaloChunks, LuxConfig.regionChunks,
                LuxFlags.runtimeAdoption, LuxFlags.denseIncremental, LuxFlags.inlineRuntime);
    }

    private void onServerStopped(ServerStoppedEvent event) {
        LuxServices.shutdownController();
    }
}
