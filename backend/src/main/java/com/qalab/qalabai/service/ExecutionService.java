package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.executor.ExecutorAgent;
import com.qalab.qalabai.dto.executor.ExecutionResponse;
import com.qalab.qalabai.healing.service.HealingAnalysisService;
import com.qalab.qalabai.healing.service.HealingOutcome;
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

import java.util.ArrayList;
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
    private final HealingAnalysisService healingAnalysisService;

    public ExecutionService(ExecutorAgent executorAgent,
                            GeneratedTestRepository testRepository,
                            TestExecutionRepository executionRepository,
                            TestWorkspaceService testWorkspaceService,
                            WorkspaceManager workspaceManager,
                            ArtifactStore artifactStore,
                            ReportService reportService,
                            HealingAnalysisService healingAnalysisService) {
        this.executorAgent = executorAgent;
        this.testRepository = testRepository;
        this.executionRepository = executionRepository;
        this.testWorkspaceService = testWorkspaceService;
        this.workspaceManager = workspaceManager;
        this.artifactStore = artifactStore;
        this.reportService = reportService;
        this.healingAnalysisService = healingAnalysisService;
    }

    /** Result of an execution: the executor response plus the healing outcome computed during the run (may be null). */
    public record RunResult(ExecutionResponse response, HealingOutcome healing) {
    }

    public ExecutionResponse runTest(Long testId, Long projectId) {
        return runTest(testId, projectId, false, null, null).response();
    }

    public RunResult runTest(Long testId, Long projectId, boolean healingAnalysis) {
        return runTest(testId, projectId, healingAnalysis, null, null);
    }

    public RunResult runTest(Long testId, Long projectId, boolean healingAnalysis, String testType, String instruction) {
        String normalizedType = CodeGenerationService.normalizeTestType(testType);
        String normalizedInstruction = com.qalab.qalabai.util.UserInstructions.normalize(instruction);
        log.info("Running test with id: {}, project: {}, healingAnalysis: {}, testType: {}",
                testId, projectId, healingAnalysis, normalizedType);

        GeneratedTest test = testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found: " + testId));

        Long resolvedProjectId = projectId != null ? projectId : test.getProjectId();
        if (resolvedProjectId != null) {
            testWorkspaceService.writeTestFiles(resolvedProjectId, List.of(test));
        }

        ProjectContext projectContext = workspaceManager.getProjectContext(resolvedProjectId);
        String testFile = TestWorkspaceService.resolveFileName(test);

        return run(task -> {
            task.putContext("testFile", testFile);
            task.putContext("runAll", false);
            task.putContext("projectContext", projectContext);
            if (normalizedType != null) {
                task.putContext("testType", normalizedType);
            }
            if (normalizedInstruction != null) {
                task.putContext("instruction", normalizedInstruction);
            }
        }, resolvedProjectId, healingAnalysis, normalizedType, normalizedInstruction);
    }

    public ExecutionResponse runAllTests(Long projectId) {
        return runAllTests(projectId, false, null, null).response();
    }

    public RunResult runAllTests(Long projectId, boolean healingAnalysis) {
        return runAllTests(projectId, healingAnalysis, null, null);
    }

    public RunResult runAllTests(Long projectId, boolean healingAnalysis, String testType, String instruction) {
        String normalizedType = CodeGenerationService.normalizeTestType(testType);
        String normalizedInstruction = com.qalab.qalabai.util.UserInstructions.normalize(instruction);
        log.info("Running all tests for project: {}, healingAnalysis: {}, testType: {}",
                projectId, healingAnalysis, normalizedType);

        if (projectId != null) {
            List<GeneratedTest> tests = testRepository.findByProjectId(projectId);
            testWorkspaceService.writeTestFiles(projectId, tests);
        }

        ProjectContext projectContext = workspaceManager.getProjectContext(projectId);

        return run(task -> {
            task.putContext("runAll", true);
            task.putContext("projectContext", projectContext);
            if (normalizedType != null) {
                task.putContext("testType", normalizedType);
            }
            if (normalizedInstruction != null) {
                task.putContext("instruction", normalizedInstruction);
            }
        }, projectId, healingAnalysis, normalizedType, normalizedInstruction);
    }

    private interface TaskConfigurer {
        void configure(Task task);
    }

    private RunResult run(TaskConfigurer configurer, Long projectId, boolean healingAnalysis,
                          String testType, String instruction) {
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
        HealingOutcome healingOutcome = null;
        if (healingAnalysis && ("FAILED".equals(status) || "ERROR".equals(status)) && projectId != null) {
            try {
                healingOutcome = healingAnalysisService.analyzeExecution(saved.getId(), projectId);
                log.info("Healing analysis completed for execution {}: proposalCreated={}",
                        saved.getId(), healingOutcome.isProposalCreated());
            } catch (Exception e) {
                log.warn("Healing analysis failed for execution {}: {}", saved.getId(), e.getMessage());
            }
        }
        attachArtifactsAndReport(saved, projectContext, output, healingOutcome);

        String note = buildNote(testType, instruction, status);

        return new RunResult(
                new ExecutionResponse(
                        saved.getId(),
                        status,
                        duration,
                        saved.getErrorMessage(),
                        output,
                        testType,
                        instruction,
                        note
                ),
                healingOutcome
        );
    }

    private String buildNote(String testType, String instruction, String status) {
        List<String> parts = new ArrayList<>();
        if (testType != null) {
            parts.add("Ran only " + testType.toUpperCase() + " tests in the workspace.");
        }
        if (instruction != null) {
            String mentioned = mentionedTestType(instruction);
            if (mentioned != null && testType != null && !testType.equalsIgnoreCase(mentioned)) {
                parts.add("Conflict: the textual instruction mentions " + mentioned.toUpperCase()
                        + " tests but the structured filter is " + testType.toUpperCase()
                        + "; the structured filter wins because it is deterministic.");
            } else {
                parts.add("The textual instruction was not machine-enforced beyond the structured filter.");
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        parts.add("Final status: " + status + ".");
        return String.join(" ", parts);
    }

    private String mentionedTestType(String instruction) {
        String lower = instruction.toLowerCase();
        if (lower.contains("api")) {
            return "api";
        }
        if (lower.contains("e2e")) {
            return "e2e";
        }
        if (lower.contains("ui")) {
            return "ui";
        }
        return null;
    }

    private void attachArtifactsAndReport(TestExecution execution, ProjectContext projectContext,
                                          String output, HealingOutcome healingOutcome) {
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
            com.qalab.qalabai.service.report.TestReport report = reportService.generate(execution, artifacts.asMap(), healingOutcome);
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
        attachArtifactsAndReport(saved, projectContext, output, null);
        return saved;
    }

    public HealingOutcome analyzeExecutionHealing(Long executionId, Long projectId) {
        return healingAnalysisService.analyzeExecution(executionId, projectId);
    }
}
