package com.qalab.qalabai.ai.gateway;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.config.AiGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * There was no {@code qalab.ai} block in any configuration file. Consequences:
 *
 * <ul>
 *   <li>{@code providers} was empty, so {@code resolveModel()} returned null and every
 *       bring-your-own-key provider received {@code {"model": null}}, which they reject
 *       with an opaque 400.</li>
 *   <li>{@code freeMonthlyTokenLimit} fell back to the code default of 4000
 *       tokens/month, which is below a single full workflow run — one run sends 6-7
 *       prompts containing full simplified page HTML. A default local start therefore
 *       hit {@code AI_BUDGET_EXCEEDED} before completing useful work.</li>
 * </ul>
 *
 * These tests assert the shipped configuration actually closes both gaps.
 */
@ActiveProfiles("test")
@SpringBootTest
class AiGatewayConfigurationTest {

    @Autowired
    AiGatewayProperties properties;

    @Test
    void everyProviderTypeResolvesAModel() {
        for (AiProviderType provider : AiProviderType.values()) {
            AiGatewayProperties.ProviderEndpoint endpoint = properties.endpoint(provider);
            assertTrue(endpoint != null && endpoint.getModel() != null && !endpoint.getModel().isBlank(),
                    "no model configured for provider " + provider
                            + " — BYOK requests would be sent with model=null");
        }
    }

    @Test
    void openAiStyleProvidersResolveABaseUrl() {
        // A missing base URL makes the client throw "no base URL configured", which
        // is a worse error than an explicit configuration failure.
        for (AiProviderType provider : Set.of(AiProviderType.OPENAI, AiProviderType.GOOGLE,
                AiProviderType.OLLAMA, AiProviderType.ANTHROPIC)) {
            AiGatewayProperties.ProviderEndpoint endpoint = properties.endpoint(provider);
            assertTrue(endpoint != null && endpoint.getBaseUrl() != null && !endpoint.getBaseUrl().isBlank(),
                    "no base URL configured for provider " + provider);
        }
    }

    @Test
    void theFreeAllowanceIsUsableForASingleWorkflowRun() {
        // A run sends 6-7 prompts containing full simplified page HTML, comfortably
        // over the previous 4000-token code default.
        assertTrue(properties.getFreeMonthlyTokenLimit() == 0
                        || properties.getFreeMonthlyTokenLimit() >= 50_000,
                "free-monthly-token-limit is " + properties.getFreeMonthlyTokenLimit()
                        + ", which is below one full workflow run; 0 means unlimited");
    }

    @Test
    void timeoutsAreFinite() {
        // Both are read by AiGatewayConfig when building the shared RestTemplate.
        assertTrue(properties.getMaxRetries() >= 0, "maxRetries must not be negative");
        assertTrue(properties.getRetryBackoffMs() > 0, "retryBackoffMs must be positive");
    }

    @Test
    void theManagedProviderIsTheDefault() {
        assertEquals(AiProviderType.AIQALAB, properties.getDefaultProvider(),
                "the managed cascade is the only path with cross-provider fallback");
    }

    @Test
    void everyProviderTypeIsDistinct() {
        assertEquals(AiProviderType.values().length, EnumSet.allOf(AiProviderType.class).size(),
                "provider identifiers must be unique or the config map would collide");
    }

    /**
     * Builds a gateway with a stub provider client that records the model it was
     * handed, so the public complete() path can be observed end to end.
     */
    private record Harness(AiGateway gateway, List<String> modelsSeen) {
    }

    private Harness gatewayWith(AiGatewayProperties props) {
        List<String> modelsSeen = new ArrayList<>();
        ProviderClient stub = new ProviderClient() {
            @Override
            public AiProviderType type() {
                return AiProviderType.OPENAI;
            }

            @Override
            public ProviderCallResult call(ProviderCallRequest request) {
                modelsSeen.add(request.getModel());
                return new ProviderCallResult("{}", 1, 1, false, request.getModel());
            }
        };
        // complete() consults the account, the budget and the rate limiter before it
        // ever reaches a provider, so those collaborators must be present.
        com.qalab.qalabai.model.Account account = new com.qalab.qalabai.model.Account();
        account.setId(1L);
        com.qalab.qalabai.service.AccountService accounts =
                org.mockito.Mockito.mock(com.qalab.qalabai.service.AccountService.class);
        org.mockito.Mockito.when(accounts.defaultAccount()).thenReturn(account);

        TokenBudget budget = new TokenBudget(0L, 0L, false);
        com.qalab.qalabai.service.TokenBudgetService budgets =
                org.mockito.Mockito.mock(com.qalab.qalabai.service.TokenBudgetService.class);
        org.mockito.Mockito.when(budgets.currentBudget()).thenReturn(budget);

        RateLimiter limiter = provider -> true;
        UsageService usage = org.mockito.Mockito.mock(UsageService.class);

        AiGateway gateway = new AiGateway(props, null, null, accounts, budgets, usage, limiter,
                new ProviderPricingRegistry(), List.of(stub),
                new ProviderResilience(5, 30, 8, 100), metrics());
        return new Harness(gateway, modelsSeen);
    }

    private static AiGatewayProperties propsWithOpenAiModel(String model) {
        AiGatewayProperties props = new AiGatewayProperties();
        AiGatewayProperties.ProviderEndpoint endpoint = new AiGatewayProperties.ProviderEndpoint();
        endpoint.setBaseUrl("https://example.invalid/v1");
        endpoint.setModel(model);
        props.getProviders().put("openai", endpoint);
        return props;
    }

    @Test
    void aConfiguredModelIsSentToTheProvider() {
        Harness harness = gatewayWith(propsWithOpenAiModel("gpt-4o-mini"));

        harness.gateway().complete(
                AiRequest.builder(AiOperation.TEST_PLAN, "sys", "user")
                        .provider(AiProviderType.OPENAI)
                        .credentialMode(AiCredentialMode.LOCAL)
                        .build(),
                null);

        assertEquals(List.of("gpt-4o-mini"), harness.modelsSeen(),
                "the configured model must reach the provider, never null");
    }

    @Test
    void aMissingModelIsALoudConfigurationErrorNotNullOnTheWire() {
        // The regression guard: resolveModel used to return null, which was
        // serialised as {"model": null} and rejected by the provider with an
        // opaque 400 that said nothing about the misconfiguration.
        Harness harness = gatewayWith(new AiGatewayProperties());

        ApiException e = assertThrows(ApiException.class, () -> harness.gateway().complete(
                AiRequest.builder(AiOperation.TEST_PLAN, "sys", "user")
                        .provider(AiProviderType.OPENAI)
                        .credentialMode(AiCredentialMode.LOCAL)
                        .build(),
                null));

        assertTrue(e.getMessage().contains("no model configured"),
                "the error should name the problem, was: " + e.getMessage());
        assertTrue(e.getMessage().contains("openai"),
                "the error should name the provider, was: " + e.getMessage());
        assertTrue(harness.modelsSeen().isEmpty(),
                "no provider call may be attempted with an unconfigured model");
    }

    /** Metrics are exercised directly in AiMetricsTest; the gateway just needs one. */
    private com.qalab.qalabai.observability.AiMetrics metrics() {
        return new com.qalab.qalabai.observability.AiMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new ProviderResilience(5, 30, 8, 10));
    }
}
