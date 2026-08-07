package com.qalab.qalabai.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "generated_test")
public class GeneratedTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String pageUrl;

    @Column
    private Long projectId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String scenarioName;

    @Column
    private String testFileName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String testCode;

    @Column(columnDefinition = "TEXT")
    private String pageObjectCode;

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

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getTestFileName() {
        return testFileName;
    }

    public void setTestFileName(String testFileName) {
        this.testFileName = testFileName;
    }

    public String getTestCode() {
        return testCode;
    }

    public void setTestCode(String testCode) {
        this.testCode = testCode;
    }

    public String getPageObjectCode() {
        return pageObjectCode;
    }

    public void setPageObjectCode(String pageObjectCode) {
        this.pageObjectCode = pageObjectCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
