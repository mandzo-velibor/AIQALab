package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.StabilityResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorStabilityAnalyzerTest {

    private final LocatorStabilityAnalyzer analyzer = new LocatorStabilityAnalyzer();

    @Test
    void stableRoleLocatorScoresHigh() {
        StabilityResult result = analyzer.analyze("getByRole('button', { name: 'Log in' })");
        assertEquals(25.0, result.score(), 0.001);
        assertEquals(StabilityResult.Level.HIGH, result.level());
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("No fragile patterns")));
    }

    @Test
    void positionalSelectorIsPenalized() {
        StabilityResult result = analyzer.analyze("locator('li:nth-child(2) > a')");
        assertTrue(result.score() < 25.0);
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("position")));
    }

    @Test
    void absoluteXpathIsPenalized() {
        StabilityResult result = analyzer.analyze("xpath=/html/body/div[1]/form/div[2]/button");
        assertTrue(result.score() < 20.0);
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("XPath")));
    }

    @Test
    void generatedClassAndIdArePenalized() {
        StabilityResult result = analyzer.analyze("locator('div.css-1x8k3y > button#submit-42')");
        assertTrue(result.score() < 20.0);
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("generated")));
    }

    @Test
    void deepChainsArePenalized() {
        StabilityResult result = analyzer.analyze("locator('div > section > article > button > span')");
        assertTrue(result.reasons().stream().anyMatch(r -> r.contains("deep DOM chain")));
    }

    @Test
    void emptyLocatorScoresZero() {
        StabilityResult result = analyzer.analyze("");
        assertEquals(0.0, result.score(), 0.001);
        assertEquals(StabilityResult.Level.LOW, result.level());
    }
}
