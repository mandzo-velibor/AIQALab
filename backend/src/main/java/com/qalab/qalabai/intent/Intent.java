package com.qalab.qalabai.intent;

/**
 * High-level user goals that the QA platform can fulfil. Detected from a
 * natural-language prompt by {@link IntentService} and mapped onto the existing
 * workflow operations.
 */
public enum Intent {

    /** Look around a site and produce a page map. */
    EXPLORE,
    /** Generate an executable test plan. */
    TEST_PLAN,
    /** Generate Playwright test code. */
    GENERATE_TESTS,
    /** Run previously generated tests. */
    RUN_TESTS,
    /** Full flow: explore, plan, generate and (if a workspace is given) run. */
    FULL_TEST,
    /** Explain why a test failed. */
    ANALYZE_FAILURE,
    /** Nothing matched. */
    UNKNOWN
}
