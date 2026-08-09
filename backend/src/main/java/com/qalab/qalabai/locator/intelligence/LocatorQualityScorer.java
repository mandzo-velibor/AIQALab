package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.locator.intelligence.model.QualityScore;
import org.springframework.stereotype.Component;

/**
 * Deterministic quality scoring, no AI, no randomness.
 *
 * <ul>
 *   <li>uniqueness 0-25 (from live element count)</li>
 *   <li>semantic 0-25 (from {@link LocatorSemanticAnalyzer})</li>
 *   <li>stability 0-25 (from {@link LocatorStabilityAnalyzer})</li>
 *   <li>maintainability 0-15 (from strategy)</li>
 *   <li>resilience 0-10 (from strategy)</li>
 * </ul>
 *
 * <p>Total = 100.</p>
 */
@Component
public class LocatorQualityScorer {

    public QualityScore score(double uniqueness, double semantic, double stability,
                              double maintainability, double resilience) {
        uniqueness = clamp(uniqueness, 0, 25);
        semantic = clamp(semantic, 0, 25);
        stability = clamp(stability, 0, 25);
        maintainability = clamp(maintainability, 0, 15);
        resilience = clamp(resilience, 0, 10);
        double total = round(uniqueness + semantic + stability + maintainability + resilience);
        return new QualityScore(uniqueness, semantic, stability, maintainability, resilience, total);
    }

    public double maintainability(LocatorStrategy strategy) {
        return switch (strategy) {
            case TEST_ID -> 15.0;
            case ROLE -> 14.0;
            case LABEL -> 13.0;
            case TEXT -> 12.0;
            case NAME -> 11.0;
            case ID -> 10.0;
            case PLACEHOLDER -> 9.0;
            case CSS -> 7.0;
            case XPATH -> 3.0;
        };
    }

    public double resilience(LocatorStrategy strategy) {
        return switch (strategy) {
            case TEST_ID -> 10.0;
            case ROLE -> 9.0;
            case LABEL -> 8.0;
            case TEXT -> 7.0;
            case NAME -> 6.0;
            case ID -> 5.0;
            case PLACEHOLDER -> 4.0;
            case CSS -> 3.0;
            case XPATH -> 1.0;
        };
    }

    /** Uniqueness score (0-25) derived from the number of matched elements. */
    public double uniquenessFromCount(int count) {
        if (count <= 0) {
            return 0.0;
        }
        if (count == 1) {
            return 25.0;
        }
        return Math.max(0.0, 25.0 - (count - 1) * 5.0);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
