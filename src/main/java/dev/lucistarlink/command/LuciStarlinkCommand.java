package dev.lucistarlink.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.lucistarlink.LuciStarlink;
import dev.lucistarlink.config.LuxConfig;
import dev.lucistarlink.light.LuxFlags;
import dev.lucistarlink.light.engine.LuxServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * In-game diagnostics: {@code /lucistarlink status} prints the same one-line engine state the verbose telemetry
 * logs, so a server admin or player can see what the engine is doing (region cache footprint, queued work, halo
 * publications, external refreshes, save-time light forcing) without turning on logging and waiting 30 s.
 */
@EventBusSubscriber(modid = LuciStarlink.MODID)
public final class LuciStarlinkCommand {
    private LuciStarlinkCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(LuciStarlinkCommand.root());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("lucistarlink")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(
                            () -> Component.literal("LuciStarlink: " + LuxServices.controller().statusReport()), false);
                    return 1;
                }))
                .then(Commands.literal("flags").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                            "LuciStarlink flags: haloPublish=%s worldgenHaloPublish=%s runtimeHaloChunks=%d regionChunks=%d "
                                    + "forceLightIncorrectOnSave=%s runtimeAdoption=%s denseIncremental=%s inlineRuntime=%s",
                            LuxFlags.haloPublish, LuxFlags.worldgenHaloPublish, LuxConfig.runtimeHaloChunks,
                            LuxConfig.regionChunks, LuxConfig.forceLightIncorrectOnSave, LuxFlags.runtimeAdoption,
                            LuxFlags.denseIncremental, LuxFlags.inlineRuntime)), false);
                    return 1;
                }))
                .then(dumpLightCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dumpLightCommand() {
        return Commands.literal("dumplight")
                .then(Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("x1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes(LuciStarlinkCommand::dumpLight))))));
    }

    /**
     * Fingerprints the engine's stored light over a block region into two 64-bit FNV-1a hashes, one per layer.
     * Two runs of the same scenario must produce the same pair; a difference is exactly the "a chunk kept stale
     * light" failure, which is otherwise invisible from the server logs. The line is stable so a test rig can
     * diff it between engine configurations.
     */
    private static int dumpLight(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        String label = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "label");
        int x1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x1");
        int z1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z1");
        int x2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x2");
        int z2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z2");
        net.minecraft.server.level.ServerLevel level = context.getSource().getLevel();
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        net.minecraft.world.level.lighting.LayerLightEventListener sky =
                lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        net.minecraft.world.level.lighting.LayerLightEventListener block =
                lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        long skyHash = 0xcbf29ce484222325L;
        long blockHash = 0xcbf29ce484222325L;
        long samples = 0L;
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = minY; y < maxY; y++) {
                    pos.set(x, y, z);
                    skyHash = (skyHash ^ sky.getLightValue(pos)) * 0x100000001b3L;
                    blockHash = (blockHash ^ block.getLightValue(pos)) * 0x100000001b3L;
                    samples++;
                }
            }
        }
        String line = String.format(java.util.Locale.ROOT,
                "LUCIS_LIGHT_FINGERPRINT label=%s sky=%016x block=%016x samples=%d",
                label, skyHash, blockHash, samples);
        LuciStarlink.LOGGER.info("{}", line);
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
