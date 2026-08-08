package com.qalab.qalabai.ai.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Generic OpenAI-compatible chat client. Used for providers that expose an
 * OpenAI-style {@code /chat/completions} endpoint (OPENAI, GOOGLE via its
 * OpenAI-compatible gateway, OLLAMA). The API key and endpoint are supplied by
 * the gateway — never read from server config here.
 */
public class OpenAiCompatProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProviderClient.class);

    private final AiProviderType type;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public OpenAiCompatProviderClient(AiProviderType type) {
        this.type = type;
    }

    @Override
    public AiProviderType type() {
        return type;
    }

    @Override
    public ProviderCallResult call(ProviderCallRequest request) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", request.getModel());
        requestBody.put("max_tokens", request.getMaxOutputTokens() != null
                ? request.getMaxOutputTokens() : 4000);

        ArrayNode messages = objectMapper.createArrayNode();

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", request.getSystemPrompt());
            messages.add(systemMessage);
        }

        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", request.getUserPrompt());
        messages.add(userMessage);

        requestBody.set("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            headers.setBearerAuth(request.getApiKey());
        }

        String url = buildUrl(request.getBaseUrl());
        HttpEntity<String> entity;
        try {
            entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
        } catch (Exception e) {
            throw new RuntimeException(type + ": failed to serialize request: " + e.getMessage(), e);
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String content = extractContent(responseJson);
            TokenUsage usage = extractUsage(responseJson);
            log.info("{} ({}) response received, length: {} chars", type, request.getModel(), content.length());
            return new ProviderCallResult(content, usage.input, usage.output, usage.estimated, request.getModel());
        } catch (HttpStatusCodeException e) {
            throw new ProviderHttpException(type, e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(type + ": API call failed: " + e.getMessage(), e);
        }
    }

    private String buildUrl(String baseUrl) {
        String base = baseUrl;
        if (base == null || base.isBlank()) {
            throw new RuntimeException(type + ": no base URL configured for this provider");
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    private String extractContent(JsonNode responseJson) {
        JsonNode choices = responseJson.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText(""))) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private TokenUsage extractUsage(JsonNode responseJson) {
        JsonNode usage = responseJson.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return new TokenUsage(0, 0, true);
        }
        int input = usage.path("prompt_tokens").asInt(-1);
        int output = usage.path("completion_tokens").asInt(-1);
        if (input < 0 || output < 0) {
            return new TokenUsage(0, 0, true);
        }
        return new TokenUsage(input, output, false);
    }

    private record TokenUsage(int input, int output, boolean estimated) {
    }

    /** Wraps HTTP status + provider body for the gateway to classify (invalid key, rate limited, ...). */
    public static class ProviderHttpException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        public ProviderHttpException(AiProviderType provider, int statusCode, String responseBody) {
            super(provider + ": HTTP " + statusCode + ": " + (responseBody == null ? "" : responseBody));
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
