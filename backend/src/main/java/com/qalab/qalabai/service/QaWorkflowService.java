package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest;
import com.qalab.qalabai.api.v1.dto.V1WorkflowResponse;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.dto.planner.TestPlanResponse;
import com.qalab.qalabai.dto.testgen.GeneratedFile;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import com.qalab.qalabai.tool.playwright.PlaywrightResultParser;
import com.qalab.qalabai.service.workspace.WorkspaceProvider;
import com.qalab.qalabai.util.UserInstructions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the full QA workflow (FULL_TEST):
 *
 * <pre>
 * EXPLORE &rarr; ANALYZE &rarr; LOCATORS &rarr; TEST PLAN &rarr; GENERATE TESTS &rarr; RUN &rarr; FAILURE ANALYSIS &rarr; HEALING CANDIDATE
 * </pre>
 *
 * <p>Branching rules:
 * <ul>
 *   <li>RUN passes &rarr; workflow completes.</li>
 *   <li>RUN fails &rarr; failure analysis runs.</li>
 *   <li>Failure is a healing candidate &rarr; a healing suggestion is generated.</li>
 *   <li>No explicit workspacePath &rarr; execution (and downstream steps) are SKIPPED.</li>
 * </ul>
 *
 * <p>The Core never modifies user source code automatically. Tests are only
 * written into a workspace the client explicitly provides via
 * {@code project.workspacePath}.</p>
 */
@Service
public class QaWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(QaWorkflowService.class);

    /**
     * Executor for the independent LLM steps (locators + test plan, bug report
     * while failure analysis runs). Virtual threads keep each HTTP call off the
     * request thread with no pooling overhead.
     */
    private final ExecutorService aiExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ProjectContextResolver contextResolver;
    private final ExplorerService explorerService;
    private final LocatorService locatorService;
    private final PlanningService planningService;
    private final CodeGenerationService codeGenerationService;
    private final ExecutionService executionService;
    private final FailureAnalysisService failureAnalysisService;
    private final com.qalab.qalabai.healing.service.HealingAnalysisService healingAnalysisService;
    private final BugReportService bugReportService;
    private final com.qalab.qalabai.service.report.ReportService reportService;
    private final OperationProgressStore progressStore;
    private final WorkspaceProvider workspaceProvider;

    public QaWorkflowService(ProjectContextResolver contextResolver,
                             ExplorerService explorerService,
                             LocatorService locatorService,
                             PlanningService planningService,
                             CodeGenerationService codeGenerationService,
                             ExecutionService executionService,
                             FailureAnalysisService failureAnalysisService,
                             com.qalab.qalabai.healing.service.HealingAnalysisService healingAnalysisService,
                             BugReportService bugReportService,
                             com.qalab.qalabai.service.report.ReportService reportService,
                             OperationProgressStore progressStore,
                             WorkspaceProvider workspaceProvider) {
        this.contextResolver = contextResolver;
        this.explorerService = explorerService;
        this.locatorService = locatorService;
        this.planningService = planningService;
        this.codeGenerationService = codeGenerationService;
        this.executionService = executionService;
        this.failureAnalysisService = failureAnalysisService;
        this.healingAnalysisService = healingAnalysisService;
        this.bugReportService = bugReportService;
        this.reportService = reportService;
        this.progressStore = progressStore;
        this.workspaceProvider = workspaceProvider;
    }

    public V1WorkflowResponse runFullTest(V1FullWorkflowRequest request) {
        ProjectContext project = contextResolver.resolve(request.project());
        String url = request.url();
        Long dbId = contextResolver.databaseId(request.project());
        String operationId = request.operationId() != null && !request.operationId().isBlank()
                ? request.operationId() : "op-" + UUID.randomUUID();
        Map<String, Object> steps = new LinkedHashMap<>();

        if (url == null || url.isBlank()) {
            throw com.qalab.qalabai.api.ApiException.invalidRequest("url is required");
        }

        OperationStatus finalStatus = OperationStatus.COMPLETED;
        progressStore.update(operationId, OperationStatus.RUNNING.name(), "STARTED", "starting full test workflow...");

        try {
            // 1. EXPLORE + ANALYZE (page capture and analysis are performed together)
            progressStore.update(operationId, OperationStatus.RUNNING.name(), "EXPLORING", "exploring app...");
            AnalysisResponse analysis = explorerService.analyze(
                    url, true, dbId, request.username(), request.password(), request.instruction());
            steps.put("explore", step("COMPLETED", Map.of("url", url, "pageType", analysis.pageType())));
            steps.put("analyze", step("COMPLETED", Map.of("url", url, "pageType", analysis.pageType())));

            // 2. + 3. LOCATORS and TEST PLAN (independent LLM steps, run in parallel)
            progressStore.update(operationId, OperationStatus.RUNNING.name(), "GENERATING_LOCATORS", "generating locators and test plan...");
            String instruction = UserInstructions.normalize(request.instruction());
            String testType = CodeGenerationService.normalizeTestType(request.testType());
            CompletableFuture<LocatorResponse> locatorsFuture = CompletableFuture.supplyAsync(
                    () -> locatorService.generateLocators(url, dbId), aiExecutor);
            CompletableFuture<TestPlanResponse> planFuture = CompletableFuture.supplyAsync(
                    () -> planningService.generateTestPlan(url, dbId, instruction), aiExecutor);
            LocatorResponse locators = locatorsFuture.join();
            TestPlanResponse plan = planFuture.join();
            steps.put("locators", step("COMPLETED", Map.of("generated", locators.generated())));
            steps.put("testPlan", step("COMPLETED", Map.of(
                    "scenarioCount", plan.scenarioCount(),
                    "scenarios", plan.scenarios()
            )));

            // 4. GENERATE TESTS. Generated exactly once: the same entities feed both the
            // response payload and the workspace write, so the specs the client receives
            // are the specs that actually ran. Previously this called the generator twice
            // (generateTestsContent here, generateTestsEntities inside runInWorkspace),
            // doubling cost and letting the two invocations diverge.
            progressStore.update(operationId, OperationStatus.RUNNING.name(), "GENERATING_TESTS", "generating tests...");
            // The credentials travel with the request instead of being read back out of
            // a URL-keyed cache, which used to hand one user's password to another
            // user's run (B-027).
            List<GeneratedTest> tests = codeGenerationService.generateTestsEntities(
                    url, dbId, instruction, testType, request.username(), request.password());
            steps.put("generatedTests", step("COMPLETED", Map.of(
                    "count", tests.size(),
                    "files", toGeneratedFiles(tests))));

            // 5. RUN (requires an explicit workspace — the Core never picks a directory
            // on the client's behalf).
            if (project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
                steps.put("execution", step("SKIPPED", Map.of(
                        "reason", "NO_WORKSPACE_PATH",
                        "detail", "Set project.workspacePath (CLI: QALAB_WORKSPACE or .qalab.json) "
                                + "to have the generated tests executed. The tests were still generated "
                                + "and are returned in this response.")));
                steps.put("failureAnalysis", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
                steps.put("healing", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
                steps.put("bugReport", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
            } else {
                progressStore.update(operationId, OperationStatus.RUNNING.name(), "RUNNING_TESTS", "running tests...");
                RunWithFiles executed = runInWorkspace(project, url, dbId, tests);
                Map<String, Object> run = executed.run();
                steps.put("execution", step("COMPLETED", run));

                // Replace the provisional payload with what was actually written,
                // including the page objects each spec imports. The client must
                // persist these verbatim or the specs will not compile.
                steps.put("generatedTests", step("COMPLETED", Map.of(
                        "count", executed.written().tests().size(),
                        "workspace", String.valueOf(executed.written().workspace()),
                        "files", executed.written().tests().stream()
                                .map(f -> new GeneratedFile(f.path(), f.content()))
                                .toList(),
                        "pageObjects", executed.written().pageObjects().stream()
                                .map(f -> new GeneratedFile(f.path(), f.content()))
                                .toList())));

                String execStatus = (String) run.get("executionStatus");

                if ("PASSED".equals(execStatus)) {
                    steps.put("failureAnalysis", step("SKIPPED", Map.of("reason", "TEST_PASSED")));
                    steps.put("healing", step("SKIPPED", Map.of("reason", "TEST_PASSED")));
                    steps.put("bugReport", step("SKIPPED", Map.of("reason", "TEST_PASSED")));
                } else {
                    // Failure analysis (+ healing) and the bug report are independent
                    // LLM steps; run them concurrently to shorten the critical path.
                    CompletableFuture<Map<String, Object>> bugReportFuture = CompletableFuture.supplyAsync(
                            () -> generateBugReport(dbId, run, request.instruction(), operationId), aiExecutor);
                    steps.putAll(analyzeFailure(dbId, run, operationId));
                    steps.putAll(bugReportFuture.join());
                }
            }

        } catch (Exception e) {
            log.error("Full QA workflow failed: {}", e.getMessage(), e);
            finalStatus = OperationStatus.FAILED;
            progressStore.update(operationId, OperationStatus.FAILED.name(), "FAILED",
                    "workflow failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            steps.put("workflow", step("FAILED", errorData));
        }

        progressStore.update(operationId, finalStatus.name(), "DONE",
                finalStatus == OperationStatus.COMPLETED ? "workflow completed" : "workflow failed");
        return new V1WorkflowResponse(operationId, finalStatus, project.getProjectId(), url, steps, LocalDateTime.now());
    }

    /**
     * How many trailing lines of raw Playwright output to keep for diagnostics.
     * The head used to be sent instead, which is where the generated test source
     * lives — so the interesting part (the failures) was cut off.
     */
    private static final int OUTPUT_TAIL_LINES = 50;

    static String tail(String value, int maxLines) {
        if (value == null) {
            return null;
        }
        String[] lines = value.split("\\R");
        if (lines.length <= maxLines) {
            return value;
        }
        StringBuilder sb = new StringBuilder("(output truncated, showing last " + maxLines + " lines)\n");
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /**
     * Extracts the structured run summary from the runner's result map.
     *
     * <p>Passes through the parser's own record so counts, per-test detail, screenshots,
     * videos and traces are available to the CLI and to every downstream report. When
     * the runner produced none — an older runner, or a workspace whose config could
     * not be extended — the key is simply absent rather than a misleading empty
     * summary claiming zero tests.</p>
     */
    private static Map<String, Object> structuredResults(Map<String, Object> result) {
        Object summary = result.get("summary");
        if (!(summary instanceof PlaywrightResultParser.RunSummary run)) {
            return null;
        }
        // Built explicitly rather than reflected, so the wire shape is a deliberate
        // contract and cannot shift with a field rename in the parser.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCount", run.total());
        out.put("passedCount", run.passed());
        out.put("failedCount", run.failed());
        out.put("skippedCount", run.skipped());
        out.put("flakyCount", run.flaky());
        out.put("durationMs", run.durationMs());
        out.put("globalErrors", run.globalErrors());

        List<Map<String, Object>> tests = new ArrayList<>();
        for (PlaywrightResultParser.TestCaseResult t : run.tests()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("file", t.file());
            one.put("title", t.title());
            one.put("fullTitle", t.fullTitle());
            one.put("status", t.status());
            one.put("durationMs", t.durationMs());
            one.put("retries", t.retries());
            if (t.errorMessage() != null) {
                one.put("error", t.errorMessage());
            }
            if (t.errorSnippet() != null) {
                one.put("snippet", t.errorSnippet());
            }
            if (!t.screenshots().isEmpty()) {
                one.put("screenshots", t.screenshots());
            }
            if (!t.videos().isEmpty()) {
                one.put("videos", t.videos());
            }
            if (!t.traces().isEmpty()) {
                one.put("traces", t.traces());
            }
            tests.add(one);
        }
        out.put("tests", tests);
        return out;
    }

    /** Execution outcome plus the exact set of files the write produced. */
    private record RunWithFiles(Map<String, Object> run, WorkspaceProvider.WriteResult written) {
    }

    private List<GeneratedFile> toGeneratedFiles(List<GeneratedTest> tests) {
        return tests.stream()
                .filter(t -> t.getTestCode() != null && !t.getTestCode().isBlank())
                .map(t -> new GeneratedFile(TestWorkspaceService.resolveFileName(t), t.getTestCode()))
                .toList();
    }

    private RunWithFiles runInWorkspace(ProjectContext project, String url, Long dbId,
                                        List<GeneratedTest> tests) {
        workspaceProvider.prepareWorkspace(project);
        WorkspaceProvider.WriteResult written = workspaceProvider.writeTestsAndReport(project, tests);
        Map<String, Object> result = workspaceProvider.execute(project, null, true);

        String status = (String) result.get("status");
        Long duration = result.get("duration") instanceof Number n ? n.longValue() : 0L;
        String output = (String) result.get("output");
        String error = (String) result.get("error");
        TestExecution record = executionService.recordExecution(dbId, "all", status, duration, output, error);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("executionId", record.getId());
        run.put("executionStatus", status);
        run.put("duration", duration);
        run.put("workspace", written.workspace());
        // Keep a bounded tail of the raw output for diagnostics, but the *structured*
        // per-test results are what callers should read. The 2000-character head that
        // used to be sent instead buried the failures in generated test source.
        run.put("output", tail(output, OUTPUT_TAIL_LINES));
        run.put("results", structuredResults(result));
        // The report files are written into the artifact directory, which is neither the
        // page URL nor a function of the execution id, so the paths have to be carried in
        // the response. Without them the report exists on disk and nothing points at it.
        if (record.getHtmlReportPath() != null && !record.getHtmlReportPath().isBlank()) {
            run.put("htmlReport", record.getHtmlReportPath());
        }
        if (record.getReportPath() != null && !record.getReportPath().isBlank()) {
            run.put("reportPath", record.getReportPath());
        }
        return new RunWithFiles(run, written);
    }

    private Map<String, Object> analyzeFailure(Long dbId, Map<String, Object> run, String operationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long executionId = (Long) run.get("executionId");
            progressStore.update(operationId, OperationStatus.RUNNING.name(), "ANALYZING_FAILURE", "analyzing failure...");
            FailureAnalysis analysis = failureAnalysisService.analyzeExecution(executionId, dbId);
            Map<String, Object> faData = new LinkedHashMap<>();
            faData.put("failureType", analysis.getFailureType());
            faData.put("summary", analysis.getSummary());
            faData.put("healingCandidate", Boolean.TRUE.equals(analysis.getHealingCandidate()));
            result.put("failureAnalysis", step("COMPLETED", faData));

            if (Boolean.TRUE.equals(analysis.getHealingCandidate())) {
                progressStore.update(operationId, OperationStatus.RUNNING.name(), "GENERATING_HEALING_SUGGESTION", "generating healing suggestion...");
                com.qalab.qalabai.healing.service.HealingOutcome healingOutcome =
                        healingAnalysisService.analyzeExecution(executionId, dbId);
                Map<String, Object> healingData = new LinkedHashMap<>();
                healingData.put("classification", healingOutcome.classification() != null
                        ? healingOutcome.classification().type() : "UNKNOWN");
                healingData.put("healingAttempted", healingOutcome.healingAttempted());
                healingData.put("message", healingOutcome.message());
                if (healingOutcome.proposal() != null) {
                    com.qalab.qalabai.healing.model.HealingProposal p = healingOutcome.proposal();
                    healingData.put("proposalId", p.getProposalId());
                    healingData.put("originalLocator", p.getOriginalLocator());
                    healingData.put("recommendedLocator", p.getRecommendedLocator());
                    healingData.put("confidence", p.getConfidence());
                    healingData.put("confidenceLabel", p.getConfidenceLabel());
                    healingData.put("safeToApply", p.getSafeToApply());
                    healingData.put("proposalStatus", p.getStatus());
                }
                result.put("healing", step("COMPLETED", healingData));
            } else {
                result.put("healing", step("SKIPPED", Map.of("reason", "NOT_HEALING_CANDIDATE")));
            }
        } catch (Exception e) {
            log.warn("Failure analysis/healing step failed: {}", e.getMessage());
            result.put("failureAnalysis", step("FAILED", Map.of("error", String.valueOf(e.getMessage()))));
            result.put("healing", step("SKIPPED", Map.of("reason", "ANALYSIS_FAILED")));
        }
        return result;
    }

    private Map<String, Object> generateBugReport(Long dbId, Map<String, Object> run, String instruction, String operationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Long executionId = (Long) run.get("executionId");
            progressStore.update(operationId, OperationStatus.RUNNING.name(), "GENERATING_BUG_REPORT", "generating bug report...");
            // One report per distinct failure, not one per run. A run with three
            // different failures is three bugs, and returning only the first would hide
            // the other two from the user (B-032).
            List<com.qalab.qalabai.model.BugReport> reports =
                    bugReportService.generateAll(executionId, dbId, instruction);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reportCount", reports.size());
            if (reports == null || reports.isEmpty()) {
                // Nothing failed, or nothing could be attributed. Saying so is useful;
                // an IndexOutOfBounds here would fail the whole workflow's report step.
                result.put("bugReport", step("COMPLETED", data));
                log.info("No bug report generated for execution {}: no distinct failure to report", executionId);
                return result;
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (com.qalab.qalabai.model.BugReport report : reports) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("reportId", report.getReportId());
                one.put("title", report.getTitle());
                one.put("severity", report.getSeverity());
                one.put("failureType", report.getFailureType());
                one.put("summary", report.getSummary());
                one.put("occurrences", report.getOccurrences());
                if (report.getScreenshotPath() != null) {
                    one.put("screenshot", report.getScreenshotPath());
                }
                entries.add(one);
            }
            data.put("reports", entries);
            // The first report stays at the top level so existing consumers keep working.
            com.qalab.qalabai.model.BugReport first = reports.get(0);
            data.put("reportId", first.getReportId());
            data.put("reportStatus", first.getStatus());
            data.put("title", first.getTitle());
            data.put("severity", first.getSeverity());
            data.put("summary", first.getSummary());
            result.put("bugReport", step("COMPLETED", data));

            // The HTML report was written during the execution step, before these bug
            // reports existed. Rewrite it so the file a user opens contains the most
            // actionable section, rather than leaving it permanently without one.
            try {
                com.qalab.qalabai.model.TestExecution execution =
                        executionService.getExecution(executionId);
                if (execution != null) {
                    Map<String, Object> extras = new LinkedHashMap<>();
                    extras.put("bugReports", entries);
                    executionService.reRenderReport(execution, extras);
                }
            } catch (Exception e) {
                log.debug("Could not re-render the report with bug reports: {}", e.getMessage());
            }

            log.info("{} bug report(s) generated for execution {}", reports.size(), executionId);
        } catch (Exception e) {
            log.warn("Bug report step failed: {}", e.getMessage());
            result.put("bugReport", step("FAILED", Map.of("error", String.valueOf(e.getMessage()))));
        }
        return result;
    }

    private Map<String, Object> step(String status, Map<String, Object> data) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("status", status);
        step.putAll(data);
        return step;
    }
}
