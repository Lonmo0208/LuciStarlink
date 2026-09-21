package ca.spottedleaf.starlight.common.config;

import ca.spottedleaf.starlight.common.thread.SchedulingUtil;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Configuration for LuciStarlink 2.0, in {@code config/lucistarlink.properties}.
 *
 * <p>Two sources, in this order: a system property ({@code scalablelux.<key>}, how the measurement rig and admins
 * override a value without editing files) and the properties file; the defaults below apply when neither is set.
 * Every key is written back to the file on first run so nobody has to guess the names.</p>
 *
 * <p>Key names are inherited from the ScalableLux base ({@code parallelism} is the base's key; the system properties
 * keep the {@code scalablelux.*} prefix they had when this was a branch). Everything else is LuciStarlink's - see
 * docs/CONFIG-MIGRATION.md for how the 1.x keys map.</p>
 */
public class Config {
    private static final Logger LOGGER = LogManager.getLogger();

    /** Light engine worker parallelism (ScalableLux's key). */
    public static final int PARALLELISM;
    /**
     * Install the light engine at all. False leaves every level on the vanilla engine - a compatibility escape
     * hatch, and a way to A/B the engine on a real server. Changing it needs a restart.
     */
    public static final boolean ENABLED;
    /** Seconds between telemetry lines; 0 disables the line. */
    public static final int TELEMETRY_SECONDS;
    /** Development profiler (counters in the log); off by default. */
    public static final boolean PROFILE;
    public static final long PROFILE_INTERVAL_NANOS;
    /**
     * Experimental: how many changed positions the per-change hook batches before running the full scheduling path
     * (1 = the base behaviour, i.e. no batching). Measured neutral on the benchmark workloads, kept for A/B work.
     */
    public static final int BATCH_LIMIT;

    static {
        final Properties properties = new Properties();
        final Properties newProperties = new Properties();
        final Path path = FMLPaths.CONFIGDIR.get().resolve("lucistarlink.properties");
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path, StandardOpenOption.CREATE)) {
                properties.load(in);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if (!SchedulingUtil.isExternallyManaged()) {
            int parallelism = getInt(properties, newProperties, "parallelism", -1);
            if (parallelism < 1) {
                parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
            }
            PARALLELISM = parallelism;
        } else {
            PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
        }

        ENABLED = getBoolean(properties, newProperties, "enabled", "scalablelux.enabled", true);
        TELEMETRY_SECONDS = getIntWithSystemProperty(properties, newProperties,
                "telemetrySeconds", "scalablelux.telemetrySeconds", 30);
        PROFILE = getBoolean(properties, newProperties, "profile", "scalablelux.profile", false);
        PROFILE_INTERVAL_NANOS = getLongWithSystemProperty(properties, newProperties,
                "profileIntervalNanos", "scalablelux.profileIntervalNanos", 2_000_000_000L);
        BATCH_LIMIT = getIntWithSystemProperty(properties, newProperties,
                "batchLimit", "scalablelux.batchLimit", 1);

        if (!newProperties.isEmpty()) {
            try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                newProperties.store(out, "Configuration file for LuciStarlink 2.0");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** The base's {@code init()} hook stays so the entrypoint does not have to change shape. */
    public static void init() {
    }

    private static int getInt(Properties properties, Properties newProperties, String key, int def) {
        try {
            final int i = Integer.parseInt(properties.getProperty(key));
            newProperties.setProperty(key, String.valueOf(i));
            return i;
        } catch (NumberFormatException e) {
            newProperties.setProperty(key, String.valueOf(def));
            return def;
        }
    }

    private static int getIntWithSystemProperty(final Properties properties, final Properties newProperties,
                                                final String key, final String systemProperty, final int def) {
        final String override = System.getProperty(systemProperty);
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring non-numeric -D{}={}", systemProperty, override);
            }
        }
        return getInt(properties, newProperties, key, def);
    }

    private static long getLongWithSystemProperty(final Properties properties, final Properties newProperties,
                                                 final String key, final String systemProperty, final long def) {
        final String override = System.getProperty(systemProperty);
        if (override != null) {
            try {
                return Long.parseLong(override.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Ignoring non-numeric -D{}={}", systemProperty, override);
            }
        }
        final String raw = properties.getProperty(key);
        if (raw != null) {
            try {
                final long parsed = Long.parseLong(raw.trim());
                newProperties.setProperty(key, String.valueOf(parsed));
                return parsed;
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        newProperties.setProperty(key, String.valueOf(def));
        return def;
    }

    private static boolean getBoolean(final Properties properties, final Properties newProperties,
                                      final String key, final String systemProperty, final boolean def) {
        final String override = System.getProperty(systemProperty);
        if (override != null) {
            return Boolean.parseBoolean(override.trim());
        }
        final String raw = properties.getProperty(key);
        if (raw != null) {
            final boolean parsed = Boolean.parseBoolean(raw.trim());
            newProperties.setProperty(key, String.valueOf(parsed));
            return parsed;
        }
        newProperties.setProperty(key, String.valueOf(def));
        return def;
    }
}
