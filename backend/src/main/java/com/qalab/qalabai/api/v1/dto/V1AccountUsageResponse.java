package com.qalab.qalabai.api.v1.dto;

import java.util.List;

/**
 * Generic, reusable account AI usage response: the current token budget plus a
 * per-operation token breakdown and the most recent AI calls. Powers the token
 * usage and AI-vs-deterministic feedback in the UI without exposing internals.
 */
public record V1AccountUsageResponse(
        V1BudgetPolicyResponse budget,
        List<V1UsageByOperation> breakdown,
        List<V1UsageRecordResponse> recent
) {

    public record V1UsageByOperation(String operation, long calls, long tokens) {
    }
}
