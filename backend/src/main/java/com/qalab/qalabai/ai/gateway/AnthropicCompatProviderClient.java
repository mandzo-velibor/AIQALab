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
 * Anthropic Messages API client (ANTHROPIC provider). Same contract as the
 * OpenAI-compatible client — the gateway supplies key, model and endpoint.
 */
public class AnthropicCompatProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCompatProviderClient.class);
    private static final String DEFAULT_URL = "https://api.anthropic.com/v1/messages";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public AiProviderType type() {
        return AiProviderType.ANTHROPIC;
    }

    @Override
    public ProviderCallResult call(ProviderCallRequest request) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", request.getModel());
        requestBody.put("max_tokens", request.getMaxOutputTokens() != null
                ? request.getMaxOutputTokens() : 4096);

        ArrayNode messages = objectMapper.createArrayNode();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "user");
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
            headers.set("x-api-key", request.getApiKey());
            headers.set("anthropic-version", "2023-06-01");
        }

        HttpEntity<String> entity;
        try {
            entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
        } catch (Exception e) {
            throw new RuntimeException("ANTHROPIC: failed to serialize request: " + e.getMessage(), e);
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    DEFAULT_URL, HttpMethod.POST, entity, String.class);
            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String content = extractContent(responseJson);
            TokenUsage usage = extractUsage(responseJson);
            log.info("ANTHROPIC ({}) response received, length: {} chars", request.getModel(), content.length());
            return new ProviderCallResult(content, usage.input, usage.output, usage.estimated, request.getModel());
        } catch (HttpStatusCodeException e) {
            throw new OpenAiCompatProviderClient.ProviderHttpException(
                    AiProviderType.ANTHROPIC, e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException("ANTHROPIC: API call failed: " + e.getMessage(), e);
        }
    }

    private String extractContent(JsonNode responseJson) {
        StringBuilder content = new StringBuilder();
        JsonNode contentArray = responseJson.path("content");
        if (contentArray.isArray()) {
            for (JsonNode item : contentArray) {
                if ("text".equals(item.path("type").asText())) {
                    content.append(item.path("text").asText());
                }
            }
        }
        return content.toString();
    }

    private TokenUsage extractUsage(JsonNode responseJson) {
        JsonNode usage = responseJson.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return new TokenUsage(0, 0, true);
        }
        int input = usage.path("input_tokens").asInt(-1);
        int output = usage.path("output_tokens").asInt(-1);
        if (input < 0 || output < 0) {
            return new TokenUsage(0, 0, true);
        }
        return new TokenUsage(input, output, false);
    }

    private record TokenUsage(int input, int output, boolean estimated) {
    }
}
