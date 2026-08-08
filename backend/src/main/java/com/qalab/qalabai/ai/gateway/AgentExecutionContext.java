package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.agent.ProjectContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared context handed down a workflow so all participating AI calls share the
 * same budget (never reset per agent), provider configuration and memory.
 */
public class AgentExecutionContext {

    private ProjectContext projectContext;
    private String operationId;
    private AiProviderConfig providerConfig;
    private TokenBudget tokenBudget;
    private boolean budgetSoftExceeded;
    private Map<String, Object> memoryContext = new HashMap<>();

    public AgentExecutionContext() {
    }

    public ProjectContext getProjectContext() {
        return projectContext;
    }

    public void setProjectContext(ProjectContext projectContext) {
        this.projectContext = projectContext;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public AiProviderConfig getProviderConfig() {
        return providerConfig;
    }

    public void setProviderConfig(AiProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }

    public TokenBudget getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(TokenBudget tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    /** True when a SOFT budget policy let the call through despite the allowance being exhausted. */
    public boolean isBudgetSoftExceeded() {
        return budgetSoftExceeded;
    }

    public void setBudgetSoftExceeded(boolean budgetSoftExceeded) {
        this.budgetSoftExceeded = budgetSoftExceeded;
    }

    public Map<String, Object> getMemoryContext() {
        return memoryContext;
    }

    public void setMemoryContext(Map<String, Object> memoryContext) {
        this.memoryContext = memoryContext;
    }

    public void putMemory(String key, Object value) {
        this.memoryContext.put(key, value);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final AgentExecutionContext ctx = new AgentExecutionContext();

        public Builder projectContext(ProjectContext projectContext) {
            ctx.setProjectContext(projectContext);
            return this;
        }

        public Builder operationId(String operationId) {
            ctx.setOperationId(operationId);
            return this;
        }

        public Builder providerConfig(AiProviderConfig providerConfig) {
            ctx.setProviderConfig(providerConfig);
            return this;
        }

        public Builder tokenBudget(TokenBudget tokenBudget) {
            ctx.setTokenBudget(tokenBudget);
            return this;
        }

        public Builder memory(String key, Object value) {
            ctx.putMemory(key, value);
            return this;
        }

        public AgentExecutionContext build() {
            return ctx;
        }
    }
}
