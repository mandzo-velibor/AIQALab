package com.qalab.qalabai.model;

import com.qalab.qalabai.ai.gateway.AiCredentialMode;
import com.qalab.qalabai.ai.gateway.AiOperation;
import com.qalab.qalabai.ai.gateway.AiProviderType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable record of a single AI call, for every credential mode (MANAGED,
 * BYOK, LOCAL). Managed calls consume the account allowance; BYOK/LOCAL calls
 * are recorded but do not consume the allowance.
 */
@Entity
@Table(name = "ai_usage_record")
public class AiUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column
    private Long projectId;

    @Column
    private String operationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AiOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiProviderType provider;

    @Column
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiCredentialMode credentialMode;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private int totalTokens;

    @Column(precision = 18, scale = 8)
    private BigDecimal estimatedCost;

    @Column(nullable = false)
    private boolean estimated;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public AiOperation getOperation() {
        return operation;
    }

    public void setOperation(AiOperation operation) {
        this.operation = operation;
    }

    public AiProviderType getProvider() {
        return provider;
    }

    public void setProvider(AiProviderType provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public AiCredentialMode getCredentialMode() {
        return credentialMode;
    }

    public void setCredentialMode(AiCredentialMode credentialMode) {
        this.credentialMode = credentialMode;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }

    public boolean isEstimated() {
        return estimated;
    }

    public void setEstimated(boolean estimated) {
        this.estimated = estimated;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
