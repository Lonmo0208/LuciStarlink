package dev.lucistarlink.mixin;

import dev.lucistarlink.light.engine.LuxServices;
import dev.lucistarlink.test.LuxBenchmarkSupport;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    ThreadedLevelLightEngine lightEngine;

    @Inject(method = "tick", at = @At("TAIL"))
    private void lucistarlink$tick(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        long startedAt = LuxBenchmarkSupport.start();
        LuxServices.controller().tickRuntime(lightEngine, (LightChunkGetter) (Object) this);
        LuxBenchmarkSupport.recordSince("lucistarlink.runtime_tick", startedAt);
    }
}
