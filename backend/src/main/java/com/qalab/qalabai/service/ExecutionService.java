package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.executor.ExecutorAgent;
import com.qalab.qalabai.dto.executor.ExecutionResponse;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutorAgent executorAgent;
    private final GeneratedTestRepository testRepository;
    private final TestExecutionRepository executionRepository;
    private final TestWorkspaceService testWorkspaceService;

    public ExecutionService(ExecutorAgent executorAgent,
                            GeneratedTestRepository testRepository,
                            TestExecutionRepository executionRepository,
                            TestWorkspaceService testWorkspaceService) {
        this.executorAgent = executorAgent;
        this.testRepository = testRepository;
        this.executionRepository = executionRepository;
        this.testWorkspaceService = testWorkspaceService;
    }

    public ExecutionResponse runTest(Long testId, Long projectId) {
        log.info("Running test with id: {}, project: {}", testId, projectId);

        GeneratedTest test = testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found: " + testId));

        Long resolvedProjectId = projectId != null ? projectId : test.getProjectId();
        String workspacePath = null;
        if (resolvedProjectId != null) {
            workspacePath = testWorkspaceService.writeTestFiles(resolvedProjectId, List.of(test));
        }

        Task task = new Task(UUID.randomUUID().toString(), "RUN_TEST", test.getPageUrl());
        task.putContext("testFile", TestWorkspaceService.resolveFileName(test));
        task.putContext("runAll", false);
        task.putContext("projectId", resolvedProjectId);
        task.putContext("workspacePath", workspacePath);

        var result = executorAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Execution failed: " + result.getMessage());
        }

        return new ExecutionResponse(
                (Long) result.getData().get("executionId"),
                (String) result.getData().get("status"),
                (Long) result.getData().get("duration"),
                null,
                (String) result.getData().get("output")
        );
    }

    public ExecutionResponse runAllTests(Long projectId) {
        log.info("Running all tests for project: {}", projectId);

        String workspacePath = null;
        if (projectId != null) {
            List<GeneratedTest> tests = testRepository.findByProjectId(projectId);
            workspacePath = testWorkspaceService.writeTestFiles(projectId, tests);
        }

        Task task = new Task(UUID.randomUUID().toString(), "RUN_ALL_TESTS", null);
        task.putContext("runAll", true);
        task.putContext("projectId", projectId);
        task.putContext("workspacePath", workspacePath);

        var result = executorAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Execution failed: " + result.getMessage());
        }

        return new ExecutionResponse(
                (Long) result.getData().get("executionId"),
                (String) result.getData().get("status"),
                (Long) result.getData().get("duration"),
                null,
                (String) result.getData().get("output")
        );
    }

    public List<TestExecution> getExecutionHistory(Long projectId) {
        if (projectId != null) {
            return executionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        }
        return executionRepository.findAllByOrderByCreatedAtDesc();
    }
}
