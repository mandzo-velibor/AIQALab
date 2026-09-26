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
    private AiGatewayProperties properties;
    private ManagedCredentials managedCredentials;
    private CredentialStore credentialStore;
    private AccountService accountService;
    private UsageService usageService;
    private RateLimiter rateLimiter;
    private ProviderClient client;
    private TokenBudgetService budgetService;

    @BeforeEach
    void setUp() {
        client = mock(ProviderClient.class);
        when(client.type()).thenReturn(AiProviderType.OLLAMA);
        when(client.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ProviderCallResult("ok", 10, 5, false, "gpt-oss:20b"));

        properties = new AiGatewayProperties();
        // This suite exercises budget enforcement, not model resolution. A model must
        // be configured because the gateway now rejects a missing one loudly instead
        // of sending {"model": null} to the provider — the stub client here would
        // happily accept it, but a real one would not.
        AiGatewayProperties.ProviderEndpoint ollama = new AiGatewayProperties.ProviderEndpoint();
        ollama.setBaseUrl("https://ollama.test/v1");
        ollama.setModel("gpt-oss:20b");
        properties.getProviders().put("ollama", ollama);
        managedCredentials = mock(ManagedCredentials.class);
        when(managedCredentials.keyFor(AiProviderType.OLLAMA))
                .thenReturn(java.util.Optional.of("test-key"));
        credentialStore = mock(CredentialStore.class);
        accountService = mock(AccountService.class);
        Account account = new Account();
        account.setId(1L);
        account.setName("default");
        when(accountService.defaultAccount()).thenReturn(account);

        budgetService = mock(TokenBudgetService.class);
        usageService = mock(UsageService.class);
        rateLimiter = mock(RateLimiter.class);
        // Both overloads must be stubbed. The gateway calls the account-scoped
        // allow(provider, accountId), and Mockito does NOT delegate an unstubbed
        // default method to the real implementation — it returns false, which would
        // rate-limit every call in this suite.
        when(rateLimiter.allow(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(rateLimiter.allow(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);

        gateway = new AiGateway(properties, managedCredentials, credentialStore,
                accountService, budgetService, usageService, rateLimiter,
                new ProviderPricingRegistry(), java.util.List.of(client),
                new ProviderResilience(5, 30, 8, 100));
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

    @Test
    void rateLimitingRejectsBeforeAnyProviderCall() {
        when(budgetService.currentBudget()).thenReturn(new TokenBudget(0L, 0L, false));
        RateLimiter denying = new RateLimiter() {
            @Override
            public boolean allow(AiProviderType provider) {
                return false;
            }
        };
        AiGateway limited = new AiGateway(properties, managedCredentials, credentialStore,
                accountService, budgetService, usageService, denying,
                new ProviderPricingRegistry(), java.util.List.of(client),
                new ProviderResilience(5, 30, 8, 100));

        ApiException e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> limited.complete(request(), null));

        org.junit.jupiter.api.Assertions.assertEquals("AI_RATE_LIMITED", e.getCode());
        // The message must be actionable, not just a refusal.
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("rate-limit-account-rps"),
                "should name the knob to turn: " + e.getMessage());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .call(org.mockito.ArgumentMatchers.any());
    }
}