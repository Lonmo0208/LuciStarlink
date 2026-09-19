package dev.lucistarlink.light.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 世界生成写入作用域：worldgen 放的方块由区块生成自己的光照路径负责，不能被当成玩家改动重新排队。
 *
 * <p>深度是「按线程」的 —— 异步区块生成让多个线程可能同时处于作用域内，所以压制的判定也按线程来；而
 * {@link #isActive()} 先读的那个 volatile 标志只是快路径。守卫每改一个方块都要问一次，ThreadLocal 查找
 * 不该出现在这条路上：标志为 false 时任何线程的深度都必然是 0，只有为 true 才回落到本线程的深度。
 */
final class WorldgenWriteScope {
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final AtomicInteger openScopes = new AtomicInteger();
    private volatile boolean scopesInFlight;

    void begin() {
        if (depth.get() == 0) {
            openScopes.incrementAndGet();
            scopesInFlight = true;
        }
        depth.set(depth.get() + 1);
    }

    void end() {
        int remainingDepth = depth.get() - 1;
        if (remainingDepth <= 0) {
            depth.remove();
            int remainingScopes = openScopes.decrementAndGet();
            if (remainingScopes < 0) {
                // 不成对的退出不能把计数压成负数，否则之后真正的作用域会把它减到 0，在仍然开着的时候解除压制。
                openScopes.set(0);
                remainingScopes = 0;
            }
            scopesInFlight = remainingScopes > 0;
        } else {
            depth.set(remainingDepth);
        }
    }

    /** 本线程此刻是否处于作用域内；别的线程开着作用域不影响本线程。 */
    boolean isActive() {
        if (!scopesInFlight) {
            return false;
        }
        return depth.get() > 0;
    }

    /** 是否有任何线程正处于作用域内（给「世界生成进行中」的全局镜像用）。 */
    boolean anyOpen() {
        return scopesInFlight;
    }

    void reset() {
        depth.remove();
    }
}
