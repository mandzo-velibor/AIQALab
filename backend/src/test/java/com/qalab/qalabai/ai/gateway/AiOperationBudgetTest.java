package com.qalab.qalabai.ai.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every operation used to ask for the same 12 000 output tokens.
 *
 * <p>That is the wrong shape: a locator verdict and a generated test suite do not need
 * the same ceiling, and a provider is entitled to bill the ceiling. On a free tier the
 * oversized requests are how an operation that needed 1 500 tokens exhausts an
 * allowance. The BYOK clients already defaulted to ~4 000; only the managed path was
 * left at 12 000.</p>
 */
class AiOperationBudgetTest {

    @Test
    void everyOperationHasABudget() {
        for (AiOperation operation : AiOperation.values()) {
            assertTrue(operation.budgetOutputTokens() > 0,
                    operation + " must declare a positive output budget");
        }
    }

    @Test
    void theLargestBudgetGoesToTestGeneration() {
        // It is the only operation that emits full spec files.
        int max = 0;
        for (AiOperation operation : AiOperation.values()) {
            max = Math.max(max, operation.budgetOutputTokens());
        }
        assertEquals(AiOperation.TEST_GENERATION.budgetOutputTokens(), max,
                "test generation should have the largest ceiling");
    }

    @Test
    void smallOperationsGetSmallCeilings() {
        // These produce a sentence or a single locator, not a file.
        assertTrue(AiOperation.SELF_HEALING.budgetOutputTokens() <= 2_000);
        assertTrue(AiOperation.HEALING_EVALUATION.budgetOutputTokens() <= 2_000);
        assertTrue(AiOperation.FAILURE_ANALYSIS.budgetOutputTokens() <= 2_000);
        assertTrue(AiOperation.EXPLORE.budgetOutputTokens() <= 2_000);
    }

    @Test
    void noOperationAsksForTheOldFlatMaximum() {
        // The regression guard: if someone reintroduces a single global number this
        // fails, which is the point.
        for (AiOperation operation : AiOperation.values()) {
            assertTrue(operation.budgetOutputTokens() < 12_000,
                    operation + " still requests the old 12000-token maximum");
        }
    }
}
