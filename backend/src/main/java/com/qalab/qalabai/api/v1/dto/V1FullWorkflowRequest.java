package com.qalab.qalabai.api.v1.dto;

/**
 * Request for the end-to-end FULL_TEST workflow.
 *
 * @param testType optional deterministic filter (ALL/UI/E2E/API). Additive and
 *                 nullable: when null the workflow applies no type filter. It is
 *                 resolved through the same normalisation as the other entry
 *                 points so a textual instruction can never silently disagree with
 *                 the structured filter.
 */
public record V1FullWorkflowRequest(ProjectInfo project, String url, String username, String password,
                                    String instruction, String operationId, String testType) {
}
