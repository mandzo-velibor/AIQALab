package com.qalab.qalabai.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "test_execution")
public class TestExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long projectId;

    @Column(nullable = false)
    private String testFile;

    @Column(nullable = false)
    private String status;

    @Column
    private Long duration;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column
    private String screenshotPath;

    @Column
    private String videoPath;

    @Column
    private String tracePath;

    @Column
    private String reportPath;

    @Column(columnDefinition = "TEXT")
    private String consoleLogs;

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

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getTestFile() {
        return testFile;
    }

    public void setTestFile(String testFile) {
        this.testFile = testFile;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getDuration() {
        return duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getScreenshotPath() {
        return screenshotPath;
    }

    public void setScreenshotPath(String screenshotPath) {
        this.screenshotPath = screenshotPath;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getTracePath() {
        return tracePath;
    }

    public void setTracePath(String tracePath) {
        this.tracePath = tracePath;
    }

    public String getReportPath() {
        return reportPath;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    public String getConsoleLogs() {
        return consoleLogs;
    }

    public void setConsoleLogs(String consoleLogs) {
        this.consoleLogs = consoleLogs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
