package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.model.FailureAnalysis;

import java.time.LocalDateTime;

public record FailureAnalysisDto(
        Long id,
        Long projectId,
        Long executionId,
        String failureType,
        Integer confidence,
        String summary,
        String affectedElement,
        Boolean healingCandidate,
        LocalDateTime createdAt
) {

    public static FailureAnalysisDto from(FailureAnalysis a) {
        return new FailureAnalysisDto(
                a.getId(),
                a.getProjectId(),
                a.getExecutionId(),
                a.getFailureType(),
                a.getConfidence(),
                a.getSummary(),
                a.getAffectedElement(),
                a.getHealingCandidate(),
                a.getCreatedAt()
        );
    }
}
