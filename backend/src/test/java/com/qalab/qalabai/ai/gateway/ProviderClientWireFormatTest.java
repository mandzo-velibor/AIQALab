package com.qalab.qalabai.ai.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The provider clients are where B-015's risk lives: they are the only place a BYOK key
 * meets the wire, and their two wire formats are genuinely different. These tests stub at
 * the transport level rather than mocking the client, so what is asserted is the bytes that
 * would actually go out — a mocked client would happily pass while the header was wrong.
 */
class ProviderClientWireFormatTest {

    private final RestTemplate restTemplate = new RestTemplate();

    private MockRestServiceServer server() {
        return MockRestServiceServer.bindTo(restTemplate).build();
    }

    private static ProviderCallRequest request(String baseUrl, String key) {
        return new ProviderCallRequest("you are a tester", "find the login form",
                "some-model", key, baseUrl, 512, null);
    }

    // ---------- OpenAI-compatible ----------

    @Test
    void openAiClientSendsBearerAuthAndTheExpectedRequestShape() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.model").value("some-model"))
                .andExpect(jsonPath("$.max_tokens").value(512))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("find the login form"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"here it is"}}],
                         "usage":{"prompt_tokens":120,"completion_tokens":45}}
                        """, MediaType.APPLICATION_JSON));

        var client = new OpenAiCompatProviderClient(AiProviderType.OPENAI, restTemplate);
        ProviderCallResult result = client.call(request("https://api.openai.com/v1", "secret-key"));

        assertThat(result.getContent()).isEqualTo("here it is");
        assertThat(result.getInputTokens()).isEqualTo(120);
        assertThat(result.getOutputTokens()).isEqualTo(45);
        assertThat(result.isEstimated())
                .as("a provider that reports usage must not be marked estimated, or budgets "
                        + "are computed from a guess while real numbers sit in the response")
                .isFalse();
        server.verify();
    }

    @Test
    void openAiClientAppendsTheChatPathToABaseUrlWithATrailingSlash() {
        MockRestServiceServer server = server();
        // A base URL configured with a trailing slash must not produce "//chat/completions",
        // which several gateways answer with a 404 that looks like a bad model name.
        server.expect(requestTo("https://host.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        MediaType.APPLICATION_JSON));

        new OpenAiCompatProviderClient(AiProviderType.OPENAI, restTemplate)
                .call(request("https://host.test/v1/", "k"));
        server.verify();
    }

    @Test
    void openAiClientOmitsTheAuthorizationHeaderWhenNoKeyIsSupplied() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://host.test/v1/chat/completions"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        MediaType.APPLICATION_JSON));

        // A local Ollama needs no key; sending "Bearer null" would be rejected.
        new OpenAiCompatProviderClient(AiProviderType.OLLAMA, restTemplate)
                .call(request("https://host.test/v1", null));
        server.verify();
    }

    @Test
    void openAiClientReportsMissingUsageAsEstimated() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://host.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        MediaType.APPLICATION_JSON));

        ProviderCallResult result = new OpenAiCompatProviderClient(AiProviderType.OPENAI, restTemplate)
                .call(request("https://host.test/v1", "k"));

        assertThat(result.isEstimated())
                .as("without usage figures the gateway must know the numbers are a guess")
                .isTrue();
    }

    @Test
    void openAiClientJoinsTextPartsWhenContentIsAnArray() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://host.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":[
                          {"type":"text","text":"part one "},
                          {"type":"text","text":"part two"},
                          {"type":"image_url","image_url":{"url":"x"}}]}}]}
                        """, MediaType.APPLICATION_JSON));

        // Reasoning models stream content as parts; returning "" for them would look like
        // an empty response and trigger the cascade.
        assertThat(new OpenAiCompatProviderClient(AiProviderType.OPENAI, restTemplate)
                .call(request("https://host.test/v1", "k")).getContent())
                .isEqualTo("part one part two");
    }

    @Test
    void openAiClientTurnsAnHttpErrorIntoATypedExceptionCarryingStatusAndBody() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://host.test/v1/chat/completions"))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"bad key\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        // The gateway classifies on status and body (invalid credential vs rate limit vs
        // provider down), so losing either here would misreport the failure upstream.
        assertThatThrownBy(() -> new OpenAiCompatProviderClient(AiProviderType.OPENAI, restTemplate)
                .call(request("https://host.test/v1", "k")))
                .isInstanceOf(OpenAiCompatProviderClient.ProviderHttpException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("bad key")
                .satisfies(e -> {
                    var typed = (OpenAiCompatProviderClient.ProviderHttpException) e;
                    assertThat(typed.getStatusCode()).isEqualTo(500);
                    assertThat(typed.getResponseBody()).contains("bad key");
                });
    }

    @Test
    void openAiClientFailsClearlyWhenNoBaseUrlIsConfigured() {
        // Previously this produced a confusing downstream error; a misconfiguration should
        // say what is missing.
        assertThatThrownBy(() -> new OpenAiCompatProviderClient(AiProviderType.OPENAI, restTemplate)
                .call(request("  ", "k")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no base URL");
    }

    // ---------- Anthropic-compatible ----------

    @Test
    void anthropicClientSendsApiKeyAndVersionHeadersInsteadOfBearer() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "secret-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.model").value("some-model"))
                .andExpect(jsonPath("$.max_tokens").value(512))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"here it is"}],
                         "usage":{"input_tokens":90,"output_tokens":30}}
                        """, MediaType.APPLICATION_JSON));

        ProviderCallResult result =
                new AnthropicCompatProviderClient(restTemplate)
                        .call(request("https://api.anthropic.com/v1", "secret-key"));

        assertThat(result.getContent()).isEqualTo("here it is");
        assertThat(result.getInputTokens())
                .as("Anthropic reports input_tokens, not prompt_tokens")
                .isEqualTo(90);
        assertThat(result.getOutputTokens()).isEqualTo(30);
        server.verify();
    }

    @Test
    void anthropicClientSkipsTheSystemMessageWhenThePromptIsBlank() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}",
                        MediaType.APPLICATION_JSON));

        new AnthropicCompatProviderClient(restTemplate).call(
                new ProviderCallRequest("   ", "just the user prompt", "m", "k",
                        "https://api.anthropic.com/v1", 256, null));
        server.verify();
    }

    @Test
    void anthropicClientDefaultsTheUrlWhenNoneIsConfigured() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}",
                        MediaType.APPLICATION_JSON));

        new AnthropicCompatProviderClient(restTemplate)
                .call(request(null, "k"));
        server.verify();
    }

    @Test
    void anthropicClientTurnsAnHttpErrorIntoATypedException() {
        MockRestServiceServer server = server();
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withServerError().body("overloaded").contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> new AnthropicCompatProviderClient(restTemplate)
                .call(request("https://api.anthropic.com/v1", "k")))
                // Note the type: the Anthropic client throws the *OpenAI* client's nested
                // exception, so the two wire formats share one error type. Asserted as it
                // actually is, rather than as the class name suggests.
                .isInstanceOf(OpenAiCompatProviderClient.ProviderHttpException.class)
                .hasMessageContaining("overloaded");
    }
}
