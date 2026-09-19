package dev.lucistarlink.mixin.client;

import dev.lucistarlink.light.client.LuxClientLightProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端探针（只读，零行为改动）：统计客户端光照引擎到底算了多少、花了多久。
 *
 * <p>为什么先做这个：客户端光照接管的前提是「客户端其实不需要自己算」—— 服务端已经把每个 section 的最终
 * 光照发过来了。这条探针就是证据：它数「服务端送来的 section 数据」（{@code queueSectionData}）与
 * 「客户端自己触发的重算」（{@code checkBlock} / {@code propagateLightSources} / {@code runLightUpdates}），
 * 前者远多于后者、且后者耗时可观时，接管才有意义；否则接管只是白改代码。
 *
 * <p>只在客户端环境应用（本 mixin 声明在 {@code lucistarlink.mixins.json} 的 {@code client} 列表里），
 * 且只在 {@code -Dlucistarlink.clientProbe=true} 时输出统计，平时连日志都没有。
 */
@Mixin(net.minecraft.world.level.lighting.LevelLightEngine.class)
public abstract class ClientLightEngineProbeMixin {
    @Inject(method = "checkBlock", at = @At("HEAD"))
    private void lucistarlink$countCheckBlock(BlockPos pos, CallbackInfo ci) {
        LuxClientLightProbe.countCheckBlock();
    }

    @Inject(method = "propagateLightSources", at = @At("HEAD"))
    private void lucistarlink$countPropagate(ChunkPos chunkPos, CallbackInfo ci) {
        LuxClientLightProbe.countPropagate();
    }

    @Inject(method = "queueSectionData", at = @At("HEAD"))
    private void lucistarlink$countQueueSectionData(net.minecraft.world.level.LightLayer layer,
                                                    net.minecraft.core.SectionPos sectionPos,
                                                    net.minecraft.world.level.chunk.DataLayer dataLayer,
                                                    CallbackInfo ci) {
        LuxClientLightProbe.countQueueSectionData();
    }

    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void lucistarlink$startRunUpdates(CallbackInfoReturnable<Integer> cir) {
        LuxClientLightProbe.beginRunLightUpdates();
    }

    @Inject(method = "runLightUpdates", at = @At("RETURN"))
    private void lucistarlink$finishRunUpdates(CallbackInfoReturnable<Integer> cir) {
        LuxClientLightProbe.endRunLightUpdates(cir.getReturnValueI());
    }
}
