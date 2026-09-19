package dev.lucistarlink.light.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界生成写入作用域的语义：压制按线程判定，而为了把 ThreadLocal 查找赶出每次改方块的热路径，read 之前先
 * 看一个全局标志。标志必须只是快路径 —— 它既不能把别人线程的作用域算到本线程头上，也不能因为某个线程先退出
 * 就替另一个还开着作用域的线程解除压制（一个朴素的 volatile boolean 恰好会在这里出错）。
 */
class WorldgenWriteScopeTest {
    @Test
    void suppressionIsScopedToTheThreadAndTheScope() {
        WorldgenWriteScope scope = new WorldgenWriteScope();
        assertFalse(scope.isActive(), "没有作用域时不该压制");
        scope.begin();
        assertTrue(scope.isActive());
        scope.end();
        assertFalse(scope.isActive(), "退出作用域后恢复");
    }

    @Test
    void nestedScopesSuppressUntilTheOutermostExit() {
        WorldgenWriteScope scope = new WorldgenWriteScope();
        scope.begin();
        scope.begin();
        scope.end();
        assertTrue(scope.isActive(), "还有一层作用域开着");
        scope.end();
        assertFalse(scope.isActive());
    }

    @Test
    void unbalancedExitDoesNotCancelALaterScope() {
        WorldgenWriteScope scope = new WorldgenWriteScope();
        scope.end();
        scope.begin();
        assertTrue(scope.isActive(), "不成对的退出不能把计数带歪，让真正的作用域失效");
        scope.end();
        assertFalse(scope.isActive());
    }

    @Test
    void anotherThreadsScopeDoesNotSuppressThisThread() throws Exception {
        WorldgenWriteScope scope = new WorldgenWriteScope();
        CountDownLatch inScope = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = scopeThread(scope, inScope, release);
        other.start();
        try {
            assertTrue(inScope.await(5, TimeUnit.SECONDS), "另一个线程已进入作用域");
            assertFalse(scope.isActive(), "本线程不在作用域内，快路径不能把别人的作用域算过来");
        } finally {
            release.countDown();
            other.join(5000);
        }
        assertFalse(scope.isActive());
    }

    @Test
    void oneThreadLeavingDoesNotEndAnotherThreadsScope() throws Exception {
        WorldgenWriteScope scope = new WorldgenWriteScope();
        CountDownLatch inScope = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = scopeThread(scope, inScope, release);
        other.start();
        scope.begin();
        try {
            assertTrue(inScope.await(5, TimeUnit.SECONDS), "另一个线程已进入作用域");
            assertTrue(scope.isActive(), "两个线程都在作用域内");
            release.countDown();
            other.join(5000);
            assertTrue(scope.isActive(), "别人退出不能结束本线程仍然开着的作用域");
        } finally {
            release.countDown();
            scope.end();
        }
        assertFalse(scope.isActive());
    }

    /** 进入作用域、等放行、再退出的线程；调用方负责 {@code release} 与 {@code join}。 */
    private Thread scopeThread(WorldgenWriteScope scope, CountDownLatch inScope, CountDownLatch release) {
        return new Thread(() -> {
            scope.begin();
            try {
                inScope.countDown();
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                scope.end();
            }
        }, "worldgen-scope-test");
    }
}
