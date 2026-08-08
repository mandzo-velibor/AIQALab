package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.ai.gateway.BudgetPolicy;

/**
 * Updates the budget enforcement policy for the default account.
 * A {@code null} policy resets to the plan default.
 */
public record V1UpdateBudgetPolicyRequest(BudgetPolicy policy) {
}
