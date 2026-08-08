package com.qalab.qalabai.service.report;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A snapshot of a single test execution, enriched with artifact locations so
 * reports are self-contained and can be re-rendered later.
 */
public record TestReport(
        Long executionId,
        Long projectId,
        String testFile,
        String status,
        Long duration,
        String errorMessage,
        Map<String, Object> artifacts,
        String reportPath,
        LocalDateTime createdAt
) {
}
