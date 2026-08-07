package com.qalab.qalabai.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "failure_analysis")
public class FailureAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long executionId;

    @Column(nullable = false)
    private String failureType;

    @Column
    private Integer confidence;

    @Column(length = 2000)
    private String summary;

    @Column
    private String affectedElement;

    @Column
    private Boolean healingCandidate;

    @Column(length = 5000)
    private String analysisJson;

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

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
    }

    public Integer getConfidence() {
        return confidence;
    }

    public void setConfidence(Integer confidence) {
        this.confidence = confidence;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getAffectedElement() {
        return affectedElement;
    }

    public void setAffectedElement(String affectedElement) {
        this.affectedElement = affectedElement;
    }

    public Boolean getHealingCandidate() {
        return healingCandidate;
    }

    public void setHealingCandidate(Boolean healingCandidate) {
        this.healingCandidate = healingCandidate;
    }

    public String getAnalysisJson() {
        return analysisJson;
    }

    public void setAnalysisJson(String analysisJson) {
        this.analysisJson = analysisJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
