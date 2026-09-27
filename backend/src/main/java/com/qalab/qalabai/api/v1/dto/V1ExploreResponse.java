package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;

import java.time.LocalDateTime;
import java.util.Map;

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
        LocalDateTime createdAt,
        // Per-agent outcome. Added by B-033: the dashboard renders a panel from this, and
        // the legacy response carried it, so dropping it during the v1 migration would have
        // deleted a visible feature rather than replaced it.
        Map<String, Object> agentResults
) {
}
