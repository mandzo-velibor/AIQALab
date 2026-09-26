package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.ProjectInfo;
import com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest;
import com.qalab.qalabai.api.v1.dto.V1WorkflowResponse;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.dto.planner.TestPlanResponse;
import com.qalab.qalabai.dto.testgen.GeneratedFile;
import com.qalab.qalabai.healing.model.FailureContext;
import com.qalab.qalabai.healing.model.HealingProposal;
import com.qalab.qalabai.healing.service.HealingAnalysisService;
import com.qalab.qalabai.healing.service.HealingOutcome;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.model.BugReport;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import com.qalab.qalabai.service.workspace.WorkspaceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QaWorkflowServiceTest {

    private final ProjectContextResolver contextResolver = mock(ProjectContextResolver.class);
    private final ExplorerService explorerService = mock(ExplorerService.class);
    private final LocatorService locatorService = mock(LocatorService.class);
    private final PlanningService planningService = mock(PlanningService.class);
    private final CodeGenerationService codeGenerationService = mock(CodeGenerationService.class);
    private final ExecutionService executionService = mock(ExecutionService.class);
    private final FailureAnalysisService failureAnalysisService = mock(FailureAnalysisService.class);
    private final HealingAnalysisService healingAnalysisService = mock(HealingAnalysisService.class);
    private final BugReportService bugReportService = mock(BugReportService.class);
    private final com.qalab.qalabai.service.report.ReportService reportService =
            mock(com.qalab.qalabai.service.report.ReportService.class);
    private final OperationProgressStore progressStore = mock(OperationProgressStore.class);
    private final WorkspaceProvider workspaceProvider = mock(WorkspaceProvider.class);

    private QaWorkflowService workflow;

    private ProjectInfo info;
    private ProjectContext project;

    @BeforeEach
    void setUp() {
        workflow = new QaWorkflowService(contextResolver, explorerService, locatorService,
                planningService, codeGenerationService, executionService,
                failureAnalysisService, healingAnalysisService, bugReportService,
                reportService, progressStore, workspaceProvider);

        info = ProjectInfo.of("internet-tests", "https://the-internet.herokuapp.com/login", "PLAYWRIGHT", "TYPESCRIPT");
        project = new ProjectContext();
        project.setProjectId("internet-tests");
        project.setBaseUrl("https://the-internet.herokuapp.com/login");
        when(contextResolver.resolve(info)).thenReturn(project);
        when(contextResolver.databaseId(info)).thenReturn(null);

        when(explorerService.analyze(any(), anyBoolean(), any(), any(), any(), any())).thenReturn(
                new AnalysisResponse("LOGIN", "summary", 95, null, null, null, null, null, null, null, null));
        when(locatorService.generateLocators(any(), any())).thenReturn(new LocatorResponse(0, List.of(), null, List.of()));
        when(planningService.generateTestPlan(any(), any(), any())).thenReturn(new TestPlanResponse(0, List.of(), null));
        when(codeGenerationService.generateTestsEntities(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            GeneratedTest t = new GeneratedTest();
            t.setScenarioName("Successful login");
            t.setTestCode("test('x', async () => {});");
            t.setPageObjectCode("export class LoginPage {}");
            return List.of(t);
        });
        // Default: report the spec back so the workflow can build its response.
        when(workspaceProvider.writeTestsAndReport(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GeneratedTest> tests = inv.getArgument(1);
            return new WorkspaceProvider.WriteResult(
                    workspaceProvider.getWorkspace(inv.getArgument(0)),
                    tests.stream()
                            .filter(t -> t.getTestCode() != null && !t.getTestCode().isBlank())
                            .map(t -> new WorkspaceProvider.WrittenFile(
                                    TestWorkspaceService.resolveFileName(t), t.getTestCode()))
                            .toList(),
                    List.of());
        });
    }

    @Test
    void skipsExecutionWhenNoWorkspacePath() {
        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null, null, null));

        assertEquals(OperationStatus.COMPLETED, response.status());
        assertStepStatus(response, "explore", "COMPLETED");
        assertStepStatus(response, "analyze", "COMPLETED");
        assertStepStatus(response, "locators", "COMPLETED");
        assertStepStatus(response, "testPlan", "COMPLETED");
        assertStepStatus(response, "generatedTests", "COMPLETED");
        assertStepStatus(response, "execution", "SKIPPED");
        assertStepStatus(response, "failureAnalysis", "SKIPPED");
        assertStepStatus(response, "healing", "SKIPPED");

        verify(workspaceProvider, never()).execute(any(), any(), anyBoolean());
        verify(executionService, never()).recordExecution(any(), any(), any(), any(), any(), any());
    }

    @Test
    void generatesTheTestSuiteExactlyOnce() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "PASSED", "duration", 10L, "output", "ok"));
        TestExecution record = new TestExecution();
        record.setId(7L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any())).thenReturn(record);

        workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login",
                null, null, null, null, null));

        // Regression guard: the generator used to be invoked twice per run
        // (generateTestsContent for the response, generateTestsEntities for the
        // workspace), which doubled cost and let the two invocations diverge.
        verify(codeGenerationService, org.mockito.Mockito.times(1))
                .generateTestsEntities(any(), any(), any(), any(), any(), any());
        verify(codeGenerationService, never()).generateTestsContent(any(), any());
    }

    @Test
    void propagatesUserInstructionAndTestTypeToPlanningAndGeneration() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "PASSED", "duration", 10L, "output", "ok"));
        TestExecution record = new TestExecution();
        record.setId(7L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any())).thenReturn(record);

        workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login",
                null, null, "focus on the username hint and red border", null, "ui"));

        // Regression guard: the instruction used to reach only page analysis, so a
        // user's --instruction was silently dropped from planning and generation.
        verify(planningService).generateTestPlan(any(), any(), eq("focus on the username hint and red border"));
        verify(codeGenerationService).generateTestsEntities(
                any(), any(), eq("focus on the username hint and red border"), eq("ui"),
                any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesTheRequestCredentialsToGenerationInsteadOfRelyingOnACache() {
        // The credentials used to be cached by ExplorerService under a hash of the URL
        // and read back by CodeGenerationService. Because the key was the URL and not
        // the request, an anonymous run against a previously-visited URL picked up the
        // previous user's password. They now travel with the request (B-027).
        var request = new V1FullWorkflowRequest(info, "https://app.example.com/login",
                "alice", "alice-secret", null, null, "ui");
        when(codeGenerationService.generateTestsEntities(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        try {
            workflow.runFullTest(request);
        } catch (RuntimeException ignored) {
            // The workflow may fail later for unrelated reasons; the assertion below is
            // about how generation was called, not about the run succeeding.
        }

        verify(codeGenerationService).generateTestsEntities(
                any(), any(), any(), any(), eq("alice"), eq("alice-secret"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shipsPageObjectsToTheClientAlongsideTheSpecs() {
        project.setWorkspacePath("/home/dev/internet-tests");
        org.mockito.Mockito.doReturn(
                new WorkspaceProvider.WriteResult("/home/dev/internet-tests",
                        List.of(new WorkspaceProvider.WrittenFile("tests/login.spec.ts",
                                "import { LoginPage } from '../pages/LoginPage_login';")),
                        List.of(new WorkspaceProvider.WrittenFile("pages/LoginPage_login.ts",
                                "export class LoginPage {}"),
                                new WorkspaceProvider.WrittenFile("pages/LoginPage.ts",
                                "export class LoginPage {}"))))
                .when(workspaceProvider).writeTestsAndReport(any(), any());
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "PASSED", "duration", 10L, "output", "ok"));
        TestExecution record = new TestExecution();
        record.setId(7L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any())).thenReturn(record);

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info,
                "https://the-internet.herokuapp.com/login", null, null, null, null, null));

        // Regression guard: the response used to advertise spec sources only, while
        // the page objects those specs import were written independently. A client
        // that trusted the response produced a tree that could not compile.
        Map<String, Object> generated = (Map<String, Object>) response.steps().get("generatedTests");
        List<GeneratedFile> pageObjects = (List<GeneratedFile>) generated.get("pageObjects");
        assertEquals(2, pageObjects.size(), "page objects must be reported: " + generated);
        assertTrue(pageObjects.stream().anyMatch(f -> f.path().equals("pages/LoginPage_login.ts")));
        assertEquals("/home/dev/internet-tests", generated.get("workspace"));

        Map<String, Object> exec = (Map<String, Object>) response.steps().get("execution");
        assertEquals("/home/dev/internet-tests", exec.get("workspace"),
                "the response must echo the workspace that was actually used");
    }

    @Test
    void runsInWorkspaceAndSkipsFailureAnalysisWhenTestsPass() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(codeGenerationService.generateTestsEntities(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "PASSED", "duration", 1200L, "output", "ok"));
        TestExecution record = new TestExecution();
        record.setId(7L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any())).thenReturn(record);

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null, null, null));

        assertEquals(OperationStatus.COMPLETED, response.status());
        assertStepStatus(response, "execution", "COMPLETED");
        assertStepStatus(response, "failureAnalysis", "SKIPPED");
        assertStepStatus(response, "healing", "SKIPPED");
        verify(workspaceProvider).writeTestsAndReport(any(), any());
        verify(workspaceProvider).execute(any(), any(), eq(true));
    }

    @Test
    void analyzesFailureAndGeneratesHealingCandidate() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(contextResolver.databaseId(info)).thenReturn(3L);
        when(codeGenerationService.generateTestsEntities(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "FAILED", "duration", 500L, "output", "timeout", "error", "locator not found"));
        TestExecution record = new TestExecution();
        record.setId(10L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any())).thenReturn(record);
        stubBugReport(10L);

        FailureAnalysis analysis = new FailureAnalysis();
        analysis.setFailureType("LOCATOR_INVALID");
        analysis.setSummary("locator broken");
        analysis.setHealingCandidate(true);
        when(failureAnalysisService.analyzeExecution(eq(10L), eq(3L))).thenReturn(analysis);

        HealingProposal proposal = new HealingProposal();
        proposal.setProposalId("prop-10");
        proposal.setOriginalLocator("#login");
        proposal.setRecommendedLocator("getByRole('button', { name: 'Login' })");
        proposal.setConfidence(0.9);
        proposal.setConfidenceLabel("HIGH");
        proposal.setSafeToApply(true);
        proposal.setStatus("PROPOSED");
        FailureContext context = new FailureContext();
        context.setProjectId(3L);
        HealingOutcome outcome = new HealingOutcome(
                context,
                new HealingOutcome.FailureClassificationView("LOCATOR_FAILURE", 0.9, "locator broken"),
                List.of(),
                proposal,
                true,
                "locator repaired");
        when(healingAnalysisService.analyzeExecution(eq(10L), eq(3L))).thenReturn(outcome);

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null, null, null));

        assertEquals(OperationStatus.COMPLETED, response.status());
        assertStepStatus(response, "failureAnalysis", "COMPLETED");
        assertStepStatus(response, "healing", "COMPLETED");
        assertStepStatus(response, "bugReport", "COMPLETED");
        verify(failureAnalysisService).analyzeExecution(eq(10L), eq(3L));
        verify(healingAnalysisService).analyzeExecution(eq(10L), eq(3L));
        verify(bugReportService).generateAll(eq(10L), eq(3L), any());
    }

    @Test
    void skipsHealingWhenNotACandidate() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(contextResolver.databaseId(info)).thenReturn(3L);
        when(codeGenerationService.generateTestsEntities(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "FAILED", "duration", 500L, "output", "500", "error", "http 500"));
        TestExecution record = new TestExecution();
        record.setId(11L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any())).thenReturn(record);
        stubBugReport(11L);

        FailureAnalysis analysis = new FailureAnalysis();
        analysis.setFailureType("HTTP_ERROR");
        analysis.setHealingCandidate(false);
        when(failureAnalysisService.analyzeExecution(eq(11L), eq(3L))).thenReturn(analysis);

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null, null, null));

        assertStepStatus(response, "failureAnalysis", "COMPLETED");
        assertStepStatus(response, "healing", "SKIPPED");
        verify(healingAnalysisService, never()).analyzeExecution(any(), any());
    }

    @Test
    void marksWorkflowFailedWhenPipelineThrows() {
        when(explorerService.analyze(any(), anyBoolean(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("AI provider unavailable"));

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null, null, null));

        assertEquals(OperationStatus.FAILED, response.status());
        assertStepStatus(response, "workflow", "FAILED");
    }

    private void assertStepStatus(V1WorkflowResponse response, String step, String status) {
        @SuppressWarnings("unchecked")
        Map<String, Object> stepMap = (Map<String, Object>) response.steps().get(step);
        assertEquals(status, stepMap.get("status"), () -> "step " + step + " status");
    }

    private void stubBugReport(Long executionId) {
        BugReport report = new BugReport();
        report.setReportId("bug-" + executionId);
        report.setStatus("FAILED");
        report.setTitle("Title " + executionId);
        report.setSeverity("HIGH");
        report.setSummary("Summary " + executionId);
        // The workflow now asks for every distinct failure, not one report (B-032).
        when(bugReportService.generateAll(eq(executionId), eq(3L), any())).thenReturn(List.of(report));
    }

    // ---- B-022: the stdout tail ----
    //
    // The response used to carry the FIRST 2000 characters, which is where the
    // generated test source lives, so the failures were cut off exactly when they
    // mattered. The tail keeps the diagnostics and drops the noise.

    @Test
    void tailKeepsTheEndOfTheOutputWhereTheFailuresAre() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            sb.append("line ").append(i).append('\n');
        }
        String tail = QaWorkflowService.tail(sb.toString(), 50);

        assertTrue(tail.contains("showing last 50 lines"), tail);
        assertTrue(tail.contains("line 500"), "the last line must survive: " + tail);
        assertFalse(tail.contains("line 1\n"), "the head must be dropped: " + tail);
        assertEquals(50, tail.split("\\R").length - 1, "exactly the last 50 lines");
    }

    @Test
    void tailLeavesShortOutputUntouched() {
        assertEquals("only\ntwo\n", QaWorkflowService.tail("only\ntwo\n", 50));
        assertNull(QaWorkflowService.tail(null, 50));
    }

    @Test
    void tailHandlesWindowsLineEndings() {
        String tail = QaWorkflowService.tail("a\r\nb\r\nc\r\n", 2);
        assertTrue(tail.contains("showing last 2 lines"), tail);
        assertTrue(tail.endsWith("b\nc\n"), "CRLF must be split as line boundaries: " + tail);
    }

    @Test
    void aRunWithNoDistinctFailureReportsThatRatherThanFailingTheStep() {
        // The workflow used reports.get(0) unguarded, so an empty result was an
        // IndexOutOfBounds that failed the whole report step. A run where nothing could
        // be attributed is a legitimate outcome, and saying so beats a red step.
        project.setWorkspacePath("/home/dev/internet-tests");
        when(contextResolver.databaseId(info)).thenReturn(3L);
        when(codeGenerationService.generateTestsEntities(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "FAILED", "duration", 500L, "output", "boom", "error", "failed"));
        TestExecution record = new TestExecution();
        record.setId(10L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any()))
                .thenReturn(record);
        when(bugReportService.generateAll(eq(10L), eq(3L), any())).thenReturn(List.of());

        V1WorkflowResponse response = workflow.runFullTest(
                new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login",
                        null, null, null, null, null));

        assertStepStatus(response, "bugReport", "COMPLETED");
    }
}
