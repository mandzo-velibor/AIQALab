package com.qalab.qalabai.agent.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.QaAgent;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.ai.provider.AiProvider;
import com.qalab.qalabai.ai.provider.JsonValidators;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.model.LocatorHistory;
import com.qalab.qalabai.repository.FailureAnalysisRepository;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.browser.BrowserTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@Component
public class SelfHealingAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingAgent.class);

    private final AiProvider aiProvider;
    private final BrowserTool browserTool;
    private final FailureAnalysisRepository failureAnalysisRepository;
    private final LocatorHistoryRepository locatorHistoryRepository;
    private final HealingSuggestionRepository healingSuggestionRepository;
    private final ObjectMapper objectMapper;
    private final String healingPrompt;

    public SelfHealingAgent(AiProvider aiProvider,
                            BrowserTool browserTool,
                            FailureAnalysisRepository failureAnalysisRepository,
                            LocatorHistoryRepository locatorHistoryRepository,
                            HealingSuggestionRepository healingSuggestionRepository,
                            ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.browserTool = browserTool;
        this.failureAnalysisRepository = failureAnalysisRepository;
        this.locatorHistoryRepository = locatorHistoryRepository;
        this.healingSuggestionRepository = healingSuggestionRepository;
        this.objectMapper = objectMapper;
        this.healingPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/self-healing-agent.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load self-healing prompt: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public String getName() {
        return "SelfHealing";
    }

    @Override
    public AgentResult execute(Task task) {
        log.info("Self-Healing Agent started");

        Long failureAnalysisId = (Long) task.getContextValue("failureAnalysisId");
        Long projectId = (Long) task.getContextValue("projectId");
        String baseUrl = (String) task.getContextValue("baseUrl");

        if (failureAnalysisId == null || projectId == null) {
            return AgentResult.failure(getName(), "Missing failureAnalysisId or projectId in task context");
        }

        try {
            FailureAnalysis analysis = failureAnalysisRepository.findById(failureAnalysisId)
                    .orElseThrow(() -> new RuntimeException("Failure analysis not found: " + failureAnalysisId));

            if (!Boolean.TRUE.equals(analysis.getHealingCandidate())) {
                return AgentResult.failure(getName(), "This failure is not a healing candidate");
            }

            String elementName = analysis.getAffectedElement();
            Optional<LocatorHistory> locatorOpt = locatorHistoryRepository
                    .findByProjectIdAndElementNameAndStatus(projectId, elementName, "ACTIVE");

            if (locatorOpt.isEmpty()) {
                return AgentResult.failure(getName(), "No active locator found for element: " + elementName);
            }

            LocatorHistory oldLocator = locatorOpt.get();
            log.info("Found old locator: {}", oldLocator.getLocator());

            String currentDom = "";
            if (baseUrl != null && !baseUrl.isBlank()) {
                log.info("Inspecting current page: {}", baseUrl);
                ToolContext context = new ToolContext();
                context.put("url", baseUrl);
                Object result = browserTool.execute(context);
                if (result instanceof Map<?, ?> map) {
                    currentDom = (String) map.get("html");
                }
            }

            String userPrompt = buildUserPrompt(oldLocator, analysis, currentDom);
            log.info("Sending healing request to AI");

            String aiResponse = aiProvider.chat(healingPrompt, userPrompt, JsonValidators.isJsonObject());
            log.info("AI response received for healing suggestion");

            HealingSuggestion suggestion = parseResponse(aiResponse, projectId, analysis.getExecutionId(), failureAnalysisId, oldLocator);
            HealingSuggestion saved = healingSuggestionRepository.save(suggestion);

            log.info("Healing suggestion saved with id: {}", saved.getId());

            AgentResult result = AgentResult.success(getName(), "Healing suggestion generated");
            result.putData("suggestionId", saved.getId());
            result.putData("newLocator", saved.getNewLocator());
            result.putData("confidence", saved.getConfidence());
            return result;

        } catch (Exception e) {
            log.error("Self-Healing Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(LocatorHistory oldLocator, FailureAnalysis analysis, String currentDom) {
        return String.format("""
                Old Locator: %s
                Element Name: %s
                Failure Summary: %s
                
                Current Page DOM (simplified):
                %s
                
                Analyze the broken locator and suggest the most probable replacement.
                """,
                oldLocator.getLocator(),
                oldLocator.getElementName(),
                analysis.getSummary(),
                currentDom.length() > 5000 ? currentDom.substring(0, 5000) + "..." : currentDom);
    }

    private HealingSuggestion parseResponse(String aiResponse, Long projectId, Long executionId,
                                            Long failureAnalysisId, LocatorHistory oldLocator) throws Exception {
        String json = extractJson(aiResponse);
        JsonNode root = objectMapper.readTree(json);

        HealingSuggestion suggestion = new HealingSuggestion();
        suggestion.setProjectId(projectId);
        suggestion.setExecutionId(executionId);
        suggestion.setFailureAnalysisId(failureAnalysisId);
        suggestion.setElementName(root.path("elementName").asText(oldLocator.getElementName()));
        suggestion.setOldLocator(oldLocator.getLocator());
        suggestion.setNewLocator(root.path("newLocator").asText(""));
        suggestion.setConfidence(root.path("confidence").asInt(50));
        suggestion.setReason(root.path("reason").asText(""));
        suggestion.setStatus("PENDING");

        return suggestion;
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
}
