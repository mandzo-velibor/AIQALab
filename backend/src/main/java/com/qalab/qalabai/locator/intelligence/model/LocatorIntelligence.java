package com.qalab.qalabai.locator.intelligence.model;

import java.util.List;

/**
 * Full result of a locator intelligence analysis (Sprint 14). Everything is
 * deterministic and explainable: scores carry explicit reasons, health carries
 * historical evidence, and the comparison shows how the locator drifted from
 * its historical variant for the same element.
 */
public record LocatorIntelligence(
        String url,
        String locator,
        String strategy,
        double stabilityScore,
        String stabilityLevel,
        List<String> stabilityReasons,
        double semanticScore,
        String semanticReason,
        double uniqueness,
        String uniquenessDetail,
        boolean visible,
        boolean enabled,
        int matchedElementCount,
        double maintainability,
        double resilience,
        double overallScore,
        String health,
        String healthReason,
        double survivalRate,
        int observedCount,
        String elementFingerprint,
        LocatorDiff comparison,
        List<HistoricalObservation> history
) {
}
