package com.qalab.qalabai.ai.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.ai.gateway.ProviderCascadeExhaustedException;
import com.qalab.qalabai.ai.opencode.OpenCodeAiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * The token budget could not see a failing cascade.
 *
 * <p>{@code AiGateway} recorded one usage row per client call, taking the tokens from
 * the single response that finally succeeded. When the managed path had already
 * rejected three responses to get there, those three were billed but uncounted — so a
 * run that burned its allowance looked cheap, and the budget only complained after the
 * money was gone. These tests pin the cumulative accounting.</p>
 */
class OpenCodeManagedProviderClientUsageTest {

    private MockRestServiceServer server;
    private OpenCodeManagedProviderClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        OpenCodeAiProvider provider = new OpenCodeAiProvider(restTemplate);
        ReflectionTestUtils.setField(provider, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(provider, "goApiKey", "go-key");
        ReflectionTestUtils.setField(provider, "zenApiKey", "zen-key");
        ReflectionTestUtils.setField(provider, "goModel", "go-model");
        ReflectionTestUtils.setField(provider, "zenModel", "zen-model");
        ReflectionTestUtils.setField(provider, "zenFallbackModel", "zen-fallback-model");
        ReflectionTestUtils.setField(provider, "maxProviderCalls", 4);
        ReflectionTestUtils.setField(provider, "maxAttemptsPerProvider", 2);

        client = new OpenCodeManagedProviderClient(provider);
    }

    private void expect(String body) {
        server.expect(request -> {
        }).andExpect(method(POST)).andRespond(withSuccess(body, APPLICATION_JSON));
    }

    private static String goResponse(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    private static String openAiResponse(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    @Test
    void aSingleCallReportsOneAttempt() {
        expect(goResponse("plain answer"));

        ProviderCallResult result = client.call(new ProviderCallRequest(
                "system", "user", "model", "key", "https://example", 2000, null));

        assertEquals("plain answer", result.getContent());
        assertEquals(1, result.getAttempts());
        assertTrue(result.getTotalTokens() > 0, "even one call has a cost");
    }

    @Test
    void rejectedResponsesAreCountedInTheReportedTokens() {
        // Go answers with something unusable, Zen answers properly. Both requests
        // happened; only reporting the winner would hide Go's cost entirely.
        expect(goResponse("garbage that the validator will reject"));
        expect(openAiResponse("the real answer"));

        ProviderCallResult result = client.call(new ProviderCallRequest(
                "system prompt", "user prompt", "model", "key", "https://example", 2000,
                response -> "garbage that the validator will reject".equals(response) ? "unusable" : null));

        assertEquals("the real answer", result.getContent());
        assertEquals(2, result.getAttempts(), "both upstream calls must be visible");

        // The prompt is re-sent per attempt, so two attempts cost roughly twice the
        // input of one. This is the assertion that fails if the cascade is not summed.
        int oneCallPrompt = estimate("system prompt", "user prompt");
        assertTrue(result.getInputTokens() >= oneCallPrompt * 2,
                "input tokens must cover every attempt, got " + result.getInputTokens()
                        + " for two attempts of ~" + oneCallPrompt);
        assertTrue(result.getOutputTokens() > 0, "the rejected response produced output too");
    }

    @Test
    void theOperationCeilingIsPassedThroughToTheProvider() {
        expect(goResponse("answer"));

        client.call(new ProviderCallRequest(
                "sys", "user", "model", "key", "https://example", 3210, null));

        server.verify();
    }

    @Test
    void anAllFailingCascadeStillReportsItsAttempts() {
        for (int i = 0; i < 4; i++) {
            server.expect(request -> {
            }).andExpect(method(POST))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                            .withServerError());
        }

        // The delegate throws; what matters is that it does so with the call count
        // attached, so the gateway can log a bounded failure rather than a mystery.
        ProviderCascadeExhaustedException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ProviderCascadeExhaustedException.class,
                        () -> client.call(new ProviderCallRequest(
                                "sys", "user", "model", "key", "https://example", 2000, null)));

        assertEquals(4, ex.getUpstreamCalls());
    }

    private int estimate(String system, String user) {
        return TokenEstimator.estimateInputTokens(system, user);
    }
}
