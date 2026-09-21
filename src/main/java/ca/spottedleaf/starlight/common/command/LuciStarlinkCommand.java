package ca.spottedleaf.starlight.common.command;

import ca.spottedleaf.starlight.common.debug.LuxProfiler;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The in-game introspection this engine did not have: {@code /scalablelux stats | light | relight}.
 *
 * <ul>
 *   <li>{@code stats} - the same figures as the telemetry line, on demand, for the level the
 *       command runs in.</li>
 *   <li>{@code light <pos>} - block/sky/raw light at a position plus whether its chunk claims to
 *       be lit, i.e. enough to answer "is this dark spot our problem or the map's".</li>
 *   <li>{@code relight <radius>} - the repair path for a world whose stored light is already
 *       wrong: mark the chunks light-incorrect and hand them to {@code lightChunk(chunk, false)},
 *       which is exactly what vanilla's own LIGHT generation step does. Radius is bounded (8
 *       chunks = 289) so a mistyped command cannot stall the server; the radius should cover the
 *       damaged area plus a couple of chunks of margin, because neighbours are what make edge
 *       light correct.</li>
 * </ul>
 *
 * <p>Requires permission level 2 (op). Marked here honestly: {@code stats} and {@code light} are
 * read-only and safe; {@code relight} recomputes light for loaded chunks only (unloaded ones are
 * skipped, so run it where the damage is visible).</p>
 */
@EventBusSubscriber(modid = "lucistarlink")
public final class LuciStarlinkCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("LuciStarlink");

    private LuciStarlinkCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("lucistarlink")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats").executes(context -> {
                    final CommandSourceStack source = context.getSource();
                    final StarLightInterface engine = engineOf(source.getLevel());
                    if (engine == null) {
                        source.sendFailure(Component.literal("LuciStarlink: this level does not run the ScalableLux light engine"));
                        return 0;
                    }
                    source.sendSuccess(() -> Component.literal("ScalableLux " + engine.lucisStats()
                            + " batchLimit=" + Integer.getInteger("scalablelux.batchLimit", 1)
                            + " profile=" + LuxProfiler.enabled()), false);
                    return 1;
                }))
                .then(Commands.literal("light")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            final CommandSourceStack source = context.getSource();
                            final ServerLevel level = source.getLevel();
                            final BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                            final LevelChunk chunk = level.getChunkAt(pos);
                            source.sendSuccess(() -> Component.literal("LuciStarlink light " + pos.toShortString()
                                    + " block=" + level.getBrightness(LightLayer.BLOCK, pos)
                                    + " sky=" + level.getBrightness(LightLayer.SKY, pos)
                                    + " raw=" + level.getRawBrightness(pos, 0)
                                    + " lightCorrect=" + chunk.isLightCorrect()), false);
                            return 1;
                        })))
                .then(Commands.literal("relight")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8)).executes(context -> {
                            final CommandSourceStack source = context.getSource();
                            final ServerLevel level = source.getLevel();
                            final StarLightInterface engine = engineOf(level);
                            if (engine == null) {
                                source.sendFailure(Component.literal("LuciStarlink: this level does not run the ScalableLux light engine"));
                                return 0;
                            }
                            final int radius = IntegerArgumentType.getInteger(context, "radius");
                            final BlockPos origin = BlockPos.containing(source.getPosition());
                            final int centerX = origin.getX() >> 4;
                            final int centerZ = origin.getZ() >> 4;

                            final List<LevelChunk> chunks = new ArrayList<>();
                            for (int dx = -radius; dx <= radius; dx++) {
                                for (int dz = -radius; dz <= radius; dz++) {
                                    final ChunkAccess chunk = level.getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                                    if (chunk instanceof LevelChunk full && full.isLightCorrect()) {
                                        chunks.add(full);
                                    }
                                }
                            }
                            if (chunks.isEmpty()) {
                                source.sendSuccess(() -> Component.literal("LuciStarlink: no loaded, light-correct chunk in radius "
                                        + radius + " around " + centerX + "," + centerZ + " - nothing to relight"), false);
                                return 1;
                            }
                            for (final LevelChunk chunk : chunks) {
                                chunk.setLightCorrect(false);
                            }
                            final ThreadedLevelLightEngine lightEngine =
                                    (ThreadedLevelLightEngine) level.getChunkSource().getLightEngine();
                            final CompletableFuture<?>[] futures = new CompletableFuture<?>[chunks.size()];
                            for (int i = 0; i < chunks.size(); i++) {
                                futures[i] = lightEngine.lightChunk(chunks.get(i), false);
                            }
                            final int count = chunks.size();
                            source.sendSuccess(() -> Component.literal("LuciStarlink: relighting " + count
                                    + " chunks around " + centerX + "," + centerZ), false);
                            CompletableFuture.allOf(futures).whenComplete((ignored, throwable) -> source.getServer().execute(() -> {
                                if (throwable != null) {
                                    source.sendFailure(Component.literal("LuciStarlink relight failed: " + throwable));
                                } else {
                                    source.sendSuccess(() -> Component.literal("LuciStarlink: re-lit " + count
                                            + " chunks around " + centerX + "," + centerZ), true);
                                }
                            }));
                            return 1;
                        }))));
        LOGGER.debug("Registered /lucistarlink (stats, light, relight)");
    }

    private static StarLightInterface engineOf(final ServerLevel level) {
        if (level.getChunkSource().getLightEngine() instanceof StarLightLightingProvider provider) {
            return provider.scalablelux$getLightEngine();
        }
        return null;
    }
}
