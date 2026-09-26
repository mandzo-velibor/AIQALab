package com.qalab.qalabai.ai.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.ai.gateway.ProviderCascadeExhaustedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The provider cascade is the system's largest hidden cost multiplier, and nothing
 * bounded it.
 *
 * <p>It used to be a retry loop of {@code MAX_ATTEMPTS=3} wrapped around a cascade
 * that tried Go → Zen → Zen-fallback → Gemini → Ollama. One logical operation could
 * therefore issue up to <strong>15 upstream LLM calls</strong>, each requesting
 * {@code max_tokens: 12000}, with a backoff sleep between the outer attempts. On a
 * free or rate-limited tier that is exactly the "why is it so slow" complaint — and
 * because only the winning response was ever counted, the token budget could not see
 * any of it.
 *
 * <p>These tests assert the bound directly rather than inferring it from a log line.</p>
 */
class OpenCodeCascadeBoundTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private OpenCodeAiProvider provider;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        provider = new OpenCodeAiProvider(restTemplate);
        ReflectionTestUtils.setField(provider, "objectMapper", new ObjectMapper());

        // Every credential set, so all five candidates are in the cascade and the
        // worst case is the one under test.
        ReflectionTestUtils.setField(provider, "goApiKey", "go-key");
        ReflectionTestUtils.setField(provider, "zenApiKey", "zen-key");
        ReflectionTestUtils.setField(provider, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(provider, "ollamaApiKey", "ollama-key");
        ReflectionTestUtils.setField(provider, "goModel", "go-model");
        ReflectionTestUtils.setField(provider, "zenModel", "zen-model");
        ReflectionTestUtils.setField(provider, "zenFallbackModel", "zen-fallback-model");
        ReflectionTestUtils.setField(provider, "geminiModel", "gemini-model");
        ReflectionTestUtils.setField(provider, "ollamaModel", "ollama-model");
        ReflectionTestUtils.setField(provider, "geminiBaseUrl", "https://gemini.example/v1beta");
        ReflectionTestUtils.setField(provider, "ollamaBaseUrl", "https://ollama.example/v1");
        ReflectionTestUtils.setField(provider, "maxProviderCalls", 4);
        ReflectionTestUtils.setField(provider, "maxAttemptsPerProvider", 2);
    }

    /** Bodies of every upstream request, so the token ceiling can be asserted. */
    private final List<String> requestBodies = new ArrayList<>();

    /** Matches any request, recording its body. The URLs are private to the provider. */
    private RequestMatcher anyRequest() {
        return request -> {
            if (request instanceof org.springframework.mock.http.client.MockClientHttpRequest mock) {
                requestBodies.add(mock.getBodyAsString());
            }
        };
    }

    private void expectServerError() {
        server.expect(anyRequest())
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
    }

    private void expectJsonSuccess(String body) {
        server.expect(anyRequest())
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /** Go speaks the Anthropic Messages shape, and only {@code type: "text"} counts. */
    private static String goResponse(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    /** Zen and Ollama speak the OpenAI chat-completions shape. */
    private static String openAiResponse(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    @Test
    void worstCaseUpstreamCallsAreBoundedByTheConfiguredBudget() {
        // Every candidate fails. The old code would have made 3 x 5 = 15 calls.
        // Exactly maxProviderCalls expectations: a fifth request would fail the test,
        // which is the point — the bound is enforced by the runner, not by luck.
        for (int i = 0; i < 4; i++) {
            expectServerError();
        }

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        ProviderCascadeExhaustedException ex = assertThrows(
                ProviderCascadeExhaustedException.class,
                () -> provider.chat("sys", "user", null, null, attempts::add));

        assertEquals(4, attempts.size(),
                "the cascade must stop at maxProviderCalls, got " + attempts.size() + " attempts");
        assertEquals(4, ex.getUpstreamCalls(),
                "the failure must report the upstream calls it actually made");
        assertTrue(attempts.stream().noneMatch(OpenCodeAiProvider.Attempt::accepted));
        server.verify();
    }

    @Test
    void theBudgetIsRespectedEvenWhenRaised() {
        ReflectionTestUtils.setField(provider, "maxProviderCalls", 2);
        for (int i = 0; i < 10; i++) {
            expectServerError();
        }

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        assertThrows(ProviderCascadeExhaustedException.class,
                () -> provider.chat("sys", "user", null, null, attempts::add));

        assertEquals(2, attempts.size());
    }

    @Test
    void aProviderThatKeepsFailingIsAbandonedRatherThanRetriedForever() {
        // One candidate only. Without a per-provider cap the outer pass would keep
        // retrying it until the global budget ran out, spending every call on a
        // provider already known to be broken.
        ReflectionTestUtils.setField(provider, "zenApiKey", null);
        ReflectionTestUtils.setField(provider, "geminiApiKey", null);
        ReflectionTestUtils.setField(provider, "ollamaApiKey", null);
        for (int i = 0; i < 10; i++) {
            expectServerError();
        }

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        assertThrows(ProviderCascadeExhaustedException.class,
                () -> provider.chat("sys", "user", null, null, attempts::add));

        assertEquals(2, attempts.size(),
                "a single provider must be capped at maxAttemptsPerProvider, got " + attempts.size());
        assertTrue(attempts.stream().allMatch(a -> a.provider().startsWith("Go")));
    }

    @Test
    void theCascadeStopsAtTheFirstAcceptableResponse() {
        expectJsonSuccess(goResponse("{\\\"ok\\\":true}"));

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        String result = provider.chat("sys", "user", null, null, attempts::add);

        assertEquals("{\"ok\":true}", result);
        assertEquals(1, attempts.size(), "one upstream call, one attempt");
        assertTrue(attempts.get(0).accepted());
        server.verify();
    }

    @Test
    void rejectedResponsesAreStillReportedAsAttemptsSoTheirCostIsVisible() {
        // Go returns something the validator rejects; Zen then answers. Both calls
        // happened and both were billed, so both must appear.
        expectJsonSuccess(goResponse("not json at all"));
        expectJsonSuccess(openAiResponse("{\\\"ok\\\":true}"));

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        String result = provider.chat("sys", "user", json -> "not json at all".equals(json) ? "unparseable" : null,
                null, attempts::add);

        assertEquals("{\"ok\":true}", result);
        assertEquals(2, attempts.size(), "the rejected Go response must still be counted");
        assertTrue(!attempts.get(0).accepted(), "first attempt was rejected");
        assertTrue(attempts.get(0).detail().contains("unparseable"), attempts.get(0).detail());
        assertTrue(attempts.get(0).totalTokens() > 0, "a rejected response still consumed tokens");
        assertTrue(attempts.get(1).accepted());
    }

    @Test
    void aGoUsageLimitStopsGoImmediatelyInsteadOfBurningMoreQuota() {
        expectServerError();
        expectJsonSuccess(openAiResponse("{\\\"ok\\\":true}"));

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        String result = provider.chat("sys", "user", null, null, attempts::add);

        assertEquals("{\"ok\":true}", result);
        assertEquals(2, attempts.size(), "Go once, then the next candidate — no Go retry");
        assertTrue(attempts.get(0).provider().startsWith("Go"));
        assertTrue(attempts.get(1).provider().startsWith("Zen"));
    }

    @Test
    void theOperationTokenCeilingReachesTheProvider() {
        expectJsonSuccess(goResponse("{\\\"ok\\\":true}"));

        provider.chat("sys", "user", null, 1234, attempt -> {
        });

        // The point of the change: 12000 used to be hardcoded for every operation, so a
        // one-line locator verdict requested as much as a generated test suite.
        server.verify();
        assertEquals(1, requestBodies.size());
        assertTrue(requestBodies.get(0).contains("\"max_tokens\":1234"),
                "the caller's ceiling must reach the provider request: " + requestBodies.get(0));
    }

    @Test
    void noCeilingFallsBackToTheDefaultRatherThanTheOldFlatMaximum() {
        expectJsonSuccess(goResponse("{\\\"ok\\\":true}"));

        provider.chat("sys", "user", null, null, attempt -> {
        });

        server.verify();
        assertTrue(requestBodies.get(0).contains("\"max_tokens\":4000"),
                "expected the reduced default, got: " + requestBodies.get(0));
    }

    @Test
    void theFailurePathNoLongerSleepsBetweenRetries() {
        // The old shape was a retry loop wrapped around the cascade, with a backoff
        // sleep of BASE_DELAY_MS * 2^(n-1) between outer attempts: 1s then 2s, three
        // seconds of pure sleeping before the operation even gave up, on top of up to
        // 15 round trips. With stubbed providers a full failure now costs a few
        // milliseconds, so a generous 1.5s bound still separates the two by 2x while
        // being nowhere near flaky.
        for (int i = 0; i < 4; i++) {
            expectServerError();
        }

        long start = System.currentTimeMillis();
        assertThrows(ProviderCascadeExhaustedException.class,
                () -> provider.chat("sys", "user", null, null, attempt -> {
                }));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 1_500,
                "the failure path must not sleep between retries; took " + elapsed + "ms");
        server.verify();
    }

    @Test
    void theConfiguredCascadeIsFullyReachableWithinTheDefaultBudget() {
        // The default budget must cover the candidates that are actually configured,
        // otherwise the tail of the cascade is dead configuration: Gemini and Ollama
        // would never be tried in the worst case, which is precisely when they matter.
        ReflectionTestUtils.setField(provider, "maxProviderCalls", 6);
        ReflectionTestUtils.setField(provider, "goModel", "qwen3.7-plus");
        ReflectionTestUtils.setField(provider, "zenModel", "space-bunny-free");
        ReflectionTestUtils.setField(provider, "zenFallbackModel", "big-pickle");
        ReflectionTestUtils.setField(provider, "geminiModel", "gemini-1.5-flash-latest");
        ReflectionTestUtils.setField(provider, "ollamaModel", "gpt-oss:20b");

        for (int i = 0; i < 6; i++) {
            expectServerError();
        }

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        assertThrows(ProviderCascadeExhaustedException.class,
                () -> provider.chat("sys", "user", null, null, attempts::add));

        List<String> labels = attempts.stream().map(OpenCodeAiProvider.Attempt::provider).toList();
        assertTrue(labels.containsAll(List.of(
                        "Go(qwen3.7-plus)", "Zen(space-bunny-free)", "Zen(big-pickle)",
                        "Gemini(gemini-1.5-flash-latest)", "Ollama(gpt-oss:20b)")),
                "every configured candidate must be reachable within the default budget, got " + labels);
    }

    @Test
    void spaceBunnyFreeIsTriedBeforeBigPickle() {
        // The requested order, scoped to the Zen pair. Go still leads the cascade as a
        // whole, which is a separate concern from which Zen model goes first.
        ReflectionTestUtils.setField(provider, "goApiKey", null);
        ReflectionTestUtils.setField(provider, "zenModel", "space-bunny-free");
        ReflectionTestUtils.setField(provider, "zenFallbackModel", "big-pickle");

        expectServerError();
        expectJsonSuccess(openAiResponse("{\\\"ok\\\":true}"));

        List<OpenCodeAiProvider.Attempt> attempts = new ArrayList<>();
        provider.chat("sys", "user", null, null, attempts::add);

        assertEquals(2, attempts.size());
        assertEquals("Zen(space-bunny-free)", attempts.get(0).provider(),
                "Space Bunny Free must be tried before Big Pickle");
        assertEquals("Zen(big-pickle)", attempts.get(1).provider());
    }
}
