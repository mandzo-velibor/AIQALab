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
import com.qalab.qalabai.model.HealingSuggestion;
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

    private final ProjectContextResolver contextResolver;
    private final ExplorerService explorerService;
    private final LocatorService locatorService;
    private final PlanningService planningService;
    private final CodeGenerationService codeGenerationService;
    private final ExecutionService executionService;
    private final FailureAnalysisService failureAnalysisService;
    private final HealingService healingService;
    private final WorkspaceProvider workspaceProvider;

    public QaWorkflowService(ProjectContextResolver contextResolver,
                             ExplorerService explorerService,
                             LocatorService locatorService,
                             PlanningService planningService,
                             CodeGenerationService codeGenerationService,
                             ExecutionService executionService,
                             FailureAnalysisService failureAnalysisService,
                             HealingService healingService,
                             WorkspaceProvider workspaceProvider) {
        this.contextResolver = contextResolver;
        this.explorerService = explorerService;
        this.locatorService = locatorService;
        this.planningService = planningService;
        this.codeGenerationService = codeGenerationService;
        this.executionService = executionService;
        this.failureAnalysisService = failureAnalysisService;
        this.healingService = healingService;
        this.workspaceProvider = workspaceProvider;
    }

    public V1WorkflowResponse runFullTest(V1FullWorkflowRequest request) {
        ProjectContext project = contextResolver.resolve(request.project());
        String url = request.url();
        Long dbId = contextResolver.databaseId(request.project());
        String operationId = "op-" + UUID.randomUUID();
        Map<String, Object> steps = new LinkedHashMap<>();

        if (url == null || url.isBlank()) {
            throw com.qalab.qalabai.api.ApiException.invalidRequest("url is required");
        }

        OperationStatus finalStatus = OperationStatus.COMPLETED;

        try {
            // 1. EXPLORE + ANALYZE (page capture and analysis are performed together)
            AnalysisResponse analysis = explorerService.analyze(url, true, dbId, request.username(), request.password());
            steps.put("explore", step("COMPLETED", Map.of("url", url, "pageType", analysis.pageType())));
            steps.put("analyze", step("COMPLETED", Map.of("url", url, "pageType", analysis.pageType())));

            // 2. LOCATORS
            LocatorResponse locators = locatorService.generateLocators(url, dbId);
            steps.put("locators", step("COMPLETED", Map.of("generated", locators.generated())));

            // 3. TEST PLAN
            TestPlanResponse plan = planningService.generateTestPlan(url, dbId);
            steps.put("testPlan", step("COMPLETED", Map.of("scenarioCount", plan.scenarioCount())));

            // 4. GENERATE TESTS (content returned, never auto-written into the Core)
            List<GeneratedFile> files = codeGenerationService.generateTestsContent(url, dbId);
            steps.put("generatedTests", step("COMPLETED", Map.of("count", files.size(), "files", files)));

            // 5. RUN (requires explicit workspace)
            if (project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
                steps.put("execution", step("SKIPPED", Map.of("reason", "NO_WORKSPACE_PATH")));
                steps.put("failureAnalysis", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
                steps.put("healing", step("SKIPPED", Map.of("reason", "NO_EXECUTION")));
            } else {
                Map<String, Object> run = runInWorkspace(project, url, dbId, files);
                steps.put("execution", step("COMPLETED", run));
                String execStatus = (String) run.get("executionStatus");

                if ("PASSED".equals(execStatus)) {
                    steps.put("failureAnalysis", step("SKIPPED", Map.of("reason", "TEST_PASSED")));
                    steps.put("healing", step("SKIPPED", Map.of("reason", "TEST_PASSED")));
                } else if (dbId == null) {
                    steps.put("failureAnalysis", step("SKIPPED", Map.of("reason", "NO_REGISTERED_PROJECT")));
                    steps.put("healing", step("SKIPPED", Map.of("reason", "NO_REGISTERED_PROJECT")));
                } else {
                    analyzeFailure(steps, dbId, run);
                }
            }

        } catch (Exception e) {
            log.error("Full QA workflow failed: {}", e.getMessage(), e);
            finalStatus = OperationStatus.FAILED;
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            steps.put("workflow", step("FAILED", errorData));
        }

        return new V1WorkflowResponse(operationId, finalStatus, project.getProjectId(), url, steps, LocalDateTime.now());
    }

    private Map<String, Object> runInWorkspace(ProjectContext project, String url, Long dbId, List<GeneratedFile> files) {
        List<GeneratedTest> entities = codeGenerationService.generateTestsEntities(url, dbId);
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

    private void analyzeFailure(Map<String, Object> steps, Long dbId, Map<String, Object> run) {
        try {
            Long executionId = (Long) run.get("executionId");
            FailureAnalysis analysis = failureAnalysisService.analyzeExecution(executionId, dbId);
            Map<String, Object> faData = new LinkedHashMap<>();
            faData.put("failureType", analysis.getFailureType());
            faData.put("summary", analysis.getSummary());
            faData.put("healingCandidate", Boolean.TRUE.equals(analysis.getHealingCandidate()));
            steps.put("failureAnalysis", step("COMPLETED", faData));

            if (Boolean.TRUE.equals(analysis.getHealingCandidate())) {
                HealingSuggestion suggestion = healingService.generateHealingSuggestion(executionId);
                Map<String, Object> healingData = new LinkedHashMap<>();
                healingData.put("elementName", suggestion.getElementName());
                healingData.put("oldLocator", suggestion.getOldLocator());
                healingData.put("newLocator", suggestion.getNewLocator());
                healingData.put("confidence", suggestion.getConfidence());
                steps.put("healing", step("COMPLETED", healingData));
            } else {
                steps.put("healing", step("SKIPPED", Map.of("reason", "NOT_HEALING_CANDIDATE")));
            }
        } catch (Exception e) {
            log.warn("Failure analysis/healing step failed: {}", e.getMessage());
            steps.put("failureAnalysis", step("FAILED", Map.of("error", String.valueOf(e.getMessage()))));
            steps.put("healing", step("SKIPPED", Map.of("reason", "ANALYSIS_FAILED")));
        }
    }

    private Map<String, Object> step(String status, Map<String, Object> data) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("status", status);
        step.putAll(data);
        return step;
    }
}
