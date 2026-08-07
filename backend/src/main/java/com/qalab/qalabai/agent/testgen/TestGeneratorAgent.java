package com.qalab.qalabai.agent.testgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.QaAgent;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.ai.provider.AiProvider;
import com.qalab.qalabai.ai.provider.JsonValidators;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class TestGeneratorAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(TestGeneratorAgent.class);

    private final AiProvider aiProvider;
    private final GeneratedTestRepository testRepository;
    private final ObjectMapper objectMapper;
    private final String generatorPrompt;

    public TestGeneratorAgent(AiProvider aiProvider,
                              GeneratedTestRepository testRepository,
                              ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.testRepository = testRepository;
        this.objectMapper = objectMapper;
        this.generatorPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/test-generator.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load test generator prompt: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public String getName() {
        return "TestGenerator";
    }

    @Override
    public AgentResult execute(Task task) {
        log.info("Test Generator Agent started");

        String pageUrl = (String) task.getContextValue("pageUrl");
        String testPlanJson = (String) task.getContextValue("testPlanJson");
        String locatorRepositoryJson = (String) task.getContextValue("locatorRepositoryJson");
        Long projectId = task.getContextValue("projectId") instanceof Number n ? n.longValue() : null;

        if (pageUrl == null || testPlanJson == null) {
            return AgentResult.failure(getName(), "Missing pageUrl or testPlanJson in task context");
        }

        try {
            String userPrompt = buildUserPrompt(pageUrl, testPlanJson, locatorRepositoryJson);
            log.info("Sending request to AI for test generation");

            String aiResponse = aiProvider.chat(generatorPrompt, userPrompt, JsonValidators.hasArrayField("tests"));
            log.info("AI response received for test generation");

            List<GeneratedTest> tests = parseResponse(aiResponse, pageUrl);
            if (projectId != null) {
                tests.forEach(t -> t.setProjectId(projectId));
            }
            log.info("Parsed {} tests from AI response", tests.size());

            List<GeneratedTest> saved = testRepository.saveAll(tests);
            log.info("Saved {} tests to database", saved.size());

            AgentResult result = AgentResult.success(getName(), "Generated " + saved.size() + " tests");
            result.putData("testCount", saved.size());
            result.putData("tests", saved);
            return result;

        } catch (Exception e) {
            log.error("Test Generator Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(String pageUrl, String testPlanJson, String locatorRepositoryJson) {
        return String.format("""
                Page URL: %s

                Test Plan JSON:
                %s

                Locator Repository JSON:
                %s

                Generate Playwright tests for all scenarios in the test plan.
                Use the locators from the repository.
                Follow Page Object Model pattern.
                """, pageUrl, testPlanJson, locatorRepositoryJson != null ? locatorRepositoryJson : "Not available");
    }

    private List<GeneratedTest> parseResponse(String aiResponse, String pageUrl) throws Exception {
        List<GeneratedTest> tests = new ArrayList<>();

        String json = extractJson(aiResponse);
        JsonNode root = objectMapper.readTree(json);
        JsonNode testsNode = root.path("tests");

        if (testsNode.isArray()) {
            for (JsonNode node : testsNode) {
                GeneratedTest test = new GeneratedTest();
                test.setPageUrl(pageUrl);
                test.setScenarioName(node.path("scenarioName").asText(""));
                test.setTestFileName(toFileName(node.path("scenarioName").asText("")));
                test.setTestCode(node.path("testCode").asText(""));
                test.setPageObjectCode(node.path("pageObjectCode").asText(""));
                tests.add(test);
            }
        }

        return tests;
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

    private String toFileName(String scenarioName) {
        String base = scenarioName == null ? "test" : scenarioName.trim();
        String fileName = base
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (fileName.isBlank() || fileName.equals("null")) {
            fileName = "test";
        }
        return fileName + ".spec.ts";
    }
}
