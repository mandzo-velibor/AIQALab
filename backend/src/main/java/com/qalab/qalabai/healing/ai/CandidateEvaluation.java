package com.qalab.qalabai.healing.ai;

import java.util.List;

/**
 * Result of the AI evaluation step: which candidate to recommend, why, how
 * confident the model is, and whether the change looks safe. The AI only
 * evaluates deterministic candidates — it never invents locators from scratch.
 */
public record CandidateEvaluation(
        String recommendedLocator,
        double confidence,
        String reason,
        boolean safeToApply,
        List<String> risks
) {
}
