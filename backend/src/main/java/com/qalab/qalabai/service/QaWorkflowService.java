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
import com.qalab.qalabai.service.workspace.WorkspaceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
            CompletableFuture<LocatorResponse> locatorsFuture = CompletableFuture.supplyAsync(
                    () -> locatorService.generateLocators(url, dbId), aiExecutor);
            CompletableFuture<TestPlanResponse> planFuture = CompletableFuture.supplyAsync(
                    () -> planningService.generateTestPlan(url, dbId), aiExecutor);
            LocatorResponse locators = locatorsFuture.join();
            TestPlanResponse plan = planFuture.join();
            steps.put("locators", step("COMPLETED", Map.of("generated", locators.generated())));
            steps.put("testPlan", step("COMPLETED", Map.of(
                    "scenarioCount", plan.scenarioCount(),
                    "scenarios", plan.scenarios()
            )));

            // 4. GENERATE TESTS (content returned, never auto-written into the Core)
            progressStore.update(operationId, OperationStatus.RUNNING.name(), "GENERATING_TESTS", "generating tests...");
            List<GeneratedFile> files = codeGenerationService.generateTestsContent(url, dbId);
            steps.put("generatedTests", step("COMPLETED", Map.of("count", files.size(), "files", files)));

            // 5. RUN (requires explicit workspace)
            if (project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
                steps.put("execution", step("SKIPPED", Map.of("reason", "NO_WORKSPACE_PATH")));
                steps.put("failureAnalysis", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
                steps.put("healing", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
                steps.put("bugReport", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
            } else {
                progressStore.update(operationId, OperationStatus.RUNNING.name(), "RUNNING_TESTS", "running tests...");
                Map<String, Object> run = runInWorkspace(project, url, dbId, files);
                steps.put("execution", step("COMPLETED", run));
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

    private Map<String, Object> runInWorkspace(ProjectContext project, String url, Long dbId, List<GeneratedFile> files) {
        List<GeneratedTest> entities = codeGenerationService.generateTestsEntities(url, dbId);
        workspaceProvider.prepareWorkspace(project);
        workspaceProvider.writeTests(project, entities);
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
        run.put("output", output != null && output.length() > 2000 ? output.substring(0, 2000) : output);
        return run;
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
            com.qalab.qalabai.model.BugReport report = bugReportService.generate(executionId, dbId, instruction);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reportId", report.getReportId());
            data.put("reportStatus", report.getStatus());
            data.put("title", report.getTitle());
            data.put("severity", report.getSeverity());
            data.put("summary", report.getSummary());
            result.put("bugReport", step("COMPLETED", data));
            log.info("Bug report {} generated for execution {}", report.getReportId(), executionId);
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
