package com.qalab.qalabai.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "locator_history")
public class LocatorHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String elementName;

    @Column(nullable = false, length = 500)
    private String locator;

    @Column
    private String strategy;

    @Column
    private Integer confidence;

    @Column(nullable = false)
    private String status;

    @Column
    private LocalDateTime lastSuccessfulExecution;

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

    public String getElementName() {
        return elementName;
    }

    public void setElementName(String elementName) {
        this.elementName = elementName;
    }

    public String getLocator() {
        return locator;
    }

    public void setLocator(String locator) {
        this.locator = locator;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Integer getConfidence() {
        return confidence;
    }

    public void setConfidence(Integer confidence) {
        this.confidence = confidence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getLastSuccessfulExecution() {
        return lastSuccessfulExecution;
    }

    public void setLastSuccessfulExecution(LocalDateTime lastSuccessfulExecution) {
        this.lastSuccessfulExecution = lastSuccessfulExecution;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
