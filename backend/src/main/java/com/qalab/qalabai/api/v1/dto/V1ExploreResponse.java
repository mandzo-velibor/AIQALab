package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;

import java.time.LocalDateTime;

public record V1ExploreResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        String url,
        String title,
        String pageType,
        long buttonCount,
        long inputCount,
        long linkCount,
        long formCount,
        String screenshotBase64,
        LocalDateTime createdAt
) {
}
