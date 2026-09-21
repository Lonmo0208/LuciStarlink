package ca.spottedleaf.starlight.common.compat;

/**
 * Whether Sable is present, answered by the <b>same criterion the mixin plugin uses to decide whether to apply the
 * Sable accessor</b> - the marker class, not the mod id.
 *
 * <p>The two questions must never disagree: if the mod id matched while the marker class was missing (Sable renamed,
 * downgraded, or relocated), the accessor would not be mixed into {@code ServerLevel} and the plot check would cast
 * an unmodified level - a {@code ClassCastException} on every block change. Cached: the answer cannot change while
 * the game runs.</p>
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
