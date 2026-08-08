package com.qalab.qalabai.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentServiceTest {

    private final IntentService service = new IntentService();

    @Test
    void detectsGenerateTests() {
        IntentResult result = service.detect("generate tests for the login page");
        assertEquals(Intent.GENERATE_TESTS, result.intent());
        assertTrue(result.steps().contains("generateTests"));
        assertTrue(result.matchedKeywords().contains("generate tests"));
    }

    @Test
    void detectsRunTests() {
        assertEquals(Intent.RUN_TESTS, service.detect("please run the tests now").intent());
        assertEquals(Intent.RUN_TESTS, service.detect("rerun the failing tests").intent());
    }

    @Test
    void detectsExplore() {
        assertEquals(Intent.EXPLORE, service.detect("explore this site and map the elements").intent());
    }

    @Test
    void detectsTestPlan() {
        assertEquals(Intent.TEST_PLAN, service.detect("create a test plan for the checkout").intent());
    }

    @Test
    void detectsFullTest() {
        assertEquals(Intent.FULL_TEST, service.detect("test this app end to end").intent());
    }

    @Test
    void detectsFailureAnalysis() {
        assertEquals(Intent.ANALYZE_FAILURE, service.detect("why did the test fail on login?").intent());
    }

    @Test
    void unknownForNoise() {
        IntentResult result = service.detect("hello world");
        assertEquals(Intent.UNKNOWN, result.intent());
        assertTrue(result.steps().isEmpty());
        assertTrue(result.matchedKeywords().isEmpty());
    }

    @Test
    void unknownForBlank() {
        assertEquals(Intent.UNKNOWN, service.detect("  ").intent());
        assertEquals(Intent.UNKNOWN, service.detect(null).intent());
    }

    @Test
    void isCaseInsensitive() {
        assertEquals(Intent.GENERATE_TESTS, service.detect("Generate TESTS now").intent());
    }
}
