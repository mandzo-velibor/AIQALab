package com.qalab.qalabai.agent.locator;

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
import com.qalab.qalabai.model.LocatorDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class LocatorAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(LocatorAgent.class);

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final String locatorPrompt;

    public LocatorAgent(AiGateway aiGateway,
                        ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.locatorPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/locator-agent.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load locator prompt: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public String getName() {
        return "Locator";
    }

    @Override
    public AgentResult execute(Task task) {
        log.info("Locator Agent started");

        String pageUrl = (String) task.getContextValue("pageUrl");
        String pageAnalysisJson = (String) task.getContextValue("pageAnalysisJson");

        if (pageUrl == null || pageAnalysisJson == null) {
            return AgentResult.failure(getName(), "Missing pageUrl or pageAnalysisJson in task context");
        }

        try {
            String userPrompt = buildUserPrompt(pageUrl, pageAnalysisJson);
            log.info("Sending request to AI for locator generation");

            AiRequest request = AiRequest.builder(AiOperation.LOCATOR_GENERATION, locatorPrompt, userPrompt)
                    .validator(JsonValidators.hasArrayField("locators"))
                    .build();
            AgentExecutionContext ctx = contextFrom(task);
            AiResponse aiResponse = aiGateway.complete(request, ctx);
            log.info("AI response received for locator generation");

            List<LocatorDefinition> locators = parseResponse(aiResponse.getContent(), pageUrl);
            log.info("Parsed {} locators from AI response", locators.size());

            AgentResult result = AgentResult.success(getName(), "Generated " + locators.size() + " locators");
            result.putData("locatorCount", locators.size());
            result.putData("locators", locators);
            return result;

        } catch (Exception e) {
            log.error("Locator Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(String pageUrl, String pageAnalysisJson) {
        return String.format("""
                Page URL: %s

                Page Analysis JSON:
                %s

                Generate stable Playwright locators for all detectable elements on this page.
                """, pageUrl, pageAnalysisJson);
    }

    private List<LocatorDefinition> parseResponse(String aiResponse, String pageUrl) throws Exception {
        List<LocatorDefinition> locators = new ArrayList<>();

        String json = extractJson(aiResponse);
        JsonNode root = objectMapper.readTree(json);
        JsonNode locatorsNode = root.path("locators");

        if (locatorsNode.isArray()) {
            for (JsonNode node : locatorsNode) {
                LocatorDefinition locator = new LocatorDefinition();
                locator.setPageUrl(pageUrl);
                locator.setElementName(node.path("elementName").asText(""));
                locator.setElementType(node.path("elementType").asText(""));
                locator.setPreferredLocator(node.path("preferredLocator").asText(""));
                locator.setStrategy(node.path("strategy").asText(""));
                locator.setConfidence(node.path("confidence").asInt(0));
                locator.setReason(node.path("reason").asText(""));

                JsonNode fallbackNode = node.path("fallbackLocators");
                if (fallbackNode.isArray()) {
                    List<String> fallbacks = new ArrayList<>();
                    for (JsonNode fb : fallbackNode) {
                        fallbacks.add(fb.asText());
                    }
                    locator.setFallbackLocators(String.join(",", fallbacks));
                }

                locators.add(locator);
            }
        }

        return locators;
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

    private AgentExecutionContext contextFrom(Task task) {
        return AgentExecutionContext.builder()
                .projectContext(ProjectContextUtil.fromTask(task))
                .operationId("op-" + task.getId())
                .build();
    }
}
