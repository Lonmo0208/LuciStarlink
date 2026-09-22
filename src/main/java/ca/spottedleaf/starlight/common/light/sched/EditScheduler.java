package ca.spottedleaf.starlight.common.light.sched;

import ca.spottedleaf.starlight.common.light.StarLightInterface;
import net.minecraft.core.BlockPos;

/**
 * The "own scheduler" project's record and entry-point skeleton — see {@code docs/OWN-SCHEDULER-PLAN.md} for the
 * charter, the fuse, and §9/§10 for the outcome of all twelve attempts.
 *
 * <p><b>Status: closed by its own fuse (2026-09-22).</b> Twelve implementations were built and measured. The lane did
 * engage and did improve the number (minPass 4.3-5.4 ms → 3.73 ms), but it hung the server four times, and the thread
 * dumps placed the four hangs in four <i>different</i> phases — {@code prepareLevels} (before the first tick), the
 * harness fingerprint (a blocking chunk read), the prepare phase, and shutdown. Narrowing the hold (attempt 12: defer
 * only keys created by an edit) did not help; it only moved the hang to the next phase.</p>
 *
 * <p><b>Why that is a design-level result, not a bug to keep chasing:</b> on this base there is a waiter that needs the
 * light engine settled in <i>every</i> phase — startup preparation, worldgen, chunk loading, any {@code getBlockState}
 * on an unloaded chunk, and shutdown/save. Any design that defers light work therefore interlocks with one of them. 1.x
 * can install inside the edit call because it owns its light truth, so a "pending" state never exists. That ownership is
 * what the new engine (see {@code docs/NEW-ENGINE-TEARDOWN.md}) is being built around.</p>
 *
 * <p>Nothing here is wired: the default is OFF ({@code -Dscalablelux.ownScheduler=true}) and every entry point is
 * inert.</p>
 */
public final class EditScheduler {

    private EditScheduler() {}

    private static final boolean ENABLED = Boolean.getBoolean("scalablelux.ownScheduler");

    public static boolean enabled() {
        return ENABLED;
    }

    /** S1 entry point — deliberately inert; a working version must compute the edit inside the call, not defer it. */
    public static boolean trySynchronousInstall(final StarLightInterface engine, final BlockPos pos) {
        return false;
    }

    /** Hard drain point, should the lane ever be revisited: must be a call that is guaranteed to happen. */
    public static void drainHeld(final StarLightInterface engine) {
        // inert
    }

    /** Whether the lane is holding work (always false in the skeleton). */
    public static boolean holding() {
        return false;
    }
}
