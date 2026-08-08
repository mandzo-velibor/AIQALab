package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;

import java.time.LocalDateTime;
import java.util.Map;

public record V1WorkflowResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        String url,
        Map<String, Object> steps,
        LocalDateTime createdAt
) {
}
