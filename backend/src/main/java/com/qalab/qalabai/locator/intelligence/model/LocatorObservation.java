package com.qalab.qalabai.locator.intelligence.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A single observation of a locator against a live page, used to compute
 * survival rate and build the historical intelligence trail for an element.
 */
@Entity
@Table(name = "locator_observation", indexes = {
        @Index(name = "idx_locator_observation_lookup",
                columnList = "projectId,elementFingerprint,observedAt")
})
public class LocatorObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long projectId;

    @Column
    private String executionId;

    @Column(nullable = false, length = 2048)
    private String pageUrl;

    @Column(nullable = false, length = 512)
    private String locator;

    @Column(nullable = false)
    private String strategy;

    @Column(nullable = false, length = 128)
    private String elementFingerprint;

    @Column(nullable = false)
    private Double score;

    @Column(nullable = false)
    private Double stabilityScore;

    @Column(nullable = false)
    private Double semanticScore;

    @Column(nullable = false)
    private Double uniqueness;

    @Column(nullable = false)
    private String health;

    /**
     * RESOLVED when the locator matched at least one element at observation
     * time, FAILED otherwise. Used to compute the survival rate.
     */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDateTime observedAt;

    @Lob
    @Column
    private String elementIdentityJson;

    public LocatorObservation() {
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

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
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

    public String getElementFingerprint() {
        return elementFingerprint;
    }

    public void setElementFingerprint(String elementFingerprint) {
        this.elementFingerprint = elementFingerprint;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Double getStabilityScore() {
        return stabilityScore;
    }

    public void setStabilityScore(Double stabilityScore) {
        this.stabilityScore = stabilityScore;
    }

    public Double getSemanticScore() {
        return semanticScore;
    }

    public void setSemanticScore(Double semanticScore) {
        this.semanticScore = semanticScore;
    }

    public Double getUniqueness() {
        return uniqueness;
    }

    public void setUniqueness(Double uniqueness) {
        this.uniqueness = uniqueness;
    }

    public String getHealth() {
        return health;
    }

    public void setHealth(String health) {
        this.health = health;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(LocalDateTime observedAt) {
        this.observedAt = observedAt;
    }

    public String getElementIdentityJson() {
        return elementIdentityJson;
    }

    public void setElementIdentityJson(String elementIdentityJson) {
        this.elementIdentityJson = elementIdentityJson;
    }
}
