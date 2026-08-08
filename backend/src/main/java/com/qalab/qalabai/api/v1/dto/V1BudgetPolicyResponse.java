package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.ai.gateway.BudgetPolicy;

import java.time.LocalDateTime;

public record V1BudgetPolicyResponse(
        BudgetPolicy policy,
        String plan,
        Long limit,
        Long used,
        Long remaining,
        boolean hardStop,
        boolean softExceeded,
        LocalDateTime updatedAt
) {
}
