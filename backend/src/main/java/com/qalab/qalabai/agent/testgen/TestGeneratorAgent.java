package com.qalab.qalabai.agent.testgen;

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
import com.qalab.qalabai.model.GeneratedTest;
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

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final String generatorPrompt;

    public TestGeneratorAgent(AiGateway aiGateway,
                              ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
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
        String pageContentHtml = (String) task.getContextValue("pageContentHtml");
        String postLoginContentHtml = (String) task.getContextValue("postLoginContentHtml");
        String loginUsername = (String) task.getContextValue("loginUsername");
        String loginPassword = (String) task.getContextValue("loginPassword");
        Long projectId = task.getContextValue("projectId") instanceof Number n ? n.longValue() : null;

        if (pageUrl == null || testPlanJson == null) {
            return AgentResult.failure(getName(), "Missing pageUrl or testPlanJson in task context");
        }

        try {
            String userPrompt = buildUserPrompt(pageUrl, testPlanJson, locatorRepositoryJson, pageContentHtml, postLoginContentHtml, loginUsername, loginPassword);
            log.info("Sending request to AI for test generation");

            AiRequest request = AiRequest.builder(AiOperation.TEST_GENERATION, generatorPrompt, userPrompt)
                    .validator(JsonValidators.hasArrayField("tests"))
                    .build();
            AgentExecutionContext ctx = AgentExecutionContext.builder()
                    .projectContext(ProjectContextUtil.fromTask(task))
                    .operationId("op-" + task.getId())
                    .build();
            AiResponse aiResponse = aiGateway.complete(request, ctx);
            log.info("AI response received for test generation");

            List<GeneratedTest> tests = parseResponse(aiResponse.getContent(), pageUrl);
            if (projectId != null) {
                tests.forEach(t -> t.setProjectId(projectId));
            }
            log.info("Parsed {} tests from AI response", tests.size());

            AgentResult result = AgentResult.success(getName(), "Generated " + tests.size() + " tests");
            result.putData("testCount", tests.size());
            result.putData("tests", tests);
            return result;

        } catch (Exception e) {
            log.error("Test Generator Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(String pageUrl, String testPlanJson, String locatorRepositoryJson, String pageContentHtml, String postLoginContentHtml, String loginUsername, String loginPassword) {
        String pageContent = pageContentHtml != null && !pageContentHtml.isBlank()
                ? pageContentHtml
                : "Not available";
        String postLoginContent = postLoginContentHtml != null && !postLoginContentHtml.isBlank()
                ? postLoginContentHtml
                : "Not available (no credentials provided or login not attempted)";
        String credentialsInfo = (loginUsername != null && !loginUsername.isBlank() && loginPassword != null)
                ? String.format("Username: %s\nPassword: %s", loginUsername, loginPassword)
                : "Not provided (tests must use environment variables or config)";

        return String.format("""
                Page URL: %s

                Test Plan JSON:
                %s

                Locator Repository JSON:
                %s

                ACTUAL PAGE CONTENT (simplified HTML of the landing page):
                %s

                POST-LOGIN PAGE CONTENT (simplified HTML of the page AFTER successful login):
                %s

                LOGIN CREDENTIALS (use these exact values when the test needs to log in):
                %s

                Generate Playwright tests for all scenarios in the test plan.
                Use the locators from the repository.
                Follow Page Object Model pattern.
                CRITICAL RULES:
                - Every assertion (text, heading, element visibility) MUST be based ONLY on elements
                  that actually exist in the ACTUAL PAGE CONTENT (for pre-login) or
                  POST-LOGIN PAGE CONTENT (for state AFTER login).
                - If a scenario performs login, assertions about the post-login state
                  MUST use the POST-LOGIN PAGE CONTENT above.
                - If POST-LOGIN PAGE CONTENT is "Not available", do NOT assert on any heading,
                  text, or element after login — assert ONLY on the URL (e.g. expect(page).toHaveURL(...)).
                - Do NOT invent headings, texts, labels, or element names that are not present
                  in the provided page content.
                - Use exact visible text from the relevant page content for assertions.
                - When the test needs to log in, use the LOGIN CREDENTIALS provided above.
                """, pageUrl, testPlanJson, locatorRepositoryJson != null ? locatorRepositoryJson : "Not available", pageContent, postLoginContent, credentialsInfo);
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
                test.setTestCode(normalizeCode(node.path("testCode").asText("")));
                test.setPageObjectCode(normalizeCode(node.path("pageObjectCode").asText("")));
                tests.add(test);
            }
        }

        return tests;
    }

    /**
     * Some providers double-encode line breaks inside generated code: the code
     * arrives as a single line whose newlines are literal "\n" (backslash-n)
     * characters instead of real line breaks. When the model clearly did that
     * (more escaped newlines than real ones) the literal sequences are decoded
     * back to actual newlines so the written file is valid source code.
     */
    private String normalizeCode(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        int literalNewlines = countOccurrences(code, "\\n");
        int realNewlines = countOccurrences(code, "\n");
        if (literalNewlines > 0 && realNewlines == 0) {
            return code
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t");
        }
        return code;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
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
