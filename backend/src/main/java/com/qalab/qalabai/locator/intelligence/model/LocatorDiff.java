package com.qalab.qalabai.locator.intelligence.model;

/**
 * Human-readable comparison between the currently used locator and a
 * historical locator observed for the same element fingerprint.
 */
public record LocatorDiff(
        String currentLocator,
        String previousLocator,
        boolean strategyChanged,
        String currentStrategy,
        String previousStrategy,
        boolean semanticChanged,
        double semanticSimilarity,
        boolean targetLikelySame,
        double targetSimilarity,
        String recommendation
) {
}
