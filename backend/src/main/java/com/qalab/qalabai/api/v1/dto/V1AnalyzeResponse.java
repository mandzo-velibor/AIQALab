package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;

import java.time.LocalDateTime;

public record V1AnalyzeResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        String url,
        AnalysisResponse analysis,
        LocalDateTime createdAt
) {
}
