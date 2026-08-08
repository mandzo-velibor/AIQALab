package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;

import java.time.LocalDateTime;

public record V1HealingResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        Long executionId,
        HealingSuggestionDto suggestion,
        LocalDateTime createdAt
) {
}
