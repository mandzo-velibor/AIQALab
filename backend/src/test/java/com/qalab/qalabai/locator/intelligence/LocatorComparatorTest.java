package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.LocatorDiff;
import com.qalab.qalabai.service.healing.LocatorSimilarityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorComparatorTest {

    private final LocatorComparator comparator = new LocatorComparator(
            new LocatorStrategyDetector(),
            new LocatorSemanticAnalyzer(),
            new LocatorSimilarityService(),
            new ElementFingerprintService());

    @Test
    void detectsStrategyChange() {
        LocatorDiff diff = comparator.compare(
                "getByText('Sign in')",
                "getByTestId('sign-in-button')",
                null, null);
        assertTrue(diff.strategyChanged());
        assertFalse(diff.targetLikelySame());
    }

    @Test
    void sameStrategyDifferentValueIsSemantic() {
        LocatorDiff diff = comparator.compare(
                "getByRole('button', { name: 'Log in' })",
                "getByRole('button', { name: 'Sign in' })",
                null, null);
        assertFalse(diff.strategyChanged());
        assertTrue(diff.targetLikelySame());
    }

    @Test
    void keepsMoreStableStrategy() {
        LocatorDiff diff = comparator.compare(
                "getByText('Log in')",
                "getByTestId('login')",
                identity("login"), identity("login"));
        assertTrue(diff.recommendation().contains("historical"));
    }

    @Test
    void identicalStrategiesRecommendFollowingHistory() {
        LocatorDiff diff = comparator.compare(
                "getByRole('button', { name: 'Log in' })",
                "getByRole('button', { name: 'Log in' })",
                null, null);
        assertFalse(diff.strategyChanged());
    }

    private com.qalab.qalabai.locator.intelligence.model.ElementIdentity identity(String testId) {
        return new com.qalab.qalabai.locator.intelligence.model.ElementIdentity(
                "https://x.dev", "button", "button", "Log in", "Log in", testId,
                null, null, null, null, null, null,
                new java.util.LinkedHashMap<>(), true, true);
    }
}
