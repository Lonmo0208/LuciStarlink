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
                .then(dumpLightCommand())
                .then(dumpPlaneCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dumpPlaneCommand() {
        return Commands.literal("dumpplane")
                .then(Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("x1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                                .executes(LuciStarlinkCommand::dumpPlane)))))));
    }

    /**
     * Prints the raw light values of one horizontal plane, one line per z, so two runs can be diffed to the exact
     * cell. The hash probe says *that* two runs differ; this says *where*, which is what a divergence needs.
     */
    private static int dumpPlane(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        String label = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "label");
        int y = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "y");
        int x1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x1");
        int z1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z1");
        int x2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x2");
        int z2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z2");
        net.minecraft.server.MinecraftServer server = context.getSource().getServer();
        net.minecraft.server.level.ServerLevel level = context.getSource().getLevel();
        server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, new Runnable() {
            private int waited;
            private int quiet;

            @Override
            public void run() {
                waited++;
                boolean idle = !level.getLightEngine().hasLightWork()
                        && !LuxServices.controller().hasPendingRuntimeWork()
                        && !LuxServices.controller().hasPendingWorldgenWork();
                quiet = idle ? quiet + 1 : 0;
                if (quiet < DUMP_QUIESCE_TICKS && waited < DUMP_MAX_WAIT_TICKS) {
                    server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, this));
                    return;
                }
                net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
                net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
                StringBuilder sky = new StringBuilder();
                StringBuilder block = new StringBuilder();
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    sky.setLength(0);
                    block.setLength(0);
                    for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                        pos.set(x, y, z);
                        sky.append(Character.forDigit(lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY)
                                .getLightValue(pos), 16));
                        block.append(Character.forDigit(lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK)
                                .getLightValue(pos), 16));
                    }
                    LuciStarlink.LOGGER.info("LUCIS_LIGHT_PLANE label={} y={} z={} x=[{},{}] sky={} block={}",
                            label, y, z, Math.min(x1, x2), Math.max(x1, x2), sky, block);
                }
            }
        }));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dumpLightCommand() {
        // the y bounds are optional: with them the probe can be aimed at a band that is not affected by the
        // randomly-timed fluid interactions of a generated world (which make a full-column block-light hash
        // irreproducible even for vanilla - see docs/HANDOVER.md, Unresolved 2)
        var y1 = Commands.argument("y1", com.mojang.brigadier.arguments.IntegerArgumentType.integer());
        var y2 = Commands.argument("y2", com.mojang.brigadier.arguments.IntegerArgumentType.integer());
        return Commands.literal("dumplight")
                .then(Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("x1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("z1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(Commands.argument("z2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes(context -> dumpLight(context,
                                                                Integer.MIN_VALUE, Integer.MAX_VALUE))
                                                        .then(Commands.argument("y1", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                                .then(Commands.argument("y2", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                                        .executes(context -> dumpLight(context,
                                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "y1"),
                                                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "y2"))))))))));
    }

    /**
     * Ceiling on how long {@code dumplight} waits for the engine to go quiet before it reads anyway. Worldgen
     * streams for a while after a chunk is forceloaded, and reading the section maps while the light thread is
     * writing them yields hashes that differ between identical runs - which is a property of the probe, not of
     * the engine, and it cost an afternoon: the first version of this command reported non-deterministic block
     * light for every configuration, including ones where the engine never computed block light at all.
     */
    private static final int DUMP_QUIESCE_TICKS = 20;
    private static final int DUMP_MAX_WAIT_TICKS = 1200;

    /**
     * Fingerprints the engine's stored light over a block region into two 64-bit FNV-1a hashes, one per layer.
     * Two runs of the same scenario must produce the same pair; a difference is exactly the "a chunk kept stale
     * light" failure, which is otherwise invisible from the server logs. The line is stable so a test rig can
     * diff it between engine configurations.
     *
     * <p>The engine's section maps are single-writer (the light thread), so the read is deferred until the
     * engine has had {@link #DUMP_QUIESCE_TICKS} consecutive quiet ticks: light engine work queues empty and no
     * pending runtime or worldgen work. Otherwise the probe races the light thread and reports noise.
     */
    private static int dumpLight(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int y1, int y2) {
        String label = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "label");
        int x1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x1");
        int z1 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z1");
        int x2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "x2");
        int z2 = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "z2");
        net.minecraft.server.MinecraftServer server = context.getSource().getServer();
        net.minecraft.server.level.ServerLevel level = context.getSource().getLevel();
        long[] waited = {0L};
        int[] quietTicks = {0};
        server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, new Runnable() {
            @Override
            public void run() {
                waited[0]++;
                boolean quiet = !level.getLightEngine().hasLightWork()
                        && !LuxServices.controller().hasPendingRuntimeWork()
                        && !LuxServices.controller().hasPendingWorldgenWork();
                quietTicks[0] = quiet ? quietTicks[0] + 1 : 0;
                if (quietTicks[0] < DUMP_QUIESCE_TICKS && waited[0] < DUMP_MAX_WAIT_TICKS) {
                    server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, this));
                    return;
                }
                logFingerprint(label, level, x1, z1, x2, z2, y1, y2, waited[0]);
            }
        }));
        return 1;
    }

    private static void logFingerprint(String label, net.minecraft.server.level.ServerLevel level,
                                       int x1, int z1, int x2, int z2, int y1, int y2, long waitedTicks) {
        net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
        net.minecraft.world.level.lighting.LayerLightEventListener sky =
                lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
        net.minecraft.world.level.lighting.LayerLightEventListener block =
                lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        int minY = Math.max(level.getMinBuildHeight(), y1);
        int maxY = Math.min(level.getMaxBuildHeight(), y2);
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
        LuciStarlink.LOGGER.info(String.format(java.util.Locale.ROOT,
                "LUCIS_LIGHT_FINGERPRINT label=%s sky=%016x block=%016x samples=%d y=[%d,%d] quiesceTicks=%d",
                label, skyHash, blockHash, samples, minY, maxY, waitedTicks));
        if (samples == 0L) {
            // An empty box still prints a well-formed fingerprint - the FNV offset basis - which reads like a real
            // measurement. A dev session lost two runs to exactly that: the arguments had been shifted by one, so
            // the label was a coordinate and the box was empty. Say so instead of returning a plausible hash.
            LuciStarlink.LOGGER.warn("LuciStarlink dumplight label={} sampled no cells: the box was empty. "
                            + "Syntax is /lucistarlink dumplight <label> <x1> <z1> <x2> <z2> [y1] [y2] "
                            + "(got x=[{},{}] z=[{},{}] y=[{},{}] in a world with y=[{},{}])",
                    label, Math.min(x1, x2), Math.max(x1, x2), Math.min(z1, z2), Math.max(z1, z2),
                    minY, maxY, level.getMinBuildHeight(), level.getMaxBuildHeight());
        }
    }
}
