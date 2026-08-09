package com.qalab.qalabai.locator.intelligence.model;

import java.time.LocalDateTime;

/**
 * API-facing view of a persisted {@link LocatorObservation}.
 */
public record HistoricalObservation(
        Long id,
        Long projectId,
        String pageUrl,
        String locator,
        String strategy,
        double score,
        double stabilityScore,
        double semanticScore,
        double uniqueness,
        String health,
        String status,
        LocalDateTime observedAt
) {

    public static HistoricalObservation from(LocatorObservation o) {
        return new HistoricalObservation(
                o.getId(), o.getProjectId(), o.getPageUrl(), o.getLocator(), o.getStrategy(),
                o.getScore() != null ? o.getScore() : 0.0,
                o.getStabilityScore() != null ? o.getStabilityScore() : 0.0,
                o.getSemanticScore() != null ? o.getSemanticScore() : 0.0,
                o.getUniqueness() != null ? o.getUniqueness() : 0.0,
                o.getHealth(), o.getStatus(), o.getObservedAt());
    }
}
