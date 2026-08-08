package com.qalab.qalabai.service;

import com.qalab.qalabai.ai.gateway.BudgetPolicy;
import com.qalab.qalabai.ai.gateway.Plan;
import com.qalab.qalabai.ai.gateway.TokenBudget;
import com.qalab.qalabai.config.AiGatewayProperties;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.repository.AiUsageRecordRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenBudgetServiceTest {

    private final AccountService accountService = mock(AccountService.class);
    private final AiUsageRecordRepository usageRepository = mock(AiUsageRecordRepository.class);
    private final AiGatewayProperties properties = new AiGatewayProperties();

    private final TokenBudgetService service =
            new TokenBudgetService(accountService, usageRepository, properties);

    @Test
    void defaultPolicyIsPlanBased() {
        Account free = account(Plan.FREE, null);
        assertEquals(BudgetPolicy.HARD, service.policyFor(free));

        Account pro = account(Plan.PRO, null);
        assertEquals(BudgetPolicy.NONE, service.policyFor(pro));
    }

    @Test
    void explicitAccountPolicyWins() {
        Account account = account(Plan.FREE, BudgetPolicy.SOFT);
        assertEquals(BudgetPolicy.SOFT, service.policyFor(account));
    }

    @Test
    void softPolicyNeverHardStops() {
        Account account = account(Plan.FREE, BudgetPolicy.SOFT);
        when(accountService.defaultAccount()).thenReturn(account);
        when(usageRepository.sumTotalTokensByAccountAndModeSince(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(properties.getFreeMonthlyTokenLimit());

        TokenBudget budget = service.currentBudget();
        assertTrue(budget.isExhausted());
        assertFalse(budget.isHardStop());
        assertTrue(budget.isSoftExceeded());
        assertEquals(BudgetPolicy.SOFT, budget.getPolicy());
    }

    private Account account(Plan plan, BudgetPolicy policy) {
        Account account = new Account();
        account.setId(1L);
        account.setPlan(plan);
        account.setBudgetPolicy(policy);
        return account;
    }
}
