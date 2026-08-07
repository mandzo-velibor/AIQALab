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
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

@Component
@Primary
public class OpenCodeAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeAiProvider.class);

    private static final String GO_API_URL = "https://opencode.ai/zen/go/v1/messages";
    private static final String ZEN_API_URL = "https://opencode.ai/zen/v1/chat/completions";

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

        try {
            if (goApiKey != null && !goApiKey.isBlank()) {
                log.info("Attempting OpenCode Go with model: {}", goModel);
                return callGoApi(systemPrompt, userPrompt);
            }
        } catch (Exception e) {
            log.warn("OpenCode Go failed: {}. Trying Zen fallback...", e.getMessage());
        }

        try {
            if (zenApiKey != null && !zenApiKey.isBlank()) {
                log.info("Attempting OpenCode Zen fallback with model: {}", zenModel);
                return callZenApi(systemPrompt, userPrompt);
            }
        } catch (Exception e) {
            log.error("OpenCode Zen also failed: {}", e.getMessage());
        }

        throw new RuntimeException("All OpenCode providers failed");
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

        ResponseEntity<String> response = restTemplate.exchange(
                GO_API_URL,
                HttpMethod.POST,
                entity,
                String.class
        );

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        JsonNode contentArray = responseJson.path("content");

        StringBuilder content = new StringBuilder();
        if (contentArray.isArray()) {
            for (JsonNode item : contentArray) {
                if ("text".equals(item.path("type").asText())) {
                    content.append(item.path("text").asText());
                }
            }
        }

        log.info("OpenCode Go response received, length: {} chars", content.length());
        return content.toString();
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
        String content = responseJson.path("choices").get(0).path("message").path("content").asText();

        log.info("OpenCode Zen response received, length: {} chars", content.length());
        return content;
    }
}
