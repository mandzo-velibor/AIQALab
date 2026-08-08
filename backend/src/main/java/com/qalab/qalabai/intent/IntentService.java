package com.qalab.qalabai.intent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects the user's intent from a natural-language prompt using lightweight
 * keyword matching. The matched terms are surfaced so callers can explain the
 * decision and let the user confirm before an expensive operation runs.
 */
@Service
public class IntentService {

    private static final Map<Intent, List<String>> KEYWORDS = Map.of(
            Intent.EXPLORE, List.of("explore", "map", "inspect", "analyze the page", "scrape", "find elements", "what is on"),
            Intent.TEST_PLAN, List.of("test plan", "plan tests", "plan", "test cases", "test strategy", "scenarios"),
            Intent.GENERATE_TESTS, List.of("generate tests", "write tests", "create tests", "make tests", "produce tests", "add tests", "test code", "playwright"),
            Intent.RUN_TESTS, List.of("run tests", "run the tests", "execute tests", "execute the tests", "run test", "execute test", "rerun", "re-run"),
            Intent.FULL_TEST, List.of("test it", "test the app", "test this", "full test", "end to end", "e2e", "test everything", "qa it", "quality check"),
            Intent.ANALYZE_FAILURE, List.of("why did it fail", "why did the test fail", "analyze failure", "failure analysis", "investigate failure", "debug test", "diagnose"));

    private static final Map<Intent, List<String>> STEPS = Map.of(
            Intent.EXPLORE, List.of("explore", "analyze"),
            Intent.TEST_PLAN, List.of("explore", "testPlan"),
            Intent.GENERATE_TESTS, List.of("explore", "locators", "testPlan", "generateTests"),
            Intent.RUN_TESTS, List.of("run"),
            Intent.FULL_TEST, List.of("explore", "locators", "testPlan", "generateTests", "run"),
            Intent.ANALYZE_FAILURE, List.of("run", "failureAnalysis", "healing"),
            Intent.UNKNOWN, List.of());

    public IntentResult detect(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return new IntentResult(Intent.UNKNOWN, List.of(), List.of(), prompt);
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        for (Intent intent : List.of(Intent.GENERATE_TESTS, Intent.FULL_TEST, Intent.TEST_PLAN,
                Intent.RUN_TESTS, Intent.ANALYZE_FAILURE, Intent.EXPLORE)) {
            List<String> matched = new ArrayList<>();
            for (String keyword : KEYWORDS.get(intent)) {
                if (normalized.contains(keyword)) {
                    matched.add(keyword);
                }
            }
            if (!matched.isEmpty()) {
                return new IntentResult(intent, STEPS.get(intent), matched, prompt);
            }
        }
        return new IntentResult(Intent.UNKNOWN, List.of(), List.of(), prompt);
    }
}
