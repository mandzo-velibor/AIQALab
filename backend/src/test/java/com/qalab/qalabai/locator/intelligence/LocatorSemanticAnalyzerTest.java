package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import com.qalab.qalabai.locator.intelligence.model.SemanticResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorSemanticAnalyzerTest {

    private final LocatorSemanticAnalyzer analyzer = new LocatorSemanticAnalyzer();

    @Test
    void testIdIsMostSemantic() {
        SemanticResult result = analyzer.analyze("getByTestId('login-button')", LocatorStrategy.TEST_ID, null);
        assertEquals(25.0, result.score(), 0.001);
    }

    @Test
    void roleScoresHigh() {
        SemanticResult result = analyzer.analyze("getByRole('button', { name: 'Log in' })", LocatorStrategy.ROLE, null);
        assertEquals(23.0, result.score(), 0.001);
    }

    @Test
    void xpathScoresLowest() {
        SemanticResult result = analyzer.analyze("xpath=//div[1]/button[2]", LocatorStrategy.XPATH, null);
        assertEquals(2.0, result.score(), 0.001);
    }

    @Test
    void identityWithTestIdBumpsNonTestIdStrategy() {
        ElementIdentity identity = new ElementIdentity("https://x.dev", "button", "button",
                "Log in", "Log in", "login-btn", null, null, null, null, null, null,
                new LinkedHashMap<>(), true, true);
        SemanticResult result = analyzer.analyze("getByRole('button', { name: 'Log in' })",
                LocatorStrategy.ROLE, identity);
        assertTrue(result.score() > 23.0);
        assertTrue(result.reason().contains("data-testid"));
    }

    @Test
    void rankingOrderIsMonotonic() {
        assertTrue(analyzer.analyze("t", LocatorStrategy.TEST_ID, null).score()
                > analyzer.analyze("t", LocatorStrategy.ROLE, null).score());
        assertTrue(analyzer.analyze("t", LocatorStrategy.ROLE, null).score()
                > analyzer.analyze("t", LocatorStrategy.CSS, null).score());
        assertTrue(analyzer.analyze("t", LocatorStrategy.CSS, null).score()
                > analyzer.analyze("t", LocatorStrategy.XPATH, null).score());
    }
}
