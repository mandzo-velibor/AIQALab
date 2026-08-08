package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.executor.ExecutorAgent;
import com.qalab.qalabai.dto.executor.ExecutionResponse;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.service.report.ReportService;
import com.qalab.qalabai.service.workspace.ArtifactResult;
import com.qalab.qalabai.service.workspace.ArtifactStore;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import com.qalab.qalabai.service.workspace.WorkspaceManager;
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
    private final WorkspaceManager workspaceManager;
    private final ArtifactStore artifactStore;
    private final ReportService reportService;

    public ExecutionService(ExecutorAgent executorAgent,
                            GeneratedTestRepository testRepository,
                            TestExecutionRepository executionRepository,
                            TestWorkspaceService testWorkspaceService,
                            WorkspaceManager workspaceManager,
                            ArtifactStore artifactStore,
                            ReportService reportService) {
        this.executorAgent = executorAgent;
        this.testRepository = testRepository;
        this.executionRepository = executionRepository;
        this.testWorkspaceService = testWorkspaceService;
        this.workspaceManager = workspaceManager;
        this.artifactStore = artifactStore;
        this.reportService = reportService;
    }

    public ExecutionResponse runTest(Long testId, Long projectId) {
        log.info("Running test with id: {}, project: {}", testId, projectId);

        GeneratedTest test = testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found: " + testId));

        Long resolvedProjectId = projectId != null ? projectId : test.getProjectId();
        if (resolvedProjectId != null) {
            testWorkspaceService.writeTestFiles(resolvedProjectId, List.of(test));
        }

        ProjectContext projectContext = workspaceManager.getProjectContext(resolvedProjectId);
        String testFile = TestWorkspaceService.resolveFileName(test);

        var result = run(task -> {
            task.putContext("testFile", testFile);
            task.putContext("runAll", false);
            task.putContext("projectContext", projectContext);
        }, resolvedProjectId);

        return result;
    }

    public ExecutionResponse runAllTests(Long projectId) {
        log.info("Running all tests for project: {}", projectId);

        if (projectId != null) {
            List<GeneratedTest> tests = testRepository.findByProjectId(projectId);
            testWorkspaceService.writeTestFiles(projectId, tests);
        }

        ProjectContext projectContext = workspaceManager.getProjectContext(projectId);

        return run(task -> {
            task.putContext("runAll", true);
            task.putContext("projectContext", projectContext);
        }, projectId);
    }

    private interface TaskConfigurer {
        void configure(Task task);
    }

    private ExecutionResponse run(TaskConfigurer configurer, Long projectId) {
        Task task = new Task(UUID.randomUUID().toString(), "RUN_TEST", null);
        configurer.configure(task);

        var result = executorAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Execution failed: " + result.getMessage());
        }

        String status = (String) result.getData().get("status");
        Long duration = result.getData().get("duration") instanceof Number n ? n.longValue() : 0L;
        String output = (String) result.getData().get("output");
        String error = (String) result.getData().get("error");
        String testFile = (String) task.getContextValue("testFile");

        TestExecution execution = new TestExecution();
        execution.setProjectId(projectId);
        execution.setTestFile(testFile != null ? testFile : "all");
        execution.setStatus(status);
        execution.setDuration(duration);
        execution.setConsoleLogs(output);
        if ("FAILED".equals(status) || "ERROR".equals(status)) {
            execution.setErrorMessage(error != null ? error : "Unknown error");
        }

        TestExecution saved = executionRepository.save(execution);
        log.info("Execution saved with id: {}, status: {}", saved.getId(), status);

        ProjectContext projectContext = (ProjectContext) task.getContextValue("projectContext");
        attachArtifactsAndReport(saved, projectContext, output);

        return new ExecutionResponse(
                saved.getId(),
                status,
                duration,
                saved.getErrorMessage(),
                output
        );
    }

    private void attachArtifactsAndReport(TestExecution execution, ProjectContext projectContext, String output) {
        try {
            String workspace = null;
            if (projectContext != null) {
                try {
                    workspace = workspaceManager.getWorkspace(projectContext);
                } catch (Exception e) {
                    log.debug("No workspace for artifact collection of execution {}: {}", execution.getId(), e.getMessage());
                }
            }
            ArtifactResult artifacts = artifactStore.collect(workspace, execution.getId(), output);
            if (artifacts.getScreenshot() != null) {
                execution.setScreenshotPath(artifacts.getScreenshot());
            }
            if (artifacts.getVideo() != null) {
                execution.setVideoPath(artifacts.getVideo());
            }
            if (artifacts.getTrace() != null) {
                execution.setTracePath(artifacts.getTrace());
            }
            com.qalab.qalabai.service.report.TestReport report = reportService.generate(execution, artifacts.asMap());
            if (report.reportPath() != null) {
                execution.setReportPath(report.reportPath());
            }
            if (report.status() != null) {
                executionRepository.save(execution);
            }
        } catch (Exception e) {
            log.warn("Artifact collection/report failed for execution {}: {}", execution.getId(), e.getMessage());
        }
    }

    public List<TestExecution> getExecutionHistory(Long projectId) {
        if (projectId != null) {
            return executionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        }
        return executionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Records a test execution from raw results without an associated persisted
     * test row. Used by workflow orchestration when tests are generated on the
     * fly for an explicitly supplied workspace.
     */
    public TestExecution recordExecution(Long projectId, String testFile, String status,
                                         Long duration, String output, String error) {
        TestExecution execution = new TestExecution();
        execution.setProjectId(projectId);
        execution.setTestFile(testFile != null ? testFile : "all");
        execution.setStatus(status);
        execution.setDuration(duration);
        execution.setConsoleLogs(output);
        if ("FAILED".equals(status) || "ERROR".equals(status)) {
            execution.setErrorMessage(error != null ? error : "Unknown error");
        }
        TestExecution saved = executionRepository.save(execution);
        ProjectContext projectContext = null;
        if (projectId != null) {
            try {
                projectContext = workspaceManager.getProjectContext(projectId);
            } catch (Exception e) {
                log.debug("No registered project {} for artifact collection: {}", projectId, e.getMessage());
            }
        }
        attachArtifactsAndReport(saved, projectContext, output);
        return saved;
    }
}
