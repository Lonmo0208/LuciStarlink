package dev.lucistarlink.compat.sable;

/**
 * Sable 是否在场，用**同一个判据**回答「该不该按 Sable 处理」和「Sable 的 mixin 该不该应用」。
 *
 * <p>这两件事原来各有一套判断：`LuxCompat` 问 `ModList.isLoaded("sable")`，而 `LuxMixinPlugin` 问
 * `dev.ryanhcode.sable.Sable` 这个标记类在不在。只要 modid 命中而标记类不在（Sable 改名、换版本、
 * 类被重定位），accessor 就不会混进 `ServerLevel`，而方块变化那条路仍然会去强制转换它 —— 每次方块
 * 改动和每次世界生成都抛一次 `ClassCastException`。所以判据统一到「标记类是否存在」，也就是 mixin
 * 应用时真正用的那一条。
 */
public final class SablePresence {
    private static final String MARKER_CLASS = "dev.ryanhcode.sable.Sable";

    private static volatile Boolean present;

    private SablePresence() {
    }

    public static boolean isPresent() {
        Boolean cached = present;
        if (cached == null) {
            cached = hasMarkerClass();
            present = cached;
        }
        return cached;
    }

    private static boolean hasMarkerClass() {
        try {
            Class.forName(MARKER_CLASS, false, SablePresence.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
