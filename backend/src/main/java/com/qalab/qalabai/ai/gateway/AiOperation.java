package com.qalab.qalabai.ai.gateway;

/**
 * The QA operation that triggered an AI call. Used for usage accounting and
 * budget estimation.
 */
public enum AiOperation {

    EXPLORE,
    ANALYZE,
    LOCATOR_GENERATION,
    TEST_PLAN,
    TEST_GENERATION,
    FAILURE_ANALYSIS,
    SELF_HEALING,
    HEALING_EVALUATION,
    BUG_REPORT,
    FULL_WORKFLOW;

    public static AiOperation from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        try {
            return AiOperation.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported AI operation: " + value);
        }
    }
}
