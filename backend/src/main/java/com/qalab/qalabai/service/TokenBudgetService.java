package com.qalab.qalabai.service;

import com.qalab.qalabai.ai.gateway.AiCredentialMode;
import com.qalab.qalabai.ai.gateway.BudgetPolicy;
import com.qalab.qalabai.ai.gateway.Plan;
import com.qalab.qalabai.ai.gateway.TokenBudget;
import com.qalab.qalabai.config.AiGatewayProperties;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.repository.AiUsageRecordRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Computes the token budget for an account.
 *
 * <p>Only MANAGED calls consume the allowance. BYOK/LOCAL calls are recorded
 * but do not count against the budget. A limit of {@code 0} means unlimited
 * (used to reserve PRO/TEAM until billing lands).</p>
 */
@Service
public class TokenBudgetService {

    private final AccountService accountService;
    private final AiUsageRecordRepository usageRepository;
    private final AiGatewayProperties properties;

    public TokenBudgetService(AccountService accountService,
                              AiUsageRecordRepository usageRepository,
                              AiGatewayProperties properties) {
        this.accountService = accountService;
        this.usageRepository = usageRepository;
        this.properties = properties;
    }

    public TokenBudget currentBudget() {
        Account account = accountService.defaultAccount();
        long limit = limitFor(account.getPlan());
        if (limit <= 0) {
            return new TokenBudget(limit, 0, false,
                    policyFor(account));
        }
        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
        long used = usageRepository.sumTotalTokensByAccountAndModeSince(
                account.getId(), AiCredentialMode.MANAGED, startOfMonth);
        return new TokenBudget(limit, used, limit - used <= 0,
                policyFor(account));
    }

    /** Effective enforcement policy: explicit account override, else plan default. */
    public BudgetPolicy policyFor(Account account) {
        if (account.getBudgetPolicy() != null) {
            return account.getBudgetPolicy();
        }
        return BudgetPolicy.defaultFor(account.getPlan());
    }

    public long limitFor(Plan plan) {
        return switch (plan) {
            case FREE -> properties.getFreeMonthlyTokenLimit();
            default -> 0L;
        };
    }
}
