package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;

import java.time.LocalDateTime;

public record V1FailureAnalysisResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        Long executionId,
        FailureAnalysisDto analysis,
        LocalDateTime createdAt
) {
}
