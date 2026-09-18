package dev.lucistarlink.test;

import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.config.LuxConfig;
import dev.lucistarlink.light.engine.LuxServices;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@EventBusSubscriber(modid = LuciStarlink.MODID)
public final class LuxClientLoadDiagnostics {
    private static final AtomicBoolean PLAYER_LOGGED = new AtomicBoolean();
    private static volatile long startedAtNanos;

    private LuxClientLoadDiagnostics() {
    }

    @SubscribeEvent
    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (event.getServer().isDedicatedServer()) {
            return;
        }
        LuxServices.resetController();
        if (!enabled()) {
            return;
        }
        LuxBenchmarkSupport.reset();
        PLAYER_LOGGED.set(false);
        startedAtNanos = System.nanoTime();
        LuciStarlink.LOGGER.info("LUCIS_CLIENT_MEASURE_START integrated=true");
    }

    @SubscribeEvent
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || startedAtNanos == 0L || !PLAYER_LOGGED.compareAndSet(false, true)) {
            return;
        }
        logSnapshot("player_join", System.nanoTime());
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        if (!enabled() || startedAtNanos == 0L || event.getServer().isDedicatedServer()) {
            return;
        }
        logSnapshot("server_stopping", System.nanoTime());
        startedAtNanos = 0L;
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        if (event.getServer().isDedicatedServer()) {
            return;
        }
        // controller() would lazily build a replacement engine just to tear it down.
        LuxServices.shutdownController();
    }

    private static void logSnapshot(String phase, long nowNanos) {
        double elapsedMs = (nowNanos - startedAtNanos) / 1_000_000.0;
        LuxBenchmarkSupport.Snapshot snapshot = LuxBenchmarkSupport.snapshot();
        LuciStarlink.LOGGER.info("LUCIS_CLIENT_MEASURE phase={} elapsed_ms={} metrics={} counters={}",
                phase,
                format(elapsedMs),
                formatMetrics(snapshot.metrics()),
                snapshot.counters());
    }

    private static boolean enabled() {
        return Boolean.getBoolean("lucistarlink.clientMeasure") && LuxConfig.debug;
    }

    private static String formatMetrics(Map<String, LuxBenchmarkSupport.Metric> metrics) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, LuxBenchmarkSupport.Metric> entry : metrics.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            LuxBenchmarkSupport.Metric metric = entry.getValue();
            builder.append(entry.getKey())
                    .append("=")
                    .append(format(metric.millis()))
                    .append("ms/")
                    .append(metric.calls());
        }
        return builder.append("}").toString();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
