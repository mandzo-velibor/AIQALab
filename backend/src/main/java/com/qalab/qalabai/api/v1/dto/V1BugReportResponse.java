package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.model.BugReport;

import java.time.LocalDateTime;

/**
 * Bug report response. The {@code generated} flag is true when the report body
 * came from the AI, false when a deterministic fallback was persisted.
 */
public record V1BugReportResponse(
        String reportId,
        Long projectId,
        Long executionId,
        String testFile,
        String testName,
        String status,
        String title,
        String severity,
        String summary,
        String stepsToReproduce,
        String expectedBehavior,
        String actualBehavior,
        String affectedElement,
        String failureType,
        String suggestedFix,
        String errorMessage,
        String consoleLogsExcerpt,
        boolean generated,
        String instruction,
        LocalDateTime createdAt
) {

    public static V1BugReportResponse from(BugReport report) {
        return new V1BugReportResponse(
                report.getReportId(),
                report.getProjectId(),
                report.getExecutionId(),
                report.getTestFile(),
                report.getTestName(),
                report.getStatus(),
                report.getTitle(),
                report.getSeverity(),
                report.getSummary(),
                report.getStepsToReproduce(),
                report.getExpectedBehavior(),
                report.getActualBehavior(),
                report.getAffectedElement(),
                report.getFailureType(),
                report.getSuggestedFix(),
                report.getErrorMessage(),
                report.getConsoleLogsExcerpt(),
                report.getReportJson() != null && !report.getReportJson().isBlank()
                        && !"{}".equals(report.getReportJson()),
                report.getInstruction(),
                report.getCreatedAt()
        );
    }
}
