package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;

import java.time.LocalDateTime;

public record V1RunResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        Long executionId,
        String executionStatus,
        Long duration,
        String errorMessage,
        String output,
        LocalDateTime createdAt
) {
}
