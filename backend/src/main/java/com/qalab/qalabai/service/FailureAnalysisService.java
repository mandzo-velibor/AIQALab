package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.failure.FailureAnalystAgent;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.FailureHistory;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.FailureAnalysisRepository;
import com.qalab.qalabai.repository.FailureHistoryRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FailureAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalysisService.class);

    private final FailureAnalystAgent failureAnalystAgent;
    private final TestExecutionRepository executionRepository;
    private final FailureAnalysisRepository analysisRepository;
    private final FailureHistoryRepository failureHistoryRepository;

    public FailureAnalysisService(FailureAnalystAgent failureAnalystAgent,
                                  TestExecutionRepository executionRepository,
                                  FailureAnalysisRepository analysisRepository,
                                  FailureHistoryRepository failureHistoryRepository) {
        this.failureAnalystAgent = failureAnalystAgent;
        this.executionRepository = executionRepository;
        this.analysisRepository = analysisRepository;
        this.failureHistoryRepository = failureHistoryRepository;
    }

    public FailureAnalysis analyzeExecution(Long executionId, Long projectId) {
        log.info("Analyzing execution {} for project {}", executionId, projectId);

        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

        Task task = new Task(UUID.randomUUID().toString(), "ANALYZE_FAILURE", execution.getTestFile());
        task.putContext("executionId", executionId);
        task.putContext("projectId", projectId);
        task.putContext("testFile", execution.getTestFile());
        task.putContext("errorMessage", execution.getErrorMessage());
        task.putContext("consoleLogs", execution.getConsoleLogs());
        task.putContext("screenshotPath", execution.getScreenshotPath());

        var result = failureAnalystAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Failure analysis failed: " + result.getMessage());
        }

        FailureAnalysis analysis = (FailureAnalysis) result.getData().get("failureAnalysis");
        FailureAnalysis saved = analysisRepository.save(analysis);
        log.info("Failure analysis saved with id: {}", saved.getId());

        saveHistory(execution, saved);

        return saved;
    }

    private void saveHistory(TestExecution execution, FailureAnalysis analysis) {
        try {
            FailureHistory history = new FailureHistory();
            history.setProjectId(analysis.getProjectId());
            history.setTestName(execution.getTestFile());
            history.setFailureType(analysis.getFailureType());
            history.setMessage(analysis.getSummary());
            history.setRelatedElement(analysis.getAffectedElement());
            failureHistoryRepository.save(history);
        } catch (Exception e) {
            log.warn("Failed to save failure history: {}", e.getMessage());
        }
    }
}
