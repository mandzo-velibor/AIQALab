package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1IntentRequest;
import com.qalab.qalabai.api.v1.dto.V1IntentResponse;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.planner.TestPlanResponse;
import com.qalab.qalabai.dto.testgen.GeneratedFile;
import com.qalab.qalabai.intent.Intent;
import com.qalab.qalabai.intent.IntentResult;
import com.qalab.qalabai.intent.IntentService;
import com.qalab.qalabai.service.CodeGenerationService;
import com.qalab.qalabai.service.ExecutionService;
import com.qalab.qalabai.service.ExplorerService;
import com.qalab.qalabai.service.PlanningService;
import com.qalab.qalabai.service.ProjectContextResolver;
import com.qalab.qalabai.service.QaWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Natural-language entry point. {@code /intent} only detects what the user
 * wants; {@code /intent/run} detects and then dispatches to the matching
 * workflow operation.
 */
@RestController
@RequestMapping("/api/v1/intent")
public class V1IntentController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1IntentController.class);

    private final IntentService intentService;
    private final ExplorerService explorerService;
    private final PlanningService planningService;
    private final CodeGenerationService codeGenerationService;
    private final ExecutionService executionService;
    private final QaWorkflowService workflowService;

    public V1IntentController(ProjectContextResolver contextResolver,
                              IntentService intentService,
                              ExplorerService explorerService,
                              PlanningService planningService,
                              CodeGenerationService codeGenerationService,
                              ExecutionService executionService,
                              QaWorkflowService workflowService) {
        super(contextResolver);
        this.intentService = intentService;
        this.explorerService = explorerService;
        this.planningService = planningService;
        this.codeGenerationService = codeGenerationService;
        this.executionService = executionService;
        this.workflowService = workflowService;
    }

    @PostMapping
    public ResponseEntity<V1IntentResponse> detect(@RequestBody V1IntentRequest request) {
        IntentResult result = detectOrThrow(request);
        return ResponseEntity.ok(new V1IntentResponse(
                operationId(), OperationStatus.COMPLETED, result.intent(),
                request.url(), result.steps(), result.matchedKeywords(), LocalDateTime.now()));
    }

    @PostMapping("/run")
    public ResponseEntity<V1IntentResponse> detectAndRun(@RequestBody V1IntentRequest request) {
        IntentResult result = detectOrThrow(request);
        if (result.intent() == Intent.UNKNOWN) {
            throw ApiException.invalidRequest(
                    "Could not understand the request. Include an action like \"explore\", \"generate tests\" or \"run tests\".");
        }
        if (request.url() == null || request.url().isBlank()) {
            throw ApiException.invalidRequest("url is required to execute intent " + result.intent());
        }

        String operationId = operationId();
        ProjectContext project = project(request.project());
        Long dbId = databaseId(request.project());
        log.info("POST /api/v1/intent/run operationId={} intent={} url={}", operationId, result.intent(), request.url());

        dispatch(result.intent(), request.url(), dbId, project);

        return ResponseEntity.ok(new V1IntentResponse(
                operationId, OperationStatus.COMPLETED, result.intent(),
                request.url(), result.steps(), result.matchedKeywords(), LocalDateTime.now()));
    }

    private void dispatch(Intent intent, String url, Long dbId, ProjectContext project) {
        switch (intent) {
            case EXPLORE -> {
                AnalysisResponse analysis = explorerService.analyze(url, true, dbId, null, null);
                log.info("Intent EXPLORE done for {} pageType={}", url, analysis.pageType());
            }
            case TEST_PLAN -> {
                TestPlanResponse plan = planningService.generateTestPlan(url, dbId);
                log.info("Intent TEST_PLAN done for {} scenarios={}", url, plan.scenarioCount());
            }
            case GENERATE_TESTS -> {
                List<GeneratedFile> files = codeGenerationService.generateTestsContent(url, dbId);
                log.info("Intent GENERATE_TESTS done for {} files={}", url, files.size());
            }
            case RUN_TESTS -> {
                if (dbId == null) {
                    throw ApiException.invalidRequest("run tests requires a registered project (databaseId)");
                }
                executionService.runAllTests(dbId);
                log.info("Intent RUN_TESTS done for project {}", dbId);
            }
            case FULL_TEST -> {
                com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest full =
                        new com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest(
                                new com.qalab.qalabai.api.v1.dto.ProjectInfo(
                                        project.getProjectId(), project.getProjectName(), project.getBaseUrl(),
                                        project.getFramework(), project.getLanguage(), project.getWorkspacePath(),
                                        dbId),
                                url, null, null, null, null);
                workflowService.runFullTest(full);
                log.info("Intent FULL_TEST done for {}", url);
            }
            default -> throw ApiException.invalidRequest("Intent " + intent + " is not executable with these inputs");
        }
    }

    private IntentResult detectOrThrow(V1IntentRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw ApiException.invalidRequest("prompt is required");
        }
        return intentService.detect(request.prompt());
    }

    private org.springframework.context.ApplicationContext applicationContext() {
        return org.springframework.web.context.ContextLoader.getCurrentWebApplicationContext();
    }
}
