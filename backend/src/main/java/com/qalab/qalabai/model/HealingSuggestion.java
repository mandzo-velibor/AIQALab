package com.qalab.qalabai.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "healing_suggestion")
public class HealingSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long executionId;

    @Column
    private Long failureAnalysisId;

    @Column(nullable = false)
    private String elementName;

    @Column(nullable = false, length = 500)
    private String oldLocator;

    @Column(nullable = false, length = 500)
    private String newLocator;

    @Column
    private Integer confidence;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private String status;

    @Column
    private String approvedBy;

    @Column
    private LocalDateTime approvedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
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

    public Long getFailureAnalysisId() {
        return failureAnalysisId;
    }

    public void setFailureAnalysisId(Long failureAnalysisId) {
        this.failureAnalysisId = failureAnalysisId;
    }

    public String getElementName() {
        return elementName;
    }

    public void setElementName(String elementName) {
        this.elementName = elementName;
    }

    public String getOldLocator() {
        return oldLocator;
    }

    public void setOldLocator(String oldLocator) {
        this.oldLocator = oldLocator;
    }

    public String getNewLocator() {
        return newLocator;
    }

    public void setNewLocator(String newLocator) {
        this.newLocator = newLocator;
    }

    public Integer getConfidence() {
        return confidence;
    }

    public void setConfidence(Integer confidence) {
        this.confidence = confidence;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
