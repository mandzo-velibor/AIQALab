package com.qalab.qalabai.dto.executor;

/**
 * @param reportPath      where report.json / report.md were written, or null
 * @param htmlReportPath  where report.html was written, or null. Carried explicitly
 *                        because the HTML lands in the artifact directory, not in the
 *                        CLI's own report directory, so without this a user has no way
 *                        to find the file the tool exists to produce.
 */
public record ExecutionResponse(
        Long executionId,
        String status,
        long durationMs,
        String errorMessage,
        String consoleLogs,
        String testType,
        String instruction,
        String note,
        String reportPath,
        String htmlReportPath
) {

    /** Back-compatible constructor for callers that do not have report paths. */
    public ExecutionResponse(Long executionId, String status, long durationMs, String errorMessage,
                             String consoleLogs, String testType, String instruction, String note) {
        this(executionId, status, durationMs, errorMessage, consoleLogs, testType, instruction, note,
                null, null);
    }
}
