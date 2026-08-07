package com.qalab.qalabai.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qalab.qalabai.ai.provider.AiProvider;
import com.qalab.qalabai.ai.provider.ResponseValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;

@Component
@Primary
public class OpenCodeAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeAiProvider.class);

    private static final String GO_API_URL = "https://opencode.ai/zen/go/v1/messages";
    private static final String ZEN_API_URL = "https://opencode.ai/zen/v1/chat/completions";
    private static final String CHAT_PATH = "/chat/completions";

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_TOKENS = 8000;
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

    @Value("${opencode.zen.fallback-model:mimo-v2.5-free}")
    private String zenFallbackModel;

    @Value("${ollama.api-key:}")
    private String ollamaApiKey;

    @Value("${ollama.base-url:https://ollama.com/v1}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:gpt-oss:20b}")
    private String ollamaModel;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta/openai}")
    private String geminiBaseUrl;

    @Value("${gemini.model:gemini-1.5-flash}")
    private String geminiModel;

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
            log.info("OpenCode Zen configured as FALLBACK with model: {} then fallback model: {}", zenModel, zenFallbackModel);
        } else {
            log.warn("OpenCode Zen API key not configured.");
        }

        if (ollamaApiKey != null && !ollamaApiKey.isBlank()) {
            log.info("Ollama configured as FALLBACK with model: {} at {}", ollamaModel, ollamaBaseUrl);
        } else {
            log.warn("Ollama API key not configured.");
        }

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            log.info("Gemini configured as FALLBACK with model: {} at {}", geminiModel, geminiBaseUrl);
        } else {
            log.warn("Gemini API key not configured.");
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
        return chat(systemPrompt, userPrompt, null);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, ResponseValidator validator) {
        if ((goApiKey == null || goApiKey.isBlank())
                && (zenApiKey == null || zenApiKey.isBlank())
                && (ollamaApiKey == null || ollamaApiKey.isBlank())
                && (geminiApiKey == null || geminiApiKey.isBlank())) {
            throw new IllegalStateException("No AI provider API key configured. Set OPENCODE_GO_API_KEY, OPENCODE_ZEN_API_KEY, OLLAMA_API_KEY or GEMINI_API_KEY.");
        }

        log.info("AI request sent. Prompt size: system={} chars, user={} chars",
                systemPrompt == null ? 0 : systemPrompt.length(),
                userPrompt == null ? 0 : userPrompt.length());

        Exception lastError = null;
        boolean goExhausted = false;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String result = attemptProviders(systemPrompt, userPrompt, validator, goExhausted);
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

        throw new RuntimeException("All AI providers failed after " + MAX_ATTEMPTS + " attempts. No valid AI response was obtained for this request. Check the application log for per-provider details.", lastError);
    }

    private boolean accepts(String result, ResponseValidator validator) {
        if (result == null || result.isBlank()) {
            return false;
        }
        return validator == null || validator.isValid(result);
    }

    private String attemptProviders(String systemPrompt, String userPrompt, ResponseValidator validator, boolean goExhausted) throws Exception {
        List<String> failures = new ArrayList<>();

        if (!goExhausted && goApiKey != null && !goApiKey.isBlank()) {
            try {
                log.info("Attempting OpenCode Go with model: {}", goModel);
                String goResult = callGoApi(systemPrompt, userPrompt);
                if (accepts(goResult, validator)) {
                    return goResult;
                }
                log.warn("OpenCode Go ({}) response rejected ({} chars). Trying Zen fallback...", goModel, goResult.length());
            } catch (GoUsageLimitException e) {
                throw e;
            } catch (Exception e) {
                failures.add("Go(" + goModel + "): " + e.getMessage());
                log.warn("OpenCode Go failed: {}. Trying Zen fallback...", e.getMessage());
            }
        }

        if (zenApiKey != null && !zenApiKey.isBlank()) {
            try {
                String zenResult = callZenApi(systemPrompt, userPrompt, zenModel);
                if (accepts(zenResult, validator)) {
                    return zenResult;
                }
                log.warn("OpenCode Zen ({}) response rejected ({} chars). Trying Zen fallback model {}...", zenModel, zenResult.length(), zenFallbackModel);
            } catch (Exception e) {
                failures.add("Zen(" + zenModel + "): " + e.getMessage());
                log.warn("OpenCode Zen failed: {}. Trying Zen fallback model {}...", e.getMessage(), zenFallbackModel);
            }

            try {
                String zenFallbackResult = callZenApi(systemPrompt, userPrompt, zenFallbackModel);
                if (accepts(zenFallbackResult, validator)) {
                    return zenFallbackResult;
                }
                log.warn("OpenCode Zen ({}) response rejected ({} chars). Trying Gemini fallback...", zenFallbackModel, zenFallbackResult.length());
            } catch (Exception e) {
                failures.add("Zen(" + zenFallbackModel + "): " + e.getMessage());
                log.warn("OpenCode Zen fallback model failed: {}. Trying Gemini fallback...", e.getMessage());
            }
        }

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            log.info("Attempting Gemini fallback with model: {}", geminiModel);
            try {
                String geminiResult = callGeminiApi(systemPrompt, userPrompt);
                if (accepts(geminiResult, validator)) {
                    return geminiResult;
                }
                log.warn("Gemini ({}) response rejected ({} chars). Trying Ollama fallback...", geminiModel, geminiResult.length());
            } catch (Exception e) {
                failures.add("Gemini(" + geminiModel + "): " + e.getMessage());
                log.warn("Gemini failed: {}. Trying Ollama fallback...", e.getMessage());
            }
        }

        if (ollamaApiKey != null && !ollamaApiKey.isBlank()) {
            log.info("Attempting Ollama fallback with model: {}", ollamaModel);
            try {
                String ollamaResult = callOllamaApi(systemPrompt, userPrompt);
                if (accepts(ollamaResult, validator)) {
                    return ollamaResult;
                }
                log.warn("Ollama ({}) response rejected ({} chars).", ollamaModel, ollamaResult.length());
            } catch (Exception e) {
                failures.add("Ollama(" + ollamaModel + "): " + e.getMessage());
                log.warn("Ollama failed: {}", e.getMessage());
            }
        }

        String reason = failures.isEmpty()
                ? "No provider was configured or every provider returned an empty/invalid response."
                : "Provider errors: " + String.join(" | ", failures);
        log.error("No AI provider returned a usable response. {}", reason);
        throw new RuntimeException(reason);
    }

    private String callGoApi(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", goModel);
        requestBody.put("max_tokens", MAX_TOKENS);

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

    private String callZenApi(String systemPrompt, String userPrompt, String model) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", MAX_TOKENS);

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

        log.info("OpenCode Zen ({}) response received, length: {} chars", model, content.length());
        return content;
    }

    private String callOllamaApi(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", ollamaModel);
        requestBody.put("max_tokens", MAX_TOKENS);

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
        headers.setBearerAuth(ollamaApiKey);

        String url = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1) + CHAT_PATH
                : ollamaBaseUrl + CHAT_PATH;

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        String content = extractOpenAiContent(responseJson);

        log.info("Ollama response received, length: {} chars", content.length());
        return content;
    }

    private String callGeminiApi(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", geminiModel);
        requestBody.put("max_tokens", MAX_TOKENS);

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
        headers.setBearerAuth(geminiApiKey);

        String url = geminiBaseUrl.endsWith("/")
                ? geminiBaseUrl.substring(0, geminiBaseUrl.length() - 1) + CHAT_PATH
                : geminiBaseUrl + CHAT_PATH;

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        JsonNode responseJson = objectMapper.readTree(response.getBody());
        String content = extractOpenAiContent(responseJson);

        log.info("Gemini response received, length: {} chars", content.length());
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
