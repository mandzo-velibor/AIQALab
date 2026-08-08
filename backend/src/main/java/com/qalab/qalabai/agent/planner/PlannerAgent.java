package com.qalab.qalabai.agent.planner;

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
import com.qalab.qalabai.model.TestPlan;
import com.qalab.qalabai.model.TestScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class PlannerAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final String plannerPrompt;

    public PlannerAgent(AiGateway aiGateway,
                        ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.plannerPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/planner-agent.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load planner prompt: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public String getName() {
        return "Planner";
    }

    @Override
    public AgentResult execute(Task task) {
        log.info("Planner Agent started");

        String pageUrl = (String) task.getContextValue("pageUrl");
        String pageAnalysisJson = (String) task.getContextValue("pageAnalysisJson");
        String locatorRepositoryJson = (String) task.getContextValue("locatorRepositoryJson");
        Long projectId = task.getContextValue("projectId") instanceof Number n ? n.longValue() : null;

        if (pageUrl == null || pageAnalysisJson == null) {
            return AgentResult.failure(getName(), "Missing pageUrl or pageAnalysisJson in task context");
        }

        try {
            String userPrompt = buildUserPrompt(pageUrl, pageAnalysisJson, locatorRepositoryJson);
            log.info("Sending request to AI for test plan generation");

            AiRequest request = AiRequest.builder(AiOperation.TEST_PLAN, plannerPrompt, userPrompt)
                    .validator(JsonValidators.hasArrayField("scenarios"))
                    .build();
            AgentExecutionContext ctx = AgentExecutionContext.builder()
                    .projectContext(ProjectContextUtil.fromTask(task))
                    .operationId("op-" + task.getId())
                    .build();
            AiResponse aiResponse = aiGateway.complete(request, ctx);
            log.info("AI response received for test plan generation");

            TestPlan testPlan = parseResponse(aiResponse.getContent(), pageUrl);
            if (projectId != null) {
                testPlan.setProjectId(projectId);
            }
            log.info("Parsed test plan with {} scenarios", testPlan.getScenarios().size());

            AgentResult result = AgentResult.success(getName(), "Generated test plan with " + testPlan.getScenarios().size() + " scenarios");
            result.putData("scenarioCount", testPlan.getScenarios().size());
            result.putData("testPlan", testPlan);
            return result;

        } catch (Exception e) {
            log.error("Planner Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(String pageUrl, String pageAnalysisJson, String locatorRepositoryJson) {
        return String.format("""
                Page URL: %s

                Page Analysis JSON:
                %s

                Locator Repository JSON:
                %s

                Create a comprehensive test plan for this page.
                Include positive, negative, validation, security, and reliability scenarios.
                """, pageUrl, pageAnalysisJson, locatorRepositoryJson != null ? locatorRepositoryJson : "Not available");
    }

    private TestPlan parseResponse(String aiResponse, String pageUrl) throws Exception {
        String json = extractJson(aiResponse);
        JsonNode root = objectMapper.readTree(json);

        TestPlan testPlan = new TestPlan();
        testPlan.setPageUrl(pageUrl);
        testPlan.setPageType(root.path("pageType").asText(""));

        JsonNode scenariosNode = root.path("scenarios");
        List<TestScenario> scenarios = new ArrayList<>();

        if (scenariosNode.isArray()) {
            for (JsonNode scenarioNode : scenariosNode) {
                TestScenario scenario = new TestScenario();
                scenario.setTestPlan(testPlan);
                scenario.setName(scenarioNode.path("name").asText(""));
                scenario.setType(scenarioNode.path("type").asText(""));
                scenario.setPriority(scenarioNode.path("priority").asText(""));
                scenario.setDescription(scenarioNode.path("description").asText(""));

                JsonNode stepsNode = scenarioNode.path("steps");
                if (stepsNode.isArray()) {
                    List<String> steps = new ArrayList<>();
                    for (JsonNode step : stepsNode) {
                        steps.add(step.asText());
                    }
                    scenario.setSteps(steps);
                }

                JsonNode elementsNode = scenarioNode.path("requiredElements");
                if (elementsNode.isArray()) {
                    List<String> elements = new ArrayList<>();
                    for (JsonNode elem : elementsNode) {
                        elements.add(elem.asText());
                    }
                    scenario.setRequiredElements(elements);
                }

                scenarios.add(scenario);
            }
        }

        testPlan.setScenarios(scenarios);
        return testPlan;
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
