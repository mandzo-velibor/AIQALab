package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.ai.provider.AiProvider;
import com.qalab.qalabai.ai.provider.JsonValidators;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.*;
import com.qalab.qalabai.model.PageAnalysisHistory;
import com.qalab.qalabai.repository.PageAnalysisHistoryRepository;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.browser.BrowserTool;
import com.qalab.qalabai.tool.browser.DomSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ExplorerService {

    private static final Logger log = LoggerFactory.getLogger(ExplorerService.class);

    private final BrowserTool browserTool;
    private final DomSimplifier domSimplifier;
    private final AiProvider aiProvider;
    private final AnalysisCache cache;
    private final ObjectMapper objectMapper;
    private final PageAnalysisHistoryRepository historyRepository;
    private final String explorerPrompt;

    public ExplorerService(BrowserTool browserTool,
                           DomSimplifier domSimplifier,
                           AiProvider aiProvider,
                           AnalysisCache cache,
                           ObjectMapper objectMapper,
                           PageAnalysisHistoryRepository historyRepository) {
        this.browserTool = browserTool;
        this.domSimplifier = domSimplifier;
        this.aiProvider = aiProvider;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.historyRepository = historyRepository;
        this.explorerPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/explorer.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load explorer prompt: {}", e.getMessage());
            return "";
        }
    }

    public AnalysisResponse analyze(String url, boolean forceRefresh) {
        return analyze(url, forceRefresh, null, null, null);
    }

    public AnalysisResponse analyze(String url, boolean forceRefresh, Long projectId) {
        return analyze(url, forceRefresh, projectId, null, null);
    }

    public AnalysisResponse analyze(String url, boolean forceRefresh, Long projectId, String username, String password) {
        log.info("Explorer started for URL: {}", url);

        String urlHash = cache.hashUrl(url);

        if (!forceRefresh) {
            AnalysisResponse cached = cache.get(urlHash);
            if (cached != null) {
                log.info("Returning cached analysis for URL: {}", url);
                return cached;
            }
        }

        log.info("Browser opening page: {}", url);
        ToolContext context = new ToolContext().put("url", url);
        Object rawResult = browserTool.execute(context);

        if (!(rawResult instanceof Map<?, ?> map)) {
            throw new RuntimeException("Unexpected result from BrowserTool");
        }

        if (map.containsKey("error")) {
            throw new RuntimeException("Browser error: " + map.get("error"));
        }

        String title = (String) map.get("title");
        String currentUrl = (String) map.get("url");
        String html = (String) map.get("html");
        String accessibilityTree = (String) map.get("accessibilityTree");
        String screenshotBase64 = (String) map.get("screenshotBase64");

        log.info("Screenshot captured");
        log.info("Accessibility tree collected");

        String simplifiedHtml = domSimplifier.simplify(html);
        log.info("HTML simplified from {} to {} chars", html.length(), simplifiedHtml.length());

        String userPrompt = buildUserPrompt(title, currentUrl, simplifiedHtml, accessibilityTree);
        log.info("Prompt created, length: {} chars", userPrompt.length());

        String llmResponse = callLlmWithRetry(userPrompt);
        log.info("LLM response received");

        AnalysisResponse analysis = parseResponse(llmResponse, screenshotBase64);
        log.info("DTO parsed");

        cache.put(urlHash, analysis, simplifiedHtml);
        log.info("Analysis finished for URL: {}", url);

        String postLoginHtml = attemptLogin(currentUrl, username, password);
        if (postLoginHtml != null) {
            String postLoginSimplified = domSimplifier.simplify(postLoginHtml);
            cache.putPostLoginContent(urlHash, postLoginSimplified);
            cache.putLoginCredentials(urlHash, username, password);
            log.info("Post-login content cached, simplified to {} chars", postLoginSimplified.length());
        }

        saveHistory(url, analysis, projectId);

        return analysis;
    }

    private String attemptLogin(String url, String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            log.info("No credentials provided, skipping login attempt");
            return null;
        }

        log.info("Attempting login for URL: {} with user {}", url, username);
        Object loginResult = browserTool.login(url, username, password);
        if (!(loginResult instanceof Map<?, ?> map) || map.containsKey("error") || map.get("html") == null) {
            log.warn("Login attempt produced no post-login content");
            return null;
        }
        log.info("Login succeeded, post-login URL: {}", map.get("url"));
        return (String) map.get("html");
    }

    private void saveHistory(String url, AnalysisResponse analysis, Long projectId) {
        if (projectId == null) {
            return;
        }

        try {
            PageAnalysisHistory history = new PageAnalysisHistory();
            history.setProjectId(projectId);
            history.setUrl(url);
            history.setPageType(analysis.pageType());
            history.setAnalysisJson(objectMapper.writeValueAsString(analysis));
            history.setScreenshotReference(analysis.screenshotBase64() != null
                    ? "embedded" : null);
            int version = 1;
            var existing = historyRepository.findByProjectIdAndUrlOrderByVersionDesc(projectId, url);
            if (!existing.isEmpty()) {
                version = existing.get(0).getVersion() + 1;
            }
            history.setVersion(version);
            historyRepository.save(history);
            log.info("Saved page analysis history v{} for project {}, URL {}", version, projectId, url);
        } catch (Exception e) {
            log.warn("Failed to save page analysis history for project {}, URL {}: {}",
                    projectId, url, e.getMessage());
        }
    }

    private String buildUserPrompt(String title, String url, String html, String accessibilityTree) {
        return String.format("""
                Page Title: %s
                URL: %s

                Simplified HTML:
                %s

                Accessibility Tree:
                %s
                """, title, url, html, accessibilityTree);
    }

    private String callLlmWithRetry(String userPrompt) {
        try {
            log.info("LLM request sent");
            return aiProvider.chat(explorerPrompt, userPrompt, JsonValidators.isJsonObject());
        } catch (Exception e) {
            log.warn("First LLM call failed: {}. Retrying...", e.getMessage());
            try {
                return aiProvider.chat(explorerPrompt, userPrompt, JsonValidators.isJsonObject());
            } catch (Exception retryEx) {
                log.error("LLM retry failed: {}", retryEx.getMessage());
                throw new RuntimeException("LLM call failed after retry: " + retryEx.getMessage(), retryEx);
            }
        }
    }

    private AnalysisResponse parseResponse(String llmResponse, String screenshotBase64) {
        try {
            String json = extractJson(llmResponse);
            JsonNode root = objectMapper.readTree(json);

            String pageType = root.path("pageType").asText("");
            String summary = root.path("summary").asText("");
            int confidence = root.path("confidence").asInt(0);

            List<DetectedForm> forms = parseForms(root.path("forms"));
            List<String> buttons = parseStringList(root.path("buttons"));
            List<DetectedNavigation> navigation = parseNavigation(root.path("navigation"));
            List<DetectedDialog> dialogs = parseDialogs(root.path("dialogs"));
            List<DetectedTable> tables = parseTables(root.path("tables"));
            List<DetectedFlow> possibleFlows = parseFlows(root.path("possibleFlows"));
            List<RiskArea> riskAreas = parseRiskAreas(root.path("riskAreas"));

            return new AnalysisResponse(
                    pageType, summary, confidence,
                    forms, buttons, navigation, dialogs, tables,
                    possibleFlows, riskAreas, screenshotBase64
            );
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        }
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private List<DetectedForm> parseForms(JsonNode node) {
        List<DetectedForm> forms = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode formNode : node) {
                String name = formNode.path("name").asText("");
                List<String> inputs = parseStringList(formNode.path("inputs"));
                forms.add(new DetectedForm(name, inputs));
            }
        }
        return forms;
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }

    private List<DetectedNavigation> parseNavigation(JsonNode node) {
        List<DetectedNavigation> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(new DetectedNavigation(
                        item.path("name").asText(""),
                        item.path("target").asText("")
                ));
            }
        }
        return list;
    }

    private List<DetectedDialog> parseDialogs(JsonNode node) {
        List<DetectedDialog> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(new DetectedDialog(
                        item.path("name").asText(""),
                        item.path("trigger").asText("")
                ));
            }
        }
        return list;
    }

    private List<DetectedTable> parseTables(JsonNode node) {
        List<DetectedTable> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(new DetectedTable(
                        item.path("name").asText(""),
                        parseStringList(item.path("columns"))
                ));
            }
        }
        return list;
    }

    private List<DetectedFlow> parseFlows(JsonNode node) {
        List<DetectedFlow> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(new DetectedFlow(
                        item.path("name").asText(""),
                        item.path("description").asText("")
                ));
            }
        }
        return list;
    }

    private List<RiskArea> parseRiskAreas(JsonNode node) {
        List<RiskArea> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(new RiskArea(
                        item.path("name").asText(""),
                        item.path("reason").asText("")
                ));
            }
        }
        return list;
    }
}
