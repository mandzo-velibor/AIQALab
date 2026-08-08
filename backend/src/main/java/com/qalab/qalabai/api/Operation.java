package com.qalab.qalabai.api;

import java.time.LocalDateTime;

/**
 * Lightweight, generic operation model used to track AI capabilities.
 *
 * <p>Prepared for future asynchronous execution, status polling and usage
 * tracking. Sprint 10 keeps operations synchronous; the model only establishes
 * the abstraction.</p>
 */
public class Operation {

    private String operationId;
    private String type;
    private OperationStatus status;
    private String projectId;
    private String url;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private String error;

    public Operation() {
    }

    public Operation(String operationId, String type) {
        this.operationId = operationId;
        this.type = type;
        this.status = OperationStatus.RUNNING;
        this.createdAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = OperationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String error) {
        this.status = OperationStatus.FAILED;
        this.error = error;
        this.completedAt = LocalDateTime.now();
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public void setStatus(OperationStatus status) {
        this.status = status;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
