package com.qalab.qalabai.locator.intelligence.model;

/**
 * Deterministic semantic quality of a locator.
 *
 * @param score  0-25 (higher = more semantic/expressive)
 * @param reason explicit explanation of the score
 */
public record SemanticResult(double score, String reason) {
}
