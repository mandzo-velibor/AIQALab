package com.qalab.qalabai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * An LLM-generated bug report derived from a failed test execution. The report
 * is written by the Core based on the failure context (error message, console
 * logs, locator, classification) and is persisted for human review.
 */
@Entity
@Table(name = "bug_report")
public class BugReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reportId;

    @Column
    private Long projectId;

    @Column
    private Long executionId;

    @Column
    private String testFile;

    @Column
    private String testName;

    @Column
    private String status;

    @Column(length = 300)
    private String title;

    @Column(length = 50)
    private String severity;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String stepsToReproduce;

    @Column(columnDefinition = "TEXT")
    private String expectedBehavior;

    @Column(columnDefinition = "TEXT")
    private String actualBehavior;

    @Column(length = 300)
    private String affectedElement;

    @Column(length = 50)
    private String failureType;

    @Column(columnDefinition = "TEXT")
    private String suggestedFix;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String consoleLogsExcerpt;

    @Column(columnDefinition = "TEXT")
    private String reportJson;

    @Column(columnDefinition = "TEXT")
    private String instruction;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public String getTestFile() {
        return testFile;
    }

    public void setTestFile(String testFile) {
        this.testFile = testFile;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStepsToReproduce() {
        return stepsToReproduce;
    }

    public void setStepsToReproduce(String stepsToReproduce) {
        this.stepsToReproduce = stepsToReproduce;
    }

    public String getExpectedBehavior() {
        return expectedBehavior;
    }

    public void setExpectedBehavior(String expectedBehavior) {
        this.expectedBehavior = expectedBehavior;
    }

    public String getActualBehavior() {
        return actualBehavior;
    }

    public void setActualBehavior(String actualBehavior) {
        this.actualBehavior = actualBehavior;
    }

    public String getAffectedElement() {
        return affectedElement;
    }

    public void setAffectedElement(String affectedElement) {
        this.affectedElement = affectedElement;
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
    }

    public String getSuggestedFix() {
        return suggestedFix;
    }

    public void setSuggestedFix(String suggestedFix) {
        this.suggestedFix = suggestedFix;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getConsoleLogsExcerpt() {
        return consoleLogsExcerpt;
    }

    public void setConsoleLogsExcerpt(String consoleLogsExcerpt) {
        this.consoleLogsExcerpt = consoleLogsExcerpt;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
