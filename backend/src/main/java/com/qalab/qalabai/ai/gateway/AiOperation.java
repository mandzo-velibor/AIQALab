package com.qalab.qalabai.ai.gateway;

/**
 * The QA operation that triggered an AI call. Used for usage accounting and
 * budget estimation.
 *
 * <p>Also owns each operation's output-token budget. A single global
 * {@code max_tokens} is the wrong shape: a locator suggestion and a generated test
 * suite do not need the same ceiling, and asking for 12 000 tokens of a 2 000-token
 * answer is how a free tier burns its allowance on a call that could never have used
 * it. The budgets below are sized to the largest plausible answer for each
 * operation, and callers may still override per request.</p>
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

    /**
     * Output-token ceiling for this operation, used when a request does not specify
     * one. Deliberately not a single global constant — see the class comment.
     */
    public int budgetOutputTokens() {
        return switch (this) {
            // A page summary, not the page itself.
            case EXPLORE -> 2_000;
            // Element inventory plus attributes.
            case ANALYZE -> 4_000;
            // A ranked locator list.
            case LOCATOR_GENERATION -> 4_000;
            // Scenarios and their steps.
            case TEST_PLAN -> 4_000;
            // The largest artefact this system produces: full spec files with
            // page-object imports.
            case TEST_GENERATION -> 8_000;
            // Classification plus reasoning about one failure.
            case FAILURE_ANALYSIS -> 2_000;
            // One replacement locator.
            case SELF_HEALING -> 2_000;
            // A verdict on a proposed locator.
            case HEALING_EVALUATION -> 1_500;
            // A bug report: severity, summary, steps, expected vs actual.
            case BUG_REPORT -> 3_000;
            // An aggregate over the above; used when a caller does not name one.
            case FULL_WORKFLOW -> 4_000;
        };
    }

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
