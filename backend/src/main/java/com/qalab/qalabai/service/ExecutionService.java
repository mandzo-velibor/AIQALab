package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.executor.ExecutorAgent;
import com.qalab.qalabai.dto.executor.ExecutionResponse;
import com.qalab.qalabai.healing.service.HealingAnalysisService;
import com.qalab.qalabai.healing.service.HealingOutcome;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.TestCaseResult;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.TestCaseResultRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.service.report.ReportService;
import com.qalab.qalabai.service.workspace.ArtifactResult;
import com.qalab.qalabai.service.workspace.ArtifactStore;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import com.qalab.qalabai.service.workspace.WorkspaceManager;
import com.qalab.qalabai.tool.playwright.PlaywrightResultParser;
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
    private final TestCaseResultRepository testCaseResultRepository;
    private final ObjectMapper objectMapper;

    public ExecutionService(ExecutorAgent executorAgent,
                            GeneratedTestRepository testRepository,
                            TestExecutionRepository executionRepository,
                            TestWorkspaceService testWorkspaceService,
                            WorkspaceManager workspaceManager,
                            ArtifactStore artifactStore,
                            ReportService reportService,
                            HealingAnalysisService healingAnalysisService,
                            TestCaseResultRepository testCaseResultRepository,
                            ObjectMapper objectMapper) {
        this.executorAgent = executorAgent;
        this.testRepository = testRepository;
        this.executionRepository = executionRepository;
        this.testWorkspaceService = testWorkspaceService;
        this.workspaceManager = workspaceManager;
        this.artifactStore = artifactStore;
        this.reportService = reportService;
        this.healingAnalysisService = healingAnalysisService;
        this.testCaseResultRepository = testCaseResultRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the run's per-test results against the saved execution.
     *
     * <p>Returns the number of rows written, or 0 when the runner produced no
     * structured summary (an older runner, or a workspace whose config could not be
     * extended). Never throws: the per-test detail is valuable but not worth losing a
     * completed run over, and the execution row is already committed by this point.</p>
     */
    private int persistTestCaseResults(TestExecution execution, java.util.Map<String, Object> resultData) {
        try {
            Object summary = resultData.get("summary");
            if (!(summary instanceof PlaywrightResultParser.RunSummary run) || run.tests().isEmpty()) {
                return 0;
            }
            List<TestCaseResult> rows = new ArrayList<>();
            int ordinal = 0;
            for (PlaywrightResultParser.TestCaseResult t : run.tests()) {
                TestCaseResult row = new TestCaseResult();
                row.setExecution(execution);
                row.setOrdinalPosition(ordinal++);
                row.setSpecFile(t.file());
                row.setTestTitle(t.title());
                row.setFullTitle(t.fullTitle());
                row.setStatus(t.status());
                row.setDurationMs(t.durationMs());
                row.setRetries(t.retries());
                row.setErrorMessage(t.errorMessage());
                row.setErrorSnippet(t.errorSnippet());
                row.setScreenshots(toJson(t.screenshots()));
                row.setVideos(toJson(t.videos()));
                row.setTraces(toJson(t.traces()));
                rows.add(row);
            }
            testCaseResultRepository.saveAll(rows);
            return rows.size();
        } catch (RuntimeException e) {
            log.warn("Could not persist per-test results for execution {}: {}",
                    execution.getId(), e.getMessage());
            return 0;
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
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
        workspaceManager.prepareWorkspace(projectContext);
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
        workspaceManager.prepareWorkspace(projectContext);

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

        // Persist the structured per-test results (B-022). Best-effort: losing the
        // detail must never lose the execution itself, so a failure here is logged
        // and the run still counts as saved.
        int persistedResults = persistTestCaseResults(saved, result.getData());
        if (persistedResults > 0) {
            log.info("Persisted {} per-test result(s) for execution {}", persistedResults, saved.getId());
        }

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
        String htmlReportPath = attachArtifactsAndReport(saved, projectContext, output, healingOutcome);

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
                        note,
                        saved.getReportPath(),
                        htmlReportPath
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

    /**
     * @return the path of the self-contained HTML report, or null when no artifact
     *         directory was available. Returned rather than only logged, because the
     *         report lives in the artifact directory and the CLI cannot guess it.
     */
    private String attachArtifactsAndReport(TestExecution execution, ProjectContext projectContext,
                                            String output, HealingOutcome healingOutcome) {
        String htmlReportPath = null;
        try {
            String workspace = null;
            if (projectContext != null) {
                try {
                    workspace = workspaceManager.getWorkspace(projectContext);
                } catch (Exception e) {
                    log.debug("No workspace for artifact collection of execution {}: {}", execution.getId(), e.getMessage());
                }
            }
            // The persisted per-test results (B-022) are the only reliable link between
            // a failure and its evidence, so the artifacts are collected per test rather
            // than flattened. Anything not collected per test is logged once.
            List<TestCaseResult> caseResults =
                    testCaseResultRepository.findByExecutionIdOrderByOrdinalPositionAsc(execution.getId());
            ArtifactResult artifacts = artifactStore.collect(workspace, execution.getId(), output,
                    toTestArtifacts(caseResults));
            if (artifacts.getScreenshot() != null) {
                execution.setScreenshotPath(artifacts.getScreenshot());
            }
            if (artifacts.getVideo() != null) {
                execution.setVideoPath(artifacts.getVideo());
            }
            if (artifacts.getTrace() != null) {
                execution.setTracePath(artifacts.getTrace());
            }
            com.qalab.qalabai.service.report.TestReport report = reportService.generate(
                    execution, artifacts.asMap(), healingOutcome,
                    toTestCaseViews(caseResults), null);
            htmlReportPath = report.htmlReportPath();
            if (report.reportPath() != null) {
                execution.setReportPath(report.reportPath());
            }
            if (htmlReportPath != null) {
                execution.setHtmlReportPath(htmlReportPath);
            }
            if (report.status() != null) {
                executionRepository.save(execution);
            }
        } catch (Exception e) {
            log.warn("Artifact collection/report failed for execution {}: {}", execution.getId(), e.getMessage());
        }
        return htmlReportPath;
    }

    /**
     * Turns persisted per-test rows into the artifact collector's input.
     *
     * <p>Paths are stored as JSON arrays of the absolute paths Playwright reported, so
     * they are unpacked here rather than by the collector. A test with no attachments is
     * still passed through: the report lists every test, and "no evidence" is worth
     * showing rather than hiding the test entirely.</p>
     */
    private List<com.qalab.qalabai.service.workspace.TestArtifacts> toTestArtifacts(
            List<TestCaseResult> caseResults) {
        List<com.qalab.qalabai.service.workspace.TestArtifacts> out = new ArrayList<>();
        for (TestCaseResult row : caseResults) {
            out.add(new com.qalab.qalabai.service.workspace.TestArtifacts(
                    row.getOrdinalPosition() != null ? row.getOrdinalPosition() : out.size(),
                    slug(row.getTestTitle()),
                    row.getSpecFile(),
                    row.getTestTitle(),
                    row.getStatus(),
                    readPaths(row.getScreenshots()),
                    readPaths(row.getVideos()),
                    readPaths(row.getTraces()),
                    null));
        }
        return out;
    }

    private List<com.qalab.qalabai.service.report.HtmlReportRenderer.TestCaseView> toTestCaseViews(
            List<TestCaseResult> caseResults) {
        List<com.qalab.qalabai.service.report.HtmlReportRenderer.TestCaseView> out = new ArrayList<>();
        for (TestCaseResult row : caseResults) {
            out.add(new com.qalab.qalabai.service.report.HtmlReportRenderer.TestCaseView(
                    row.getOrdinalPosition() != null ? row.getOrdinalPosition() : out.size(),
                    row.getSpecFile(),
                    row.getTestTitle(),
                    row.getStatus(),
                    row.getDurationMs(),
                    row.getRetries() != null ? row.getRetries() : 0,
                    row.getErrorMessage(),
                    row.getErrorSnippet(),
                    readPaths(row.getScreenshots()),
                    readPaths(row.getVideos()),
                    readPaths(row.getTraces())));
        }
        return out;
    }

    /** A short, filesystem-safe form of a test title, used for the artifact directory. */
    static String slug(String title) {
        if (title == null || title.isBlank()) {
            return "test";
        }
        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            return "test";
        }
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
    }

    /** A stored attachment list. A malformed cell is treated as absent, not fatal. */
    private List<String> readPaths(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, String[].class));
        } catch (Exception e) {
            log.debug("Ignoring unreadable attachment list: {}", json);
            return List.of();
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
