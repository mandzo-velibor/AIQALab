package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.ai.provider.AiProvider;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.*;
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
    private final String explorerPrompt;

    public ExplorerService(BrowserTool browserTool,
                           DomSimplifier domSimplifier,
                           AiProvider aiProvider,
                           AnalysisCache cache,
                           ObjectMapper objectMapper) {
        this.browserTool = browserTool;
        this.domSimplifier = domSimplifier;
        this.aiProvider = aiProvider;
        this.cache = cache;
        this.objectMapper = objectMapper;
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

        cache.put(urlHash, analysis);
        log.info("Analysis finished for URL: {}", url);

        return analysis;
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
            return aiProvider.chat(explorerPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("First LLM call failed: {}. Retrying...", e.getMessage());
            try {
                return aiProvider.chat(explorerPrompt, userPrompt);
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
