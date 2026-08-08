package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.config.AiGatewayProperties;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.service.AccountService;
import com.qalab.qalabai.service.TokenBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiGatewayBudgetEnforcementTest {

    private AiGateway gateway;
    private ProviderClient client;
    private TokenBudgetService budgetService;

    @BeforeEach
    void setUp() {
        client = mock(ProviderClient.class);
        when(client.type()).thenReturn(AiProviderType.OLLAMA);
        when(client.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ProviderCallResult("ok", 10, 5, false, "gpt-oss:20b"));

        AiGatewayProperties properties = new AiGatewayProperties();
        ManagedCredentials managedCredentials = mock(ManagedCredentials.class);
        when(managedCredentials.keyFor(AiProviderType.OLLAMA))
                .thenReturn(java.util.Optional.of("test-key"));
        CredentialStore credentialStore = mock(CredentialStore.class);
        AccountService accountService = mock(AccountService.class);
        Account account = new Account();
        account.setId(1L);
        account.setName("default");
        when(accountService.defaultAccount()).thenReturn(account);

        budgetService = mock(TokenBudgetService.class);
        UsageService usageService = mock(UsageService.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.allow(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        gateway = new AiGateway(properties, managedCredentials, credentialStore,
                accountService, budgetService, usageService, rateLimiter,
                new ProviderPricingRegistry(), java.util.List.of(client));
    }

    private AiRequest request() {
        return AiRequest.builder(AiOperation.ANALYZE, "system", "user")
                .provider(AiProviderType.OLLAMA)
                .build();
    }

    @Test
    void hardStopBlocksManagedCall() {
        when(budgetService.currentBudget()).thenReturn(new TokenBudget(100, 100, true, BudgetPolicy.HARD));
        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-1").build();

        ApiException ex = assertThrows(ApiException.class, () -> gateway.complete(request(), ctx));
        assertEquals(ErrorCode.AI_BUDGET_EXCEEDED, ex.getCode());
        verify(client, never()).call(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hardStopAllowsCallWhenNotExhausted() {
        when(budgetService.currentBudget()).thenReturn(new TokenBudget(100, 40, false, BudgetPolicy.HARD));
        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-2").build();

        AiResponse response = gateway.complete(request(), ctx);
        assertEquals("ok", response.getContent());
        assertFalse(ctx.isBudgetSoftExceeded());
    }

    @Test
    void softStopAllowsCallAndFlagsContext() {
        when(budgetService.currentBudget()).thenReturn(new TokenBudget(100, 100, true, BudgetPolicy.SOFT));
        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-3").build();

        AiResponse response = gateway.complete(request(), ctx);
        assertEquals("ok", response.getContent());
        assertTrue(ctx.isBudgetSoftExceeded());
    }

    @Test
    void nonePolicyIgnoresExhaustedAllowance() {
        when(budgetService.currentBudget()).thenReturn(new TokenBudget(100, 100, true, BudgetPolicy.NONE));
        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-4").build();

        AiResponse response = gateway.complete(request(), ctx);
        assertEquals("ok", response.getContent());
        assertFalse(ctx.isBudgetSoftExceeded());
    }

    @Test
    void unlimitedBudgetIsNeverEnforced() {
        when(budgetService.currentBudget()).thenReturn(new TokenBudget(0, 0, false, BudgetPolicy.HARD));
        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-5").build();

        AiResponse response = gateway.complete(request(), ctx);
        assertEquals("ok", response.getContent());
    }
}
