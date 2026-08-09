package com.qalab.qalabai.locator.intelligence.model;

import java.util.List;

/**
 * Deterministic fragility analysis of a locator expression.
 *
 * @param score  0-25 (higher = more stable), the stability dimension of the
 *               quality score
 * @param level  HIGH / MEDIUM / LOW
 * @param reasons explicit, human-readable findings with explanation
 */
public record StabilityResult(double score, Level level, List<String> reasons) {

    public enum Level {
        HIGH,
        MEDIUM,
        LOW
    }
}
