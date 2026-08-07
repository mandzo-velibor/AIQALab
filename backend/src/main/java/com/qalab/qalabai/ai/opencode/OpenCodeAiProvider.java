package com.qalab.qalabai.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qalab.qalabai.ai.provider.AiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

@Component
@Primary
public class OpenCodeAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeAiProvider.class);

    private static final String GO_API_URL = "https://opencode.ai/zen/go/v1/messages";
    private static final String ZEN_API_URL = "https://opencode.ai/zen/v1/chat/completions";

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 8000;

    @Value("${opencode.go.api-key:}")
    private String goApiKey;

    @Value("${opencode.zen.api-key:}")
    private String zenApiKey;

    @Value("${opencode.go.model:qwen3.7-plus}")
    private String goModel;

    @Value("${opencode.zen.model:big-pickle}")
    private String zenModel;

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        restTemplate = new RestTemplate();
        objectMapper = new ObjectMapper();

        if (goApiKey != null && !goApiKey.isBlank()) {
            log.info("OpenCode Go configured as PRIMARY with model: {}", goModel);
        } else {
            log.warn("OpenCode Go API key not configured.");
        }

        if (zenApiKey != null && !zenApiKey.isBlank()) {
            log.info("OpenCode Zen configured as FALLBACK with model: {}", zenModel);
        } else {
            log.warn("OpenCode Zen API key not configured.");
        }
    }

    @Override
    public String getName() {
        return "OpenCode";
    }

    @Override
    public String chat(String prompt) {
        return chat(null, prompt);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if ((goApiKey == null || goApiKey.isBlank()) && (zenApiKey == null || zenApiKey.isBlank())) {
            throw new IllegalStateException("No OpenCode API key configured. Set OPENCODE_GO_API_KEY or OPENCODE_ZEN_API_KEY.");
        }

        Exception lastError = null;
        boolean goExhausted = false;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String result = attemptProviders(systemPrompt, userPrompt, goExhausted);
                if (result != null && !result.isBlank()) {
                    return result;
                }
                lastError = new RuntimeException("Empty response from OpenCode providers");
                log.warn("OpenCode providers returned empty response (attempt {}/{})", attempt, MAX_ATTEMPTS);
            } catch (GoUsageLimitException e) {
                goExhausted = true;
                lastError = e;
                log.warn("OpenCode Go usage limit reached: {}. Skipping Go on further attempts.", e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                sleep(backoff(attempt));
                continue;
            } catch (Exception e) {
                lastError = e;
                log.warn("OpenCode attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }

            if (attempt < MAX_ATTEMPTS) {
                sleep(backoff(attempt));
            }
        }

        throw new RuntimeException("All OpenCode providers failed after " + MAX_ATTEMPTS + " attempts", lastError);
    }

    private String attemptProviders(String systemPrompt, String userPrompt, boolean goExhausted) throws Exception {
        if (!goExhausted && goApiKey != null && !goApiKey.isBlank()) {
            try {
                log.info("Attempting OpenCode Go with model: {}", goModel);
                String goResult = callGoApi(systemPrompt, userPrompt);
                if (goResult != null && !goResult.isBlank()) {
                    return goResult;
                }
                log.warn("OpenCode Go returned empty response; trying Zen fallback...");
            } catch (GoUsageLimitException e) {
                throw e;
            } catch (Exception e) {
                log.warn("OpenCode Go failed: {}. Trying Zen fallback...", e.getMessage());
            }
        }

        if (zenApiKey != null && !zenApiKey.isBlank()) {
            log.info("Attempting OpenCode Zen fallback with model: {}", zenModel);
            return callZenApi(systemPrompt, userPrompt);
        }

        throw new RuntimeException("No OpenCode API key configured. Set OPENCODE_GO_API_KEY or OPENCODE_ZEN_API_KEY.");
    }

    private String callGoApi(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", goModel);
        requestBody.put("max_tokens", 4000);

        ArrayNode messages = objectMapper.createArrayNode();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }

        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        messages.add(userMessage);

        requestBody.set("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", goApiKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    GO_API_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String content = extractAnthropicContent(responseJson);

            log.info("OpenCode Go response received, length: {} chars", content.length());
            return content;
        } catch (HttpStatusCodeException e) {
            if (isUsageLimit(e)) {
                throw new GoUsageLimitException(e.getResponseBodyAsString());
            }
            throw e;
        }
    }

    private String callZenApi(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", zenModel);
        requestBody.put("max_tokens", 4000);

        ArrayNode messages = objectMapper.createArrayNode();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }

        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        messages.add(userMessage);

        requestBody.set("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(zenApiKey);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                ZEN_API_URL,
                HttpMethod.POST,
                entity,
                String.class
        );

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        String content = extractOpenAiContent(responseJson);

        log.info("OpenCode Zen response received, length: {} chars", content.length());
        return content;
    }

    private String extractAnthropicContent(JsonNode responseJson) {
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

    private String extractOpenAiContent(JsonNode responseJson) {
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

    private boolean isUsageLimit(HttpStatusCodeException e) {
        if (e.getStatusCode().value() != 429) {
            return false;
        }
        String body = e.getResponseBodyAsString();
        String lower = body == null ? "" : body.toLowerCase();
        return lower.contains("usage");
    }

    private long backoff(int attempt) {
        return Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1L << (attempt - 1)));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class GoUsageLimitException extends RuntimeException {
        GoUsageLimitException(String message) {
            super(message);
        }
    }
}
