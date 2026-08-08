package com.qalab.qalabai.agent.healing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.ProjectContextUtil;
import com.qalab.qalabai.agent.QaAgent;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.ai.gateway.AgentExecutionContext;
import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.AiOperation;
import com.qalab.qalabai.ai.gateway.AiRequest;
import com.qalab.qalabai.ai.gateway.AiResponse;
import com.qalab.qalabai.ai.provider.JsonValidators;
import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.browser.BrowserTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class SelfHealingAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingAgent.class);

    private final AiGateway aiGateway;
    private final BrowserTool browserTool;
    private final ObjectMapper objectMapper;
    private final String healingPrompt;

    public SelfHealingAgent(AiGateway aiGateway,
                            BrowserTool browserTool,
                            ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
        this.browserTool = browserTool;
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
        Long executionId = (Long) task.getContextValue("executionId");
        Long projectId = (Long) task.getContextValue("projectId");
        String elementName = (String) task.getContextValue("elementName");
        String oldLocator = (String) task.getContextValue("oldLocator");
        String failureSummary = (String) task.getContextValue("failureSummary");
        String baseUrl = (String) task.getContextValue("baseUrl");

        if (failureAnalysisId == null || projectId == null || oldLocator == null) {
            return AgentResult.failure(getName(), "Missing failureAnalysisId, projectId or oldLocator in task context");
        }

        try {
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

            String userPrompt = buildUserPrompt(oldLocator, elementName, failureSummary, currentDom);
            log.info("Sending healing request to AI");

            AiRequest request = AiRequest.builder(AiOperation.SELF_HEALING, healingPrompt, userPrompt)
                    .validator(JsonValidators.isJsonObject())
                    .build();
            AgentExecutionContext ctx = AgentExecutionContext.builder()
                    .projectContext(ProjectContextUtil.fromTask(task))
                    .operationId("op-" + task.getId())
                    .build();
            AiResponse aiResponse = aiGateway.complete(request, ctx);
            log.info("AI response received for healing suggestion");

            HealingSuggestion suggestion = parseResponse(aiResponse.getContent(), projectId, executionId, failureAnalysisId, elementName, oldLocator);
            log.info("Healing suggestion parsed: newLocator={}, confidence={}", suggestion.getNewLocator(), suggestion.getConfidence());

            AgentResult result = AgentResult.success(getName(), "Healing suggestion generated");
            result.putData("suggestion", suggestion);
            result.putData("newLocator", suggestion.getNewLocator());
            result.putData("confidence", suggestion.getConfidence());
            return result;

        } catch (Exception e) {
            log.error("Self-Healing Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(String oldLocator, String elementName, String failureSummary, String currentDom) {
        return String.format("""
                Old Locator: %s
                Element Name: %s
                Failure Summary: %s
                
                Current Page DOM (simplified):
                %s
                
                Analyze the broken locator and suggest the most probable replacement.
                """,
                oldLocator,
                elementName != null ? elementName : "",
                failureSummary != null ? failureSummary : "",
                currentDom.length() > 5000 ? currentDom.substring(0, 5000) + "..." : currentDom);
    }

    private HealingSuggestion parseResponse(String aiResponse, Long projectId, Long executionId,
                                            Long failureAnalysisId, String elementName, String oldLocator) throws Exception {
        String json = extractJson(aiResponse);
        JsonNode root = objectMapper.readTree(json);

        HealingSuggestion suggestion = new HealingSuggestion();
        suggestion.setProjectId(projectId);
        suggestion.setExecutionId(executionId);
        suggestion.setFailureAnalysisId(failureAnalysisId);
        suggestion.setElementName(root.path("elementName").asText(elementName != null ? elementName : ""));
        suggestion.setOldLocator(oldLocator);
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
