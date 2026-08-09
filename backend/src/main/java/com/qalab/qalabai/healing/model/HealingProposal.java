package com.qalab.qalabai.healing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A persisted, human-reviewable self-healing proposal. The Core never applies
 * the proposed locator automatically — a human accepts or rejects it later.
 */
@Entity
@Table(name = "healing_proposal")
public class HealingProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String proposalId;

    @Column
    private Long projectId;

    @Column
    private String runId;

    @Column
    private String testId;

    @Column
    private String testName;

    @Column(nullable = false)
    private String classification;

    @Column(nullable = false, length = 500)
    private String originalLocator;

    @Column(nullable = false, length = 500)
    private String recommendedLocator;

    @Column(nullable = false)
    private Double confidence;

    @Column(nullable = false)
    private String confidenceLabel;

    @Column(nullable = false)
    private Boolean safeToApply;

    @Column(length = 2000)
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String alternativesJson;

    @Column
    private String originalLocatorHealth;

    @Column
    private Double originalLocatorStability;

    @Column
    private String recommendedLocatorHealth;

    @Column
    private Double recommendedLocatorStability;

    @Column
    private String recommendedStabilityLevel;

    @Column
    private Double recommendedSemanticScore;

    @Column
    private Double recommendedQualityScore;

    @Column(nullable = false)
    private String status;

    @Column
    private String reviewAction;

    @Column
    private LocalDateTime reviewedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public HealingProposal() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProposalId() {
        return proposalId;
    }

    public void setProposalId(String proposalId) {
        this.proposalId = proposalId;
    }

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

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getOriginalLocator() {
        return originalLocator;
    }

    public void setOriginalLocator(String originalLocator) {
        this.originalLocator = originalLocator;
    }

    public String getRecommendedLocator() {
        return recommendedLocator;
    }

    public void setRecommendedLocator(String recommendedLocator) {
        this.recommendedLocator = recommendedLocator;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getConfidenceLabel() {
        return confidenceLabel;
    }

    public void setConfidenceLabel(String confidenceLabel) {
        this.confidenceLabel = confidenceLabel;
    }

    public Boolean getSafeToApply() {
        return safeToApply;
    }

    public void setSafeToApply(Boolean safeToApply) {
        this.safeToApply = safeToApply;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getAlternativesJson() {
        return alternativesJson;
    }

    public void setAlternativesJson(String alternativesJson) {
        this.alternativesJson = alternativesJson;
    }

    public String getOriginalLocatorHealth() {
        return originalLocatorHealth;
    }

    public void setOriginalLocatorHealth(String originalLocatorHealth) {
        this.originalLocatorHealth = originalLocatorHealth;
    }

    public Double getOriginalLocatorStability() {
        return originalLocatorStability;
    }

    public void setOriginalLocatorStability(Double originalLocatorStability) {
        this.originalLocatorStability = originalLocatorStability;
    }

    public String getRecommendedLocatorHealth() {
        return recommendedLocatorHealth;
    }

    public void setRecommendedLocatorHealth(String recommendedLocatorHealth) {
        this.recommendedLocatorHealth = recommendedLocatorHealth;
    }

    public Double getRecommendedLocatorStability() {
        return recommendedLocatorStability;
    }

    public void setRecommendedLocatorStability(Double recommendedLocatorStability) {
        this.recommendedLocatorStability = recommendedLocatorStability;
    }

    public String getRecommendedStabilityLevel() {
        return recommendedStabilityLevel;
    }

    public void setRecommendedStabilityLevel(String recommendedStabilityLevel) {
        this.recommendedStabilityLevel = recommendedStabilityLevel;
    }

    public Double getRecommendedSemanticScore() {
        return recommendedSemanticScore;
    }

    public void setRecommendedSemanticScore(Double recommendedSemanticScore) {
        this.recommendedSemanticScore = recommendedSemanticScore;
    }

    public Double getRecommendedQualityScore() {
        return recommendedQualityScore;
    }

    public void setRecommendedQualityScore(Double recommendedQualityScore) {
        this.recommendedQualityScore = recommendedQualityScore;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReviewAction() {
        return reviewAction;
    }

    public void setReviewAction(String reviewAction) {
        this.reviewAction = reviewAction;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
