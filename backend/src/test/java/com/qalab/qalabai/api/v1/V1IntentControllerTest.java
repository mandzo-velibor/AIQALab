package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest;
import com.qalab.qalabai.api.v1.dto.V1IntentRequest;
import com.qalab.qalabai.api.v1.dto.V1IntentResponse;
import com.qalab.qalabai.api.v1.dto.ProjectInfo;
import com.qalab.qalabai.agent.ProjectContext;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for a bug that survived every sprint until it was written down.
 *
 * <p>{@code /intent/run} used the user's prompt <em>only</em> to work out which operation
 * to run, then threw the prompt away. A user who typed "generate tests for the login
 * page, focus on the red border" got generic tests and nothing telling them why — the
 * same class of defect as B-003, on the entry point nobody exercised.</p>
 *
 * <p>The request also had no field for credentials, so the path could not pass them even
 * in principle: a run against a login page could not explore past the form.</p>
 */
class V1IntentControllerTest {

    private V1IntentController controller;
    private IntentService intentService;
    private ExplorerService explorerService;
    private PlanningService planningService;
    private CodeGenerationService codeGenerationService;
    private ExecutionService executionService;
    private QaWorkflowService workflowService;
    private ProjectContextResolver contextResolver;

    @BeforeEach
    void setUp() {
        contextResolver = mock(ProjectContextResolver.class);
        intentService = mock(IntentService.class);
        explorerService = mock(ExplorerService.class);
        planningService = mock(PlanningService.class);
        codeGenerationService = mock(CodeGenerationService.class);
        executionService = mock(ExecutionService.class);
        workflowService = mock(QaWorkflowService.class);

        ProjectContext project = new ProjectContext();
        project.setProjectId("demo");
        project.setProjectName("demo");
        project.setBaseUrl("https://app.example.com/login");
        project.setWorkspacePath("/tmp/demo");
        when(contextResolver.resolve(any())).thenReturn(project);
        when(contextResolver.databaseId(any())).thenReturn(7L);

        when(explorerService.analyze(anyString(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(emptyAnalysis());
        when(planningService.generateTestPlan(anyString(), any(), any()))
                .thenReturn(new TestPlanResponse(0, List.of(), null));
        when(codeGenerationService.generateTestsContent(anyString(), any(), any(), any()))
                .thenReturn(List.<GeneratedFile>of());
        when(codeGenerationService.generateTestsEntities(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(List.<com.qalab.qalabai.model.GeneratedTest>of());
        when(workflowService.runFullTest(any())).thenReturn(null);

        controller = new V1IntentController(contextResolver, intentService, explorerService,
                planningService, codeGenerationService, executionService, workflowService);
    }

    private void detects(Intent intent) {
        when(intentService.detect(anyString()))
                .thenReturn(new IntentResult(intent, List.of(), List.of(), "prompt"));
    }

    private static AnalysisResponse emptyAnalysis() {
        return new AnalysisResponse("login", "https://app.example.com/login", 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private ResponseEntity<V1IntentResponse> run(V1IntentRequest request) {
        return controller.detectAndRun(request);
    }

    private static V1IntentRequest request(String prompt) {
        return new V1IntentRequest(new ProjectInfo("demo", "https://app.example.com/login",
                null, null, null, null, 7L), prompt, "https://app.example.com/login");
    }

    // ---- the instruction reaches the operation ----

    @Test
    void thePromptReachesExplorationAsAnInstruction() {
        detects(Intent.EXPLORE);
        String prompt = "explore the login page, focus on the red border on submit";

        run(request(prompt));

        verify(explorerService).analyze(anyString(), anyBoolean(), any(), any(), any(), eq(prompt));
    }

    @Test
    void thePromptReachesTheTestPlan() {
        detects(Intent.TEST_PLAN);
        String prompt = "plan tests, only the checkout flow";

        run(request(prompt));

        verify(planningService).generateTestPlan(anyString(), any(), eq(prompt));
    }

    @Test
    void thePromptReachesTestGeneration() {
        detects(Intent.GENERATE_TESTS);
        String prompt = "generate tests, focus on the username hint and red border";

        run(request(prompt));

        verify(codeGenerationService).generateTestsEntities(
                anyString(), any(), eq(prompt), any(), any(), any());
    }

    @Test
    void thePromptReachesTestExecution() {
        detects(Intent.RUN_TESTS);

        run(request("run the tests and report what broke"));

        verify(executionService).runAllTests(eq(7L), anyBoolean(), any(), anyString());
    }

    @Test
    void thePromptReachesTheFullWorkflow() {
        detects(Intent.FULL_TEST);
        String prompt = "run everything, prioritise the login flow";

        run(request(prompt));

        org.mockito.ArgumentCaptor<V1FullWorkflowRequest> captor =
                org.mockito.ArgumentCaptor.forClass(V1FullWorkflowRequest.class);
        verify(workflowService).runFullTest(captor.capture());

        assertEquals(prompt, captor.getValue().instruction(),
                "the full workflow must receive the user's instruction");
    }

    // ---- credentials can now be carried at all ----

    @Test
    void credentialsReachExploration() {
        detects(Intent.EXPLORE);

        run(new V1IntentRequest(new ProjectInfo("demo", "https://app.example.com/login",
                null, null, null, null, 7L),
                "explore behind the login form", "https://app.example.com/login",
                "alice", "alice-secret", null));

        verify(explorerService).analyze(anyString(), anyBoolean(), any(),
                eq("alice"), eq("alice-secret"), anyString());
    }

    @Test
    void credentialsReachTheFullWorkflow() {
        detects(Intent.FULL_TEST);

        run(new V1IntentRequest(new ProjectInfo("demo", "https://app.example.com/login",
                null, null, null, null, 7L),
                "test the login flow", "https://app.example.com/login",
                "alice", "alice-secret", "ui"));

        org.mockito.ArgumentCaptor<V1FullWorkflowRequest> captor =
                org.mockito.ArgumentCaptor.forClass(V1FullWorkflowRequest.class);
        verify(workflowService).runFullTest(captor.capture());

        assertEquals("alice", captor.getValue().username());
        assertEquals("alice-secret", captor.getValue().password());
        assertEquals("ui", captor.getValue().testType());
    }

    @Test
    void generationThroughIntentPersistsTheTests() {
        // Generating through the intent route used to leave no GeneratedTest rows, so the
        // tests existed only in the response: nothing to run afterwards, no execution
        // history and no healing. The full-test route has always persisted.
        detects(Intent.GENERATE_TESTS);

        run(request("generate tests for the login page"));

        verify(codeGenerationService).generateTestsEntities(anyString(), any(), any(), any(), any(), any());
        verify(codeGenerationService, org.mockito.Mockito.never())
                .generateTestsContent(anyString(), any(), any(), any());
    }

    // ---- the structured filter still goes through the shared resolver ----

    @Test
    void aTextualTestTypeIsResolvedTheSameWayAsEveryOtherEntryPoint() {
        detects(Intent.GENERATE_TESTS);

        run(new V1IntentRequest(new ProjectInfo("demo", "https://app.example.com/login",
                null, null, null, null, 7L),
                "generate api tests", "https://app.example.com/login",
                null, null, "api"));

        // Lower case: that is the resolver's canonical form, the same one the CLI and
        // the workflow already produce.
        verify(codeGenerationService).generateTestsEntities(anyString(), any(), any(), eq("api"), any(), any());
    }

    // ---- additive contract ----

    @Test
    void theOriginalThreeFieldRequestStillWorks() {
        detects(Intent.EXPLORE);

        ResponseEntity<V1IntentResponse> response = run(request("explore the page"));

        assertNotNull(response.getBody());
        assertEquals(OperationStatus.COMPLETED, response.getBody().status());
        // No credentials supplied means none are passed, not empty strings.
        verify(explorerService).analyze(anyString(), anyBoolean(), any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), anyString());
    }

    @Test
    void aRequestWithNoCredentialsReportsThemAsAbsent() {
        detects(Intent.EXPLORE);

        run(request("explore the page"));

        // The important half: it must not invent credentials or pass empty strings that
        // a login attempt would then treat as real.
        verify(explorerService).analyze(anyString(), anyBoolean(), any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), anyString());
    }

    // ---- detection-only endpoint unchanged ----

    @Test
    void detectOnlyDoesNotRunAnything() {
        detects(Intent.EXPLORE);

        controller.detect(request("explore the page"));

        verify(explorerService, org.mockito.Mockito.never())
                .analyze(anyString(), anyBoolean(), any(), any(), any(), any());
    }
}
