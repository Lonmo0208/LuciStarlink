package ca.spottedleaf.starlight.common.debug;

import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * One telemetry line per interval and level, for servers that run for weeks: queue depth, dirty
 * positions and the pooled propagator counts. ScalableLux holds light per chunk so its memory is
 * bounded by construction, but it has no way to see whether the engine is keeping up - this is that
 * visibility, and it is the port of the Lucis telemetry line.
 *
 * <p>Interval: {@code -Dscalablelux.telemetrySeconds} (default 30; 0 disables). Reads are
 * approximate by design - taken without blocking the engine, and a stale-by-one figure is fine for
 * a health line. Lines are prefixed {@code SLTELEM} so they can be grepped or filtered out.</p>
 *
 * <p>Driven from both the server tick (so an idle server still reports) and the light engine's own
 * update entry point (so the line always exists even if the tick hook is unavailable).</p>
 */
@EventBusSubscriber(modid = "lucistarlink")
public final class LuxTelemetry {

    private static final long INTERVAL_NANOS =
            Math.max(0L, Long.getLong("scalablelux.telemetrySeconds", 30L)) * 1_000_000_000L;

    private static volatile long lastPrint = System.nanoTime();

    private LuxTelemetry() {}

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        if (INTERVAL_NANOS <= 0L) {
            return;
        }
        final MinecraftServer server = event.getServer();
        for (final ServerLevel level : server.getAllLevels()) {
            print(level, System.nanoTime(), true);
        }
    }

    /** Engine-side driver: called from the light engine's update entry point. */
    public static void maybePrint(final Level level) {
        if (INTERVAL_NANOS <= 0L || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        print(serverLevel, System.nanoTime(), false);
    }

    private static void print(final ServerLevel level, final long now, final boolean fromTick) {
        if (now - lastPrint < INTERVAL_NANOS) {
            return;
        }
        lastPrint = now;
        final String stats = statsFor(level);
        if (stats != null) {
            System.out.println("SLTELEM dim=" + level.dimension().location() + " " + stats);
        } else if (!fromTick) {
            System.out.println("SLTELEM dim=" + level.dimension().location()
                    + " engine=" + level.getChunkSource().getLightEngine().getClass().getSimpleName());
        }
    }

    /** The one-line engine state for a level, or null when the level does not run this engine. */
    public static String statsFor(final ServerLevel level) {
        if (!(level.getChunkSource().getLightEngine() instanceof StarLightLightingProvider provider)) {
            return null;
        }
        final StarLightInterface engine = provider.scalablelux$getLightEngine();
        return engine == null ? null : engine.lucisStats();
    }
}
