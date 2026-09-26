package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.config.AiGatewayProperties;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.service.AccountService;
import com.qalab.qalabai.service.TokenBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bounding the cascade inside the provider is not sufficient on its own.
 *
 * <p>{@code AiGateway.executeWithRetry} retries a failed client call up to
 * {@code max-retries} times (default 2, so three attempts) and used to catch
 * everything. A managed cascade that had already spent its own budget of four upstream
 * calls was therefore re-run from scratch twice more: <strong>12 upstream LLM calls for
 * one logical operation</strong>, plus a backoff sleep between each. Capping the
 * provider alone would have left the real worst case untouched while appearing to fix
 * it.</p>
 *
 * <p>An exhausted cascade means every configured provider was tried within its
 * budget, so the gateway must not retry it.</p>
 */
class AiGatewayCascadeBoundTest {

    private AiGateway gateway;
    private AiGatewayProperties properties;
    private ProviderClient client;

    @BeforeEach
    void setUp() {
        client = mock(ProviderClient.class);
        when(client.type()).thenReturn(AiProviderType.AIQALAB);

        properties = new AiGatewayProperties();
        AiGatewayProperties.ProviderEndpoint endpoint = new AiGatewayProperties.ProviderEndpoint();
        endpoint.setBaseUrl("https://managed.test/v1");
        endpoint.setModel("managed-model");
        properties.getProviders().put("aiqalab", endpoint);

        ManagedCredentials managedCredentials = mock(ManagedCredentials.class);
        when(managedCredentials.keyFor(AiProviderType.AIQALAB))
                .thenReturn(java.util.Optional.of("managed-key"));

        CredentialStore credentialStore = mock(CredentialStore.class);
        AccountService accountService = mock(AccountService.class);
        Account account = new Account();
        account.setId(1L);
        account.setName("default");
        when(accountService.defaultAccount()).thenReturn(account);

        TokenBudgetService budgetService = mock(TokenBudgetService.class);
        // A budget must exist or the gateway dereferences null before it ever reaches
        // the client. This suite is about the retry bound, not budget enforcement.
        when(budgetService.currentBudget())
                .thenReturn(new TokenBudget(1_000_000, 1_000_000, false, BudgetPolicy.NONE));
        UsageService usageService = mock(UsageService.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.allow(any())).thenReturn(true);
        when(rateLimiter.allow(any(), any())).thenReturn(true);

        gateway = new AiGateway(properties, managedCredentials, credentialStore,
                accountService, budgetService, usageService, rateLimiter,
                new ProviderPricingRegistry(), java.util.List.of(client),
                new ProviderResilience(5, 30, 8, 100), metrics());
    }

    private AiRequest request() {
        return AiRequest.builder(AiOperation.ANALYZE, "system", "user")
                .provider(AiProviderType.AIQALAB)
                .build();
    }

    @Test
    void anExhaustedCascadeIsNotRetriedByTheGateway() {
        when(client.call(any()))
                .thenThrow(new ProviderCascadeExhaustedException(
                        "Provider errors after 4 upstream call(s): all failed", 4));

        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-1").build();

        ApiException ex = assertThrows(ApiException.class, () -> gateway.complete(request(), ctx));

        // The whole point: one cascade, not three. 3 x 4 was 12 upstream calls.
        verify(client, times(1)).call(any());
        assertTrue(ex.getMessage().contains("4 upstream call"),
                "the failure must report what the cascade actually spent: " + ex.getMessage());
    }

    @Test
    void aTransientClientFailureIsStillRetried() {
        // The retry is the behaviour under test, not the nap between attempts.
        properties.setRetryBackoffMs(1);
        // The distinction matters: a cascade that exhausted its providers is a final
        // answer, but an ordinary transport error is not. Retrying must be reduced
        // only for the former, or resilience is lost along with the cost.
        when(client.call(any()))
                .thenThrow(new RuntimeException("connection reset"))
                .thenReturn(new ProviderCallResult("ok", 10, 5, false, "managed-model"));

        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-1").build();

        assertEquals("ok", gateway.complete(request(), ctx).getContent());
        verify(client, times(2)).call(any());
    }

    @Test
    void theCascadeBoundHoldsEvenWithRetriesEnabled() {
        properties.setMaxRetries(5);
        when(client.call(any()))
                .thenThrow(new ProviderCascadeExhaustedException("exhausted", 4));

        AgentExecutionContext ctx = AgentExecutionContext.builder().operationId("op-1").build();

        assertThrows(ApiException.class, () -> gateway.complete(request(), ctx));
        // A raised retry count must not resurrect the multiplied cost.
        verify(client, times(1)).call(any());
    }

    /** Metrics are exercised directly in AiMetricsTest; the gateway just needs one. */
    private com.qalab.qalabai.observability.AiMetrics metrics() {
        return new com.qalab.qalabai.observability.AiMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new ProviderResilience(5, 30, 8, 10));
    }
}
