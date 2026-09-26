package com.qalab.qalabai.dto.executor;

import java.util.List;

/**
 * Per-test results for one execution, for the UI to render without opening a file.
 *
 * <p>Deliberately a separate endpoint from the execution history: the dashboard lists
 * every run, and inlining every test of every run into that list would make a page that
 * a project with a few hundred executions cannot load. The results are fetched when a
 * row is expanded.</p>
 *
 * @param htmlReport where the self-contained report was written, or null
 * @param tests      in run order
 */
public record ExecutionResultsResponse(
        Long executionId,
        String status,
        Long durationMs,
        String reportPath,
        String htmlReport,
        int totalCount,
        int passedCount,
        int failedCount,
        int skippedCount,
        List<TestResult> tests
) {

    public record TestResult(
            int ordinal,
            String file,
            String title,
            String status,
            Long durationMs,
            int retries,
            String error,
            boolean hasEvidence,
            List<String> screenshots,
            List<String> videos,
            List<String> traces
    ) {
    }
}
