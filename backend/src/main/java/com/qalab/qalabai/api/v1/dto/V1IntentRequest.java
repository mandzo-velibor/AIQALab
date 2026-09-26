package com.qalab.qalabai.api.v1.dto;

/**
 * A natural-language request describing what the user wants the QA platform
 * to do, e.g. "generate tests for the login page at https://...".
 *
 * @param username  optional login user. Present because the intent path dispatches to the
 *                  same operations as the CLI, and a run against a login page cannot
 *                  explore past the form without it. Previously the request had no such
 *                  field, so {@code /intent/run} could never pass credentials on.
 * @param password  optional login password, as above
 * @param testType  optional deterministic filter (ALL/UI/E2E/API), resolved through the
 *                  same normalisation as the other entry points so a textual instruction
 *                  can never silently disagree with the structured filter
 *
 * <p>All three are additive and nullable, so an existing client that sends only
 * {@code project}, {@code prompt} and {@code url} keeps working unchanged.</p>
 */
public record V1IntentRequest(
        ProjectInfo project,
        String prompt,
        String url,
        String username,
        String password,
        String testType
) {

    /** Back-compatible constructor for the original three-field shape. */
    public V1IntentRequest(ProjectInfo project, String prompt, String url) {
        this(project, prompt, url, null, null, null);
    }
}
