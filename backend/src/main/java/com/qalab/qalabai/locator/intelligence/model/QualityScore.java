package com.qalab.qalabai.locator.intelligence.model;

/**
 * Deterministic 0-100 quality breakdown of a locator.
 *
 * <ul>
 *   <li>uniqueness 0-25 (from live element count)</li>
 *   <li>semantic 0-25 (from strategy + element identity)</li>
 *   <li>stability 0-25 (from fragility analysis)</li>
 *   <li>maintainability 0-15 (from strategy)</li>
 *   <li>resilience 0-10 (from strategy)</li>
 * </ul>
 */
public record QualityScore(
        double uniqueness,
        double semantic,
        double stability,
        double maintainability,
        double resilience,
        double total
) {
}
