package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.model.HealingSuggestion;

import java.time.LocalDateTime;

public record HealingSuggestionDto(
        Long id,
        Long projectId,
        Long executionId,
        Long failureAnalysisId,
        String elementName,
        String oldLocator,
        String newLocator,
        Integer confidence,
        String reason,
        String status,
        LocalDateTime createdAt
) {

    public static HealingSuggestionDto from(HealingSuggestion s) {
        return new HealingSuggestionDto(
                s.getId(),
                s.getProjectId(),
                s.getExecutionId(),
                s.getFailureAnalysisId(),
                s.getElementName(),
                s.getOldLocator(),
                s.getNewLocator(),
                s.getConfidence(),
                s.getReason(),
                s.getStatus(),
                s.getCreatedAt()
        );
    }
}
