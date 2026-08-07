package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.failure.FailureAnalystAgent;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.FailureAnalysisRepository;
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

    public FailureAnalysisService(FailureAnalystAgent failureAnalystAgent,
                                  TestExecutionRepository executionRepository,
                                  FailureAnalysisRepository analysisRepository) {
        this.failureAnalystAgent = failureAnalystAgent;
        this.executionRepository = executionRepository;
        this.analysisRepository = analysisRepository;
    }

    public FailureAnalysis analyzeExecution(Long executionId, Long projectId) {
        log.info("Analyzing execution {} for project {}", executionId, projectId);

        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

        Task task = new Task(UUID.randomUUID().toString(), "ANALYZE_FAILURE", execution.getTestFile());
        task.putContext("executionId", executionId);
        task.putContext("projectId", projectId);

        var result = failureAnalystAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Failure analysis failed: " + result.getMessage());
        }

        Long analysisId = (Long) result.getData().get("analysisId");
        return analysisRepository.findById(analysisId)
                .orElseThrow(() -> new RuntimeException("Analysis not found: " + analysisId));
    }
}
