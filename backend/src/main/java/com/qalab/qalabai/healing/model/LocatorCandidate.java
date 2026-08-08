package com.qalab.qalabai.healing.model;

/**
 * A proposed replacement locator with deterministic validation information.
 * Extensible: {@code reason} carries the human/rank rationale, numeric fields
 * carry measurable evidence.
 */
public record LocatorCandidate(
        String locator,
        LocatorStrategy strategy,
        double score,
        boolean unique,
        boolean visible,
        boolean enabled,
        int matchedElementCount,
        String reason
) {
}
