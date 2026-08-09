package com.qalab.qalabai.service;

import com.qalab.qalabai.model.TestScenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGenerationServiceFilterTest {

    private static TestScenario scenario(String type) {
        TestScenario s = new TestScenario();
        s.setType(type);
        return s;
    }

    @Test
    void normalizeTestTypeMapsKnownValuesAndNullsAll() {
        assertEquals("ui", CodeGenerationService.normalizeTestType("UI"));
        assertEquals("ui", CodeGenerationService.normalizeTestType("ui"));
        assertEquals("e2e", CodeGenerationService.normalizeTestType("e2e"));
        assertEquals("api", CodeGenerationService.normalizeTestType("API"));
        assertNull(CodeGenerationService.normalizeTestType("ALL"));
        assertNull(CodeGenerationService.normalizeTestType(""));
        assertNull(CodeGenerationService.normalizeTestType(null));
        assertNull(CodeGenerationService.normalizeTestType("bogus"));
    }

    @Test
    void apiFilterKeepsSecurityAndValidationScenarios() {
        assertTrue(CodeGenerationService.scenarioMatchesType(scenario("security"), "api"));
        assertTrue(CodeGenerationService.scenarioMatchesType(scenario("validation"), "api"));
        assertFalse(CodeGenerationService.scenarioMatchesType(scenario("positive"), "api"));
        assertFalse(CodeGenerationService.scenarioMatchesType(scenario("negative"), "api"));
    }

    @Test
    void uiAndE2eKeepNonApiScenarios() {
        assertTrue(CodeGenerationService.scenarioMatchesType(scenario("positive"), "ui"));
        assertTrue(CodeGenerationService.scenarioMatchesType(scenario("negative"), "e2e"));
        assertTrue(CodeGenerationService.scenarioMatchesType(scenario("reliability"), "ui"));
        assertFalse(CodeGenerationService.scenarioMatchesType(scenario("api"), "e2e"));
    }

    @Test
    void nullTypeKeepsEverything() {
        assertTrue(CodeGenerationService.scenarioMatchesType(scenario("positive"), null));
        assertTrue(CodeGenerationService.scenarioMatchesType(scenario("security"), null));
    }
}
