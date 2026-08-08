package com.qalab.qalabai.agent.failure;

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
import com.qalab.qalabai.model.FailureAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class FailureAnalystAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalystAgent.class);

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final String analystPrompt;

    public FailureAnalystAgent(AiGateway aiGateway,
                               ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.analystPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/failure-analyst.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load failure analyst prompt: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public String getName() {
        return "FailureAnalyst";
    }

    @Override
    public AgentResult execute(Task task) {
        log.info("Failure Analyst Agent started");

        Long executionId = (Long) task.getContextValue("executionId");
        Long projectId = (Long) task.getContextValue("projectId");
        String testFile = (String) task.getContextValue("testFile");
        String errorMessage = (String) task.getContextValue("errorMessage");
        String consoleLogs = (String) task.getContextValue("consoleLogs");
        String screenshotPath = (String) task.getContextValue("screenshotPath");

        if (executionId == null || projectId == null) {
            return AgentResult.failure(getName(), "Missing executionId or projectId in task context");
        }

        try {
            String userPrompt = buildUserPrompt(testFile, errorMessage, consoleLogs, screenshotPath);
            log.info("Sending failure analysis request to AI");

            AiRequest request = AiRequest.builder(AiOperation.FAILURE_ANALYSIS, analystPrompt, userPrompt)
                    .validator(JsonValidators.isJsonObject())
                    .build();
            AgentExecutionContext ctx = AgentExecutionContext.builder()
                    .projectContext(ProjectContextUtil.fromTask(task))
                    .operationId("op-" + task.getId())
                    .build();
            AiResponse aiResponse = aiGateway.complete(request, ctx);
            log.info("AI response received for failure analysis");

            FailureAnalysis analysis = parseResponse(aiResponse.getContent(), projectId, executionId);
            log.info("Failure analysis parsed: type={}, healingCandidate={}",
                    analysis.getFailureType(), analysis.getHealingCandidate());

            AgentResult result = AgentResult.success(getName(), "Failure analysis completed");
            result.putData("failureAnalysis", analysis);
            result.putData("failureType", analysis.getFailureType());
            result.putData("confidence", analysis.getConfidence());
            result.putData("healingCandidate", analysis.getHealingCandidate());
            return result;

        } catch (Exception e) {
            log.error("Failure Analyst Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(String testFile, String errorMessage, String consoleLogs, String screenshotPath) {
        return String.format("""
                Test Name: %s
                Error Message: %s
                Console Logs: %s
                Screenshot Path: %s
                
                Analyze this failed test execution and determine the root cause.
                """,
                testFile != null ? testFile : "unknown",
                errorMessage != null ? errorMessage : "No error message",
                consoleLogs != null ? consoleLogs : "No logs",
                screenshotPath != null ? screenshotPath : "No screenshot");
    }

    private FailureAnalysis parseResponse(String aiResponse, Long projectId, Long executionId) throws Exception {
        String json = extractJson(aiResponse);
        JsonNode root = objectMapper.readTree(json);

        FailureAnalysis analysis = new FailureAnalysis();
        analysis.setProjectId(projectId);
        analysis.setExecutionId(executionId);
        analysis.setFailureType(root.path("failureType").asText("UNKNOWN"));
        analysis.setConfidence(root.path("confidence").asInt(50));
        analysis.setSummary(root.path("summary").asText(""));
        analysis.setAffectedElement(root.path("affectedElement").asText(""));
        analysis.setHealingCandidate(root.path("healingCandidate").asBoolean(false));
        analysis.setAnalysisJson(json);

        return analysis;
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
