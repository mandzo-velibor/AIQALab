package com.qalab.qalabai.agent.failure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.QaAgent;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.ai.provider.AiProvider;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.FailureHistory;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.FailureAnalysisRepository;
import com.qalab.qalabai.repository.FailureHistoryRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class FailureAnalystAgent implements QaAgent {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalystAgent.class);

    private final AiProvider aiProvider;
    private final TestExecutionRepository executionRepository;
    private final FailureAnalysisRepository analysisRepository;
    private final FailureHistoryRepository failureHistoryRepository;
    private final ObjectMapper objectMapper;
    private final String analystPrompt;

    public FailureAnalystAgent(AiProvider aiProvider,
                               TestExecutionRepository executionRepository,
                               FailureAnalysisRepository analysisRepository,
                               FailureHistoryRepository failureHistoryRepository,
                               ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.executionRepository = executionRepository;
        this.analysisRepository = analysisRepository;
        this.failureHistoryRepository = failureHistoryRepository;
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

        if (executionId == null || projectId == null) {
            return AgentResult.failure(getName(), "Missing executionId or projectId in task context");
        }

        try {
            TestExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

            String userPrompt = buildUserPrompt(execution);
            log.info("Sending failure analysis request to AI");

            String aiResponse = aiProvider.chat(analystPrompt, userPrompt);
            log.info("AI response received for failure analysis");

            FailureAnalysis analysis = parseResponse(aiResponse, projectId, executionId);
            FailureAnalysis saved = analysisRepository.save(analysis);

            FailureHistory history = new FailureHistory();
            history.setProjectId(projectId);
            history.setTestName(execution.getTestFile());
            history.setFailureType(analysis.getFailureType());
            history.setMessage(analysis.getSummary());
            history.setRelatedElement(analysis.getAffectedElement());
            failureHistoryRepository.save(history);

            log.info("Failure analysis saved with id: {}", saved.getId());

            AgentResult result = AgentResult.success(getName(), "Failure analysis completed");
            result.putData("analysisId", saved.getId());
            result.putData("failureType", saved.getFailureType());
            result.putData("confidence", saved.getConfidence());
            result.putData("healingCandidate", saved.getHealingCandidate());
            return result;

        } catch (Exception e) {
            log.error("Failure Analyst Agent failed: {}", e.getMessage(), e);
            return AgentResult.failure(getName(), "Failed: " + e.getMessage());
        }
    }

    private String buildUserPrompt(TestExecution execution) {
        return String.format("""
                Test Name: %s
                Error Message: %s
                Console Logs: %s
                Screenshot Path: %s
                
                Analyze this failed test execution and determine the root cause.
                """,
                execution.getTestFile(),
                execution.getErrorMessage() != null ? execution.getErrorMessage() : "No error message",
                execution.getConsoleLogs() != null ? execution.getConsoleLogs() : "No logs",
                execution.getScreenshotPath() != null ? execution.getScreenshotPath() : "No screenshot");
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
