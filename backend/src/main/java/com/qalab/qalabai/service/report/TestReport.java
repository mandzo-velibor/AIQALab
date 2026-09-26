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
        String htmlReportPath,
        LocalDateTime createdAt
) {

    /** Back-compatible constructor for callers that predate the HTML report. */
    public TestReport(Long executionId, Long projectId, String testFile, String status, Long duration,
                      String errorMessage, Map<String, Object> artifacts, String reportPath,
                      LocalDateTime createdAt) {
        this(executionId, projectId, testFile, status, duration, errorMessage, artifacts, reportPath,
                null, createdAt);
    }
}
