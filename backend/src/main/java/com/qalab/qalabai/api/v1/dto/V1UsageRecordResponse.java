package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.model.AiUsageRecord;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of a single AI call for the account usage endpoint.
 * Mirrors {@link AiUsageRecord} without leaking persistence internals.
 */
public record V1UsageRecordResponse(
        String operation,
        String provider,
        String model,
        String mode,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        boolean estimated,
        String operationId,
        LocalDateTime createdAt
) {

    public static V1UsageRecordResponse from(AiUsageRecord record) {
        return new V1UsageRecordResponse(
                record.getOperation() != null ? record.getOperation().name() : null,
                record.getProvider() != null ? record.getProvider().name() : null,
                record.getModel(),
                record.getCredentialMode() != null ? record.getCredentialMode().name() : null,
                record.getInputTokens(),
                record.getOutputTokens(),
                record.getTotalTokens(),
                record.isEstimated(),
                record.getOperationId(),
                record.getCreatedAt());
    }
}
