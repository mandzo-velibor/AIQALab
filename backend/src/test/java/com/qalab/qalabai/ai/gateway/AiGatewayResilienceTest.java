package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.config.AiGatewayProperties;
import com.qalab.qalabai.model.Account;
import com.qalab.qalabai.service.AccountService;
import com.qalab.qalabai.service.TokenBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "A dead provider stops consuming calls after the threshold" is the criterion that
 * matters, and it is the one an end-to-end test can actually answer.
 *
 * <p>Everything here counts calls to a stub client. If the breaker is not working, the
 * count keeps climbing; if it is, the count stops.</p>
 */
class AiGatewayResilienceTest {

    private AiGateway gateway;
    private AiGatewayProperties properties;
    private ProviderClient client;
    private ProviderResilience resilience;
    private final AtomicInteger calls = new AtomicInteger();
    // Promoted to fields so the recovery test can rebuild a gateway with a fresh
    // resilience without re-deriving the whole fixture.
    private AccountService accounts;
    private TokenBudgetService budget;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        calls.set(0);
        client = mock(ProviderClient.class);
        when(client.type()).thenReturn(AiProviderType.OPENAI);

        properties = new AiGatewayProperties();
        properties.setRetryBackoffMs(1);
        properties.setMaxRetries(2);
        AiGatewayProperties.ProviderEndpoint endpoint = new AiGatewayProperties.ProviderEndpoint();
        endpoint.setBaseUrl("https://openai.test/v1");
        endpoint.setModel("gpt-4o-mini");
        properties.getProviders().put("openai", endpoint);

        ManagedCredentials managed = mock(ManagedCredentials.class);
        when(managed.keyFor(AiProviderType.OPENAI)).thenReturn(java.util.Optional.of("key"));
        CredentialStore credentials = mock(CredentialStore.class);
        accounts = mock(AccountService.class);
        Account account = new Account();
        account.setId(1L);
        account.setName("default");
        when(accounts.defaultAccount()).thenReturn(account);

        budget = mock(TokenBudgetService.class);
        when(budget.currentBudget())
                .thenReturn(new TokenBudget(1_000_000, 1_000_000, false, BudgetPolicy.NONE));
        limiter = mock(RateLimiter.class);
        when(limiter.allow(any())).thenReturn(true);
        when(limiter.allow(any(), any())).thenReturn(true);

        // Threshold 2 with no retries, so "after the threshold" is observable precisely.
        resilience = new ProviderResilience(2, 30, 8, 10);
        gateway = new AiGateway(properties, managed, credentials, accounts, budget,
                mock(UsageService.class), limiter, new ProviderPricingRegistry(),
                List.of(client), resilience);
    }

    private AiRequest request() {
        return AiRequest.builder(AiOperation.ANALYZE, "system", "user")
                .provider(AiProviderType.OPENAI)
                .build();
    }

    private AgentExecutionContext ctx() {
        return AgentExecutionContext.builder()
                .projectContext(new com.qalab.qalabai.agent.ProjectContext())
                .operationId("op-1")
                .build();
    }

    @Test
    void aDeadProviderStopsConsumingCallsOnceItsBreakerOpens() {
        when(client.call(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            throw new RuntimeException("connection refused");
        });

        // Each gateway call is 3 client calls (initial + 2 retries), and the breaker
        // opens after 2 failures — so the very first gateway call already trips it.
        assertThrows(ApiException.class, () -> gateway.complete(request(), ctx()));
        int afterFirst = calls.get();

        for (int i = 0; i < 5; i++) {
            assertThrows(ApiException.class, () -> gateway.complete(request(), ctx()));
        }

        assertEquals(afterFirst, calls.get(),
                "a dead provider must cost nothing once open; it made " + (calls.get() - afterFirst)
                        + " further calls");
    }

    @Test
    void anOpenBreakerIsReportedWithRetryGuidance() {
        when(client.call(any())).thenThrow(new RuntimeException("connection refused"));
        assertThrows(ApiException.class, () -> gateway.complete(request(), ctx()));

        ApiException ex = assertThrows(ApiException.class,
                () -> gateway.complete(request(), ctx()));

        assertEquals(ErrorCode.AI_PROVIDER_UNAVAILABLE, ex.getCode());
        assertTrue(ex.getMessage().contains("circuit"), "the user must be told why: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Retry"),
                "and what to do about it: " + ex.getMessage());
    }

    @Test
    void aSuccessfulCallKeepsTheBreakerClosed() {
        when(client.call(any())).thenReturn(
                new ProviderCallResult("ok", 10, 5, false, "gpt-4o-mini"));

        for (int i = 0; i < 10; i++) {
            assertEquals("ok", gateway.complete(request(), ctx()).getContent());
        }

        assertEquals(CircuitBreaker.State.CLOSED, resilience.breaker("OPENAI").state());
    }

    @Test
    void anIntermittentProviderRecoversWithoutOperatorAction() {
        // Three good calls, then two bad ones (which trip it), then good again. The point
        // is that recovery is automatic once the cooldown elapses — nobody has to restart
        // anything or clear a flag.
        AtomicInteger attempt = new AtomicInteger();
        when(client.call(any())).thenAnswer(invocation -> {
            int n = attempt.getAndIncrement();
            if (n >= 3 && n < 9) {
                throw new RuntimeException("flaky");
            }
            return new ProviderCallResult("ok", 10, 5, false, "gpt-4o-mini");
        });

        for (int i = 0; i < 3; i++) {
            gateway.complete(request(), ctx());
        }
        for (int i = 0; i < 2; i++) {
            assertThrows(ApiException.class, () -> gateway.complete(request(), ctx()));
        }
        assertEquals(CircuitBreaker.State.OPEN, resilience.breaker("OPENAI").state());

        // Past the cooldown the breaker half-opens, the probe succeeds, and calls flow
        // again. Driven through a fresh gateway so the sleep is not paid per test.
        ProviderResilience recovered = new ProviderResilience(2, 0, 8, 10);
        gateway = new AiGateway(properties, mock(ManagedCredentials.class), mock(CredentialStore.class),
                accounts, budget, mock(UsageService.class), limiter, new ProviderPricingRegistry(),
                List.of(client), recovered);
        // doReturn, not when(): re-stubbing a mock whose current answer throws makes
        // when() invoke it, and the throw escapes from the test setup.
        org.mockito.Mockito.doReturn(new ProviderCallResult("ok", 10, 5, false, "gpt-4o-mini"))
                .when(client).call(any());

        assertEquals("ok", gateway.complete(request(), ctx()).getContent());
    }

    @Test
    void aFullBulkheadIsRefusedRatherThanQueued() throws Exception {
        ProviderResilience tiny = new ProviderResilience(5, 30, 1, 20);
        gateway = new AiGateway(properties, mock(ManagedCredentials.class), mock(CredentialStore.class),
                accounts, budget, mock(UsageService.class), limiter,
                new ProviderPricingRegistry(), List.of(client), tiny);

        ProviderResilience.Permit held = tiny.acquireSlot("OPENAI");
        try {
            ApiException ex = assertThrows(ApiException.class,
                    () -> gateway.complete(request(), ctx()));

            assertEquals(ErrorCode.AI_PROVIDER_UNAVAILABLE, ex.getCode());
            assertTrue(ex.getMessage().contains("concurrency limit"),
                    "the refusal must say the bulkhead is why: " + ex.getMessage());
        } finally {
            held.close();
        }
    }

    @Test
    void breakerStateIsReadableSoItCanBeDiagnosed() {
        when(client.call(any())).thenThrow(new RuntimeException("down"));

        assertThrows(ApiException.class, () -> gateway.complete(request(), ctx()));
        assertThrows(ApiException.class, () -> gateway.complete(request(), ctx()));

        List<CircuitBreaker.Stats> state = gateway.circuitState();
        assertEquals(1, state.size());
        assertEquals("OPENAI", state.get(0).provider());
        assertEquals(CircuitBreaker.State.OPEN, state.get(0).state());
    }
}
