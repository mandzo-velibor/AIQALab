package com.qalab.qalabai.ai.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-023 replaced four near-identical HTTP methods with one. The value of that is that the
 * request shape can no longer drift per provider, so these tests assert the shared shape
 * directly and pin the two places providers genuinely differ: the auth header, and where
 * the reply text sits in the JSON.
 *
 * <p>The test lives in the same package so it can build real {@code ManagedEndpoint} values
 * and call the real method. The alternative — driving a private method reflectively with
 * dynamic proxies — would have been harder to read than the duplication being removed.
 */
class OpenCodeProviderRequestShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String OPENAI_REPLY = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
    private static final String ANTHROPIC_REPLY = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";

    private record Sent(String body, org.springframework.http.HttpHeaders headers) {
    }

    private OpenCodeAiProvider providerReturning(RestTemplate restTemplate, String reply) {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<String>(reply, HttpStatus.OK));
        return newProvider(restTemplate);
    }

    private Sent sent(OpenCodeAiProvider provider, OpenCodeAiProvider.ManagedEndpoint endpoint,
                      String systemPrompt, String userPrompt, RestTemplate restTemplate)
            throws Exception {
        provider.callChatApi(endpoint, systemPrompt, userPrompt, 512);
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<HttpEntity<String>> captor =
                org.mockito.ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST), captor.capture(),
                eq(String.class));
        return new Sent(captor.getValue().getBody(), captor.getValue().getHeaders());
    }

    private OpenCodeAiProvider newProvider(RestTemplate restTemplate) {
        for (var ctor : OpenCodeAiProvider.class.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == RestTemplate.class) {
                ctor.setAccessible(true);
                try {
                    return (OpenCodeAiProvider) ctor.newInstance(restTemplate);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("no RestTemplate constructor found");
    }

    private static OpenCodeAiProvider.AuthStrategy bearer(String key) {
        return h -> h.setBearerAuth(key);
    }

    private static OpenCodeAiProvider.ManagedEndpoint openAiEndpoint(String family, String model) {
        return new OpenCodeAiProvider.ManagedEndpoint(
                family + "(" + model + ")", family, "https://example.test/chat", model,
                bearer("key-" + family), OpenCodeAiProvider.OPENAI_REPLY);
    }

    @Test
    void everyProviderSendsTheSameRequestShape() throws Exception {
        // The point of the consolidation: one body builder, so a field cannot be added for
        // one provider and quietly forgotten for the others.
        RestTemplate restTemplate = mock(RestTemplate.class);
        OpenCodeAiProvider provider = providerReturning(restTemplate, OPENAI_REPLY);

        Sent sent = sent(provider, openAiEndpoint("zen", "big-pickle"),
                "you are a tester", "find the login form", restTemplate);

        JsonNode root = mapper.readTree(sent.body());
        assertThat(root.path("model").asText()).isEqualTo("big-pickle");
        assertThat(root.path("max_tokens").asInt()).isEqualTo(512);
        assertThat(root.path("messages")).hasSize(2);
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(root.path("messages").get(0).path("content").asText())
                .isEqualTo("you are a tester");
        assertThat(root.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(sent.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void aBlankSystemPromptOmitsTheSystemMessageRatherThanSendingAnEmptyOne() throws Exception {
        // Providers disagree about an empty system message: some reject it, some read it as
        // a real instruction. Omitting it is the only portable behaviour.
        RestTemplate restTemplate = mock(RestTemplate.class);
        OpenCodeAiProvider provider = providerReturning(restTemplate, OPENAI_REPLY);

        Sent sent = sent(provider, openAiEndpoint("zen", "m"), "   ", "just the user prompt",
                restTemplate);

        JsonNode root = mapper.readTree(sent.body());
        assertThat(root.path("messages")).hasSize(1);
        assertThat(root.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void anthropicStyleProviderSendsApiKeyAndVersionInsteadOfABearerToken() throws Exception {
        // Go is the one managed provider that is not bearer-authenticated; getting this
        // wrong produces a 401 that looks exactly like an exhausted key.
        RestTemplate restTemplate = mock(RestTemplate.class);
        OpenCodeAiProvider provider = providerReturning(restTemplate, ANTHROPIC_REPLY);
        OpenCodeAiProvider.ManagedEndpoint go = new OpenCodeAiProvider.ManagedEndpoint(
                "Go(qwen)", "go", "https://example.test/messages", "qwen",
                h -> {
                    h.set("x-api-key", "go-key");
                    h.set("anthropic-version", "2023-06-01");
                },
                OpenCodeAiProvider.ANTHROPIC_REPLY);

        Sent sent = sent(provider, go, "sys", "user", restTemplate);

        assertThat(sent.headers().getFirst("x-api-key")).isEqualTo("go-key");
        assertThat(sent.headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(sent.headers().getFirst("Authorization"))
                .as("an Anthropic-style endpoint must not also carry a bearer token")
                .isNull();
    }

    @Test
    void bothReplyShapesAreReadByTheSharedPath() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        OpenCodeAiProvider provider = providerReturning(restTemplate, ANTHROPIC_REPLY);
        OpenCodeAiProvider.ManagedEndpoint go = new OpenCodeAiProvider.ManagedEndpoint(
                "Go(qwen)", "go", "https://example.test/messages", "qwen",
                bearer("k"), OpenCodeAiProvider.ANTHROPIC_REPLY);

        // One HTTP method, two response shapes: the extractor is what differs, so the same
        // call must return text from either reply format.
        assertThat(provider.callChatApi(go, "sys", "user", 64))
                .as("Anthropic content[].text")
                .isEqualTo("ok");

        RestTemplate rt2 = mock(RestTemplate.class);
        OpenCodeAiProvider p2 = providerReturning(rt2, OPENAI_REPLY);
        assertThat(p2.callChatApi(openAiEndpoint("zen", "m"), "sys", "user", 64))
                .as("OpenAI choices[].message.content")
                .isEqualTo("ok");
    }

    @Test
    void aUsageLimitBecomesATypedErrorNamingTheProviderAndFamily() {
        // The cascade skips a spent provider for a reason, so the error must say which
        // provider is spent. A generic "usage limit" leaves an operator guessing, and the
        // family is what stops a Zen limit being recorded as "Go is out".
        var error = new OpenCodeAiProvider.UsageLimitException("Zen(big-pickle)", "zen",
                "quota exhausted");
        assertThat(error).hasMessageContaining("Zen(big-pickle)");
        assertThat(error.family()).isEqualTo("zen");
    }

    @Test
    void aUsageLimitOnAnyProviderIsTranslatedByTheSharedPath() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        // A 429 whose body mentions usage: the shape the cascade must recognise as a spent
        // quota rather than a transient fault.
        HttpStatusCodeException tooManyRequests = new org.springframework.web.client.HttpClientErrorException(
                HttpStatus.TOO_MANY_REQUESTS, "429",
                new org.springframework.http.HttpHeaders(),
                "{\"error\":\"usage limit reached for this key\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(tooManyRequests);
        OpenCodeAiProvider provider = newProvider(restTemplate);

        assertThatThrownBy(() ->
                provider.callChatApi(openAiEndpoint("gemini", "flash"), "sys", "user", 64))
                .isInstanceOf(OpenCodeAiProvider.UsageLimitException.class)
                .hasMessageContaining("usage limit");
    }

    @Test
    void aNonUsageHttpErrorIsNotMisreportedAsAQuotaProblem() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpStatusCodeException serverError = new org.springframework.web.client.HttpServerErrorException(
                HttpStatus.INTERNAL_SERVER_ERROR, "500",
                new org.springframework.http.HttpHeaders(),
                "upstream boom".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(serverError);
        OpenCodeAiProvider provider = newProvider(restTemplate);

        // Reporting a 500 as an exhausted quota would skip the provider permanently instead
        // of treating it as a transient fault the breaker should see.
        assertThatThrownBy(() ->
                provider.callChatApi(openAiEndpoint("zen", "m"), "sys", "user", 64))
                .isNotInstanceOf(OpenCodeAiProvider.UsageLimitException.class);
    }
}
