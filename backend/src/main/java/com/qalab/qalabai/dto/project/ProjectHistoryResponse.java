package com.qalab.qalabai.dto.project;

import java.time.LocalDateTime;
import java.util.List;

public record ProjectHistoryResponse(
        List<ExecutionEntry> executions,
        List<PageAnalysisEntry> pageAnalyses,
        List<LocatorHistoryEntry> locatorHistory,
        List<FailureAnalysisEntry> failureAnalyses,
        List<HealingSuggestionEntry> healingSuggestions
) {

    public record ExecutionEntry(
            Long id,
            String testFile,
            String status,
            Long duration,
            String errorMessage,
            String screenshotPath,
            String consoleLogs,
            LocalDateTime createdAt
    ) {}

    public record PageAnalysisEntry(
            Long id,
            String url,
            String pageType,
            Integer version,
            LocalDateTime createdAt
    ) {}

    public record LocatorHistoryEntry(
            Long id,
            String elementName,
            String locator,
            String strategy,
            String status,
            LocalDateTime createdAt
    ) {}

    public record FailureAnalysisEntry(
            Long id,
            Long executionId,
            String failureType,
            String summary,
            String affectedElement,
            Boolean healingCandidate,
            LocalDateTime createdAt
    ) {}

    public record HealingSuggestionEntry(
            Long id,
            String elementName,
            String oldLocator,
            String newLocator,
            String status,
            Integer confidence,
            LocalDateTime createdAt
    ) {}
}
