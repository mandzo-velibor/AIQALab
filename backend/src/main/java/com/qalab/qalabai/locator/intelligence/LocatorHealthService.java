package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.LocatorHealth;
import org.springframework.stereotype.Component;

/**
 * Health classification combining intrinsic stability (0-25) with the
 * historical survival rate of the locator on the same element fingerprint.
 */
@Component
public class LocatorHealthService {

    private static final double HEALTHY_STABILITY = 0.70;
    private static final double WARNING_STABILITY = 0.45;
    private static final double FRAGILE_STABILITY = 0.20;
    private static final double HEALTHY_SURVIVAL = 0.80;

    public LocatorHealth classify(double stabilityScore, Double survivalRate, int observedCount, boolean elementFound) {
        if (Double.isNaN(stabilityScore) || stabilityScore < 0.0) {
            return LocatorHealth.UNKNOWN;
        }
        double stability = stabilityScore / 25.0;

        Double rate = survivalRate;
        if (rate == null) {
            rate = observedCount > 0 ? 0.95 : null;
        }

        if (!elementFound) {
            return LocatorHealth.BROKEN;
        }
        if (stability < FRAGILE_STABILITY) {
            return LocatorHealth.BROKEN;
        }
        if (rate != null && rate < 0.5) {
            return LocatorHealth.FRAGILE;
        }
        if (stability >= HEALTHY_STABILITY && rate != null && rate >= HEALTHY_SURVIVAL) {
            return LocatorHealth.HEALTHY;
        }
        if (stability >= WARNING_STABILITY) {
            return LocatorHealth.WARNING;
        }
        if (stability >= FRAGILE_STABILITY) {
            return LocatorHealth.FRAGILE;
        }
        return LocatorHealth.BROKEN;
    }

    /** Short human explanation of why the given health was chosen. */
    public String reason(LocatorHealth health, double stabilityScore, Double survivalRate, int observedCount, boolean elementFound) {
        double stability = stabilityScore / 25.0;
        Double rate = survivalRate != null ? survivalRate : (observedCount > 0 ? 0.95 : null);
        return switch (health) {
            case HEALTHY -> String.format("Stable strategy (%.0f%% stability) and healthy survival history (%.0f%%, %d observations).",
                    stability * 100, rate * 100, observedCount);
            case WARNING -> String.format("Moderate stability (%.0f%%).", stability * 100);
            case FRAGILE -> "Fragile patterns (positional/generated selectors) or failing history.";
            case BROKEN -> elementFound
                    ? "Locator has very low intrinsic stability."
                    : "The locator does not resolve to any element on the page.";
            case UNKNOWN -> "No historical observations yet.";
        };
    }
}
