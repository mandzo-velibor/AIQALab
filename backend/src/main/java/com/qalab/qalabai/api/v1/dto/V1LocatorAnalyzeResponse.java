package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.locator.intelligence.model.LocatorIntelligence;

import java.time.LocalDateTime;

/**
 * Response of a locator intelligence analysis.
 *
 * @param operationId  correlation id for the operation
 * @param status       operation status
 * @param projectId    resolved logical project id
 * @param url          analyzed page URL
 * @param intelligence the full deterministic analysis result
 * @param createdAt    server timestamp
 */
public record V1LocatorAnalyzeResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        String url,
        LocatorIntelligence intelligence,
        LocalDateTime createdAt
) {
}
