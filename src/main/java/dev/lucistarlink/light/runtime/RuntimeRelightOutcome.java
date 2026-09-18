package dev.lucistarlink.light.runtime;

import java.util.List;

/**
 * Result of one runtime region job.
 *
 * @param results        sections to publish (core plus, when {@code haloPublish} is on, halo chunks)
 * @param baselineMoved  true when a neighbouring region pushed light into the engine for this region's chunks
 *                       while the job was running. The computed values were derived from a baseline that is no
 *                       longer current, so they must not be published - the job is re-queued instead.
 */
public record RuntimeRelightOutcome(List<LuxRelightResult> results, boolean baselineMoved, boolean haloTouched) {
    public static RuntimeRelightOutcome publish(List<LuxRelightResult> results) {
        return new RuntimeRelightOutcome(results, false, true);
    }

    public static RuntimeRelightOutcome publish(List<LuxRelightResult> results, boolean haloTouched) {
        return new RuntimeRelightOutcome(results, false, haloTouched);
    }

    public static RuntimeRelightOutcome stale(List<LuxRelightResult> results) {
        return new RuntimeRelightOutcome(results, true, true);
    }
}
