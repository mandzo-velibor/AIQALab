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
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.model.BugReport;
import com.qalab.qalabai.service.workspace.WorkspaceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private final WorkspaceProvider workspaceProvider = mock(WorkspaceProvider.class);

    private QaWorkflowService workflow;

    private ProjectInfo info;
    private ProjectContext project;

    @BeforeEach
    void setUp() {
        workflow = new QaWorkflowService(contextResolver, explorerService, locatorService,
                planningService, codeGenerationService, executionService,
                failureAnalysisService, healingAnalysisService, bugReportService, workspaceProvider);

        info = ProjectInfo.of("internet-tests", "https://the-internet.herokuapp.com/login", "PLAYWRIGHT", "TYPESCRIPT");
        project = new ProjectContext();
        project.setProjectId("internet-tests");
        project.setBaseUrl("https://the-internet.herokuapp.com/login");
        when(contextResolver.resolve(info)).thenReturn(project);
        when(contextResolver.databaseId(info)).thenReturn(null);

        when(explorerService.analyze(any(), anyBoolean(), any(), any(), any(), any())).thenReturn(
                new AnalysisResponse("LOGIN", "summary", 95, null, null, null, null, null, null, null, null));
        when(locatorService.generateLocators(any(), any())).thenReturn(new LocatorResponse(0, List.of(), null, List.of()));
        when(planningService.generateTestPlan(any(), any())).thenReturn(new TestPlanResponse(0, List.of(), null));
        when(codeGenerationService.generateTestsContent(any(), any())).thenReturn(
                List.of(new GeneratedFile("login.spec.ts", "test('x', async () => {});")));
    }

    @Test
    void skipsExecutionWhenNoWorkspacePath() {
        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null));

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
    void runsInWorkspaceAndSkipsFailureAnalysisWhenTestsPass() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(codeGenerationService.generateTestsEntities(any(), any())).thenReturn(List.of());
        when(workspaceProvider.execute(any(), any(), eq(true))).thenReturn(
                Map.of("status", "PASSED", "duration", 1200L, "output", "ok"));
        TestExecution record = new TestExecution();
        record.setId(7L);
        when(executionService.recordExecution(any(), any(), any(), any(), any(), any())).thenReturn(record);

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null));

        assertEquals(OperationStatus.COMPLETED, response.status());
        assertStepStatus(response, "execution", "COMPLETED");
        assertStepStatus(response, "failureAnalysis", "SKIPPED");
        assertStepStatus(response, "healing", "SKIPPED");
        verify(workspaceProvider).writeTests(any(), any());
        verify(workspaceProvider).execute(any(), any(), eq(true));
    }

    @Test
    void analyzesFailureAndGeneratesHealingCandidate() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(contextResolver.databaseId(info)).thenReturn(3L);
        when(codeGenerationService.generateTestsEntities(any(), any())).thenReturn(List.of());
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

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null));

        assertEquals(OperationStatus.COMPLETED, response.status());
        assertStepStatus(response, "failureAnalysis", "COMPLETED");
        assertStepStatus(response, "healing", "COMPLETED");
        assertStepStatus(response, "bugReport", "COMPLETED");
        verify(failureAnalysisService).analyzeExecution(eq(10L), eq(3L));
        verify(healingAnalysisService).analyzeExecution(eq(10L), eq(3L));
        verify(bugReportService).generate(eq(10L), eq(3L), any());
    }

    @Test
    void skipsHealingWhenNotACandidate() {
        project.setWorkspacePath("/home/dev/internet-tests");
        when(contextResolver.databaseId(info)).thenReturn(3L);
        when(codeGenerationService.generateTestsEntities(any(), any())).thenReturn(List.of());
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

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null));

        assertStepStatus(response, "failureAnalysis", "COMPLETED");
        assertStepStatus(response, "healing", "SKIPPED");
        verify(healingAnalysisService, never()).analyzeExecution(any(), any());
    }

    @Test
    void marksWorkflowFailedWhenPipelineThrows() {
        when(explorerService.analyze(any(), anyBoolean(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("AI provider unavailable"));

        V1WorkflowResponse response = workflow.runFullTest(new V1FullWorkflowRequest(info, "https://the-internet.herokuapp.com/login", null, null, null));

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
        when(bugReportService.generate(eq(executionId), eq(3L), any())).thenReturn(report);
    }
}
