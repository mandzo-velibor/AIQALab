package com.qalab.qalabai.api.v1.dto;

/**
 * A natural-language request describing what the user wants the QA platform
 * to do, e.g. "generate tests for the login page at https://...".
 */
public record V1IntentRequest(
        ProjectInfo project,
        String prompt,
        String url
) {
}
