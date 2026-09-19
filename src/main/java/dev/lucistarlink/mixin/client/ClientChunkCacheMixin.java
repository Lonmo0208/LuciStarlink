package dev.lucistarlink.mixin.client;

import dev.lucistarlink.light.client.LuxClientLightEngine;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 客户端光照引擎的**唯一**构造点（{@code ClientChunkCache} 构造函数里的 {@code new LevelLightEngine(...)}）。
 *
 * <p>只有 {@code -Dlucistarlink.clientLightTakeover=true} 时才换成 {@link LuxClientLightEngine}；否则原样返回
 * 原版引擎，玩家的客户端行为与未装本模组完全一致（这也是这条 V2 线的默认状态）。
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Redirect(method = "<init>",
            at = @At(value = "NEW", target = "net/minecraft/world/level/lighting/LevelLightEngine"))
    private LevelLightEngine lucistarlink$clientLightEngine(LightChunkGetter chunkGetter, boolean hasBlockLight,
                                                            boolean hasSkyLight) {
        if (LuxClientLightEngine.TAKEOVER_ENABLED) {
            return new LuxClientLightEngine(chunkGetter, hasBlockLight, hasSkyLight);
        }
        return new LevelLightEngine(chunkGetter, hasBlockLight, hasSkyLight);
    }
}
