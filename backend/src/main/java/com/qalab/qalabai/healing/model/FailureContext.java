package com.qalab.qalabai.healing.model;

import java.time.LocalDateTime;

/**
 * Common, partial-tolerant context collected for a failed test before locator
 * healing is attempted. Any field may be null; the pipeline degrades gracefully.
 *
 * <p>Jackson-deserializable so it can be posted to the healing API directly.</p>
 */
public class FailureContext {

    private Long projectId;
    private String runId;
    private String testId;
    private String testName;
    private String testFile;
    private String sourceLine;
    private String sourceCode;
    private String action;
    private String originalLocator;
    private String error;
    private String stackTrace;
    private String logs;
    private String screenshot;
    private String trace;
    private String video;
    private String currentUrl;
    private String browser;
    private String domSnapshot;
    private String relevantHtml;
    private String pageTitle;
    private LocalDateTime executionTimestamp;
    private FailureClassification classification;
    private Double classificationConfidence;
    private String classificationReason;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public String getTestFile() {
        return testFile;
    }

    public void setTestFile(String testFile) {
        this.testFile = testFile;
    }

    public String getSourceLine() {
        return sourceLine;
    }

    public void setSourceLine(String sourceLine) {
        this.sourceLine = sourceLine;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOriginalLocator() {
        return originalLocator;
    }

    public void setOriginalLocator(String originalLocator) {
        this.originalLocator = originalLocator;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String getLogs() {
        return logs;
    }

    public void setLogs(String logs) {
        this.logs = logs;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public void setScreenshot(String screenshot) {
        this.screenshot = screenshot;
    }

    public String getTrace() {
        return trace;
    }

    public void setTrace(String trace) {
        this.trace = trace;
    }

    public String getVideo() {
        return video;
    }

    public void setVideo(String video) {
        this.video = video;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public void setCurrentUrl(String currentUrl) {
        this.currentUrl = currentUrl;
    }

    public String getBrowser() {
        return browser;
    }

    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public String getDomSnapshot() {
        return domSnapshot;
    }

    public void setDomSnapshot(String domSnapshot) {
        this.domSnapshot = domSnapshot;
    }

    public String getRelevantHtml() {
        return relevantHtml;
    }

    public void setRelevantHtml(String relevantHtml) {
        this.relevantHtml = relevantHtml;
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public void setPageTitle(String pageTitle) {
        this.pageTitle = pageTitle;
    }

    public LocalDateTime getExecutionTimestamp() {
        return executionTimestamp;
    }

    public void setExecutionTimestamp(LocalDateTime executionTimestamp) {
        this.executionTimestamp = executionTimestamp;
    }

    public FailureClassification getClassification() {
        return classification;
    }

    public void setClassification(FailureClassification classification) {
        this.classification = classification;
    }

    public Double getClassificationConfidence() {
        return classificationConfidence;
    }

    public void setClassificationConfidence(Double classificationConfidence) {
        this.classificationConfidence = classificationConfidence;
    }

    public String getClassificationReason() {
        return classificationReason;
    }

    public void setClassificationReason(String classificationReason) {
        this.classificationReason = classificationReason;
    }
}
