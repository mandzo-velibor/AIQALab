package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.AiProviderType;
import com.qalab.qalabai.ai.gateway.AiRequest;
import com.qalab.qalabai.ai.gateway.AiResponse;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.healing.context.FailureContextFactory;
import com.qalab.qalabai.healing.model.FailureClassification;
import com.qalab.qalabai.healing.model.FailureContext;
import com.qalab.qalabai.model.BugReport;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.BugReportRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BugReportServiceTest {

    private BugReportRepository bugReportRepository;
    private TestExecutionRepository executionRepository;
    private ProjectRepository projectRepository;
    private FailureContextFactory contextFactory;
    private AiGateway aiGateway;
    private BugReportService service;

    @BeforeEach
    void setUp() {
        bugReportRepository = mock(BugReportRepository.class);
        executionRepository = mock(TestExecutionRepository.class);
        projectRepository = mock(ProjectRepository.class);
        contextFactory = mock(FailureContextFactory.class);
        aiGateway = mock(AiGateway.class);
        service = new BugReportService(bugReportRepository, executionRepository,
                projectRepository, contextFactory, aiGateway, new ObjectMapper());
    }

    private TestExecution failedExecution(Long id) {
        TestExecution execution = new TestExecution();
        execution.setId(id);
        execution.setProjectId(42L);
        execution.setTestFile("login.spec.ts");
        execution.setStatus("FAILED");
        execution.setErrorMessage("waiting for getByRole('button', { name: 'Sign In' })");
        execution.setConsoleLogs("  › Login with valid credentials (5s)\nwaiting for getByRole(...)");
        return execution;
    }

    private FailureContext failureContext() {
        FailureContext context = new FailureContext();
        context.setProjectId(42L);
        context.setRunId("7");
        context.setTestName("Login with valid credentials");
        context.setTestFile("login.spec.ts");
        context.setError("waiting for getByRole('button', { name: 'Sign In' })");
        context.setLogs("  › Login with valid credentials (5s)");
        context.setCurrentUrl("http://localhost:8080/login");
        context.setClassification(FailureClassification.APPLICATION_ERROR);
        return context;
    }

    private void stubAi(String json) {
        when(aiGateway.complete(any(), any())).thenReturn(
                new AiResponse(json, AiProviderType.AIQALAB, "test-model", 10, 10, false, BigDecimal.ZERO, "op-1"));
    }

    @Test
    void generatesAndPersistsBugReportFromAi() {
        when(executionRepository.findById(7L)).thenReturn(Optional.of(failedExecution(7L)));
        when(contextFactory.fromExecution(eq(42L), anyString(), any(), any())).thenReturn(failureContext());
        stubAi("""
                {
                  "title": "Sign In button missing after failed login",
                  "severity": "HIGH",
                  "summary": "The Sign In button never becomes enabled.",
                  "stepsToReproduce": "1. Open /login\\n2. Click Login",
                  "expectedBehavior": "Button is enabled",
                  "actualBehavior": "Button stays disabled",
                  "affectedElement": "Sign In button",
                  "failureType": "APPLICATION_BUG",
                  "suggestedFix": "Enable the button after form validation"
                }
                """);
        when(bugReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BugReport report = service.generate(7L, null);

        assertNotNull(report.getReportId());
        assertTrue(report.getReportId().startsWith("bug-"));
        assertEquals(42L, report.getProjectId());
        assertEquals(7L, report.getExecutionId());
        assertEquals("login.spec.ts", report.getTestFile());
        assertEquals("Login with valid credentials", report.getTestName());
        assertEquals("Sign In button missing after failed login", report.getTitle());
        assertEquals("HIGH", report.getSeverity());
        assertEquals("APPLICATION_BUG", report.getFailureType());
        assertEquals("1. Open /login\n2. Click Login", report.getStepsToReproduce());
        assertTrue(report.getReportJson().contains("\"title\""));
        verify(bugReportRepository).save(any());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiGateway).complete(captor.capture(), any());
        assertTrue(captor.getValue().getUserPrompt().contains("Login with valid credentials"));
    }

    @Test
    void rejectsPassedExecution() {
        TestExecution passed = failedExecution(8L);
        passed.setStatus("PASSED");
        when(executionRepository.findById(8L)).thenReturn(Optional.of(passed));

        assertThrows(ApiException.class, () -> service.generate(8L, null));
        verify(aiGateway, never()).complete(any(), any());
        verify(bugReportRepository, never()).save(any());
    }

    @Test
    void fallsBackToDeterministicReportWhenAiFails() {
        when(executionRepository.findById(9L)).thenReturn(Optional.of(failedExecution(9L)));
        when(contextFactory.fromExecution(eq(42L), anyString(), any(), any())).thenReturn(failureContext());
        when(aiGateway.complete(any(), any())).thenThrow(new RuntimeException("provider down"));
        when(bugReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BugReport report = service.generate(9L, null);

        assertNotNull(report.getReportId());
        assertEquals("Test failed: Login with valid credentials", report.getTitle());
        assertEquals("MEDIUM", report.getSeverity());
        assertEquals("APPLICATION_ERROR", report.getFailureType());
        assertEquals("{}", report.getReportJson());
        verify(bugReportRepository).save(any());
    }

    @Test
    void fallsBackWhenAiReturnsUnparseableContent() {
        when(executionRepository.findById(10L)).thenReturn(Optional.of(failedExecution(10L)));
        when(contextFactory.fromExecution(eq(42L), anyString(), any(), any())).thenReturn(failureContext());
        stubAi("I am sorry, I cannot produce that.");
        when(bugReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BugReport report = service.generate(10L, null);

        assertEquals("Test failed: Login with valid credentials", report.getTitle());
        assertEquals("{}", report.getReportJson());
    }

    @Test
    void findByReportIdReturnsPersistedReport() {
        BugReport saved = new BugReport();
        saved.setReportId("bug-1234");
        saved.setTitle("Broken flow");
        when(bugReportRepository.findByReportId("bug-1234")).thenReturn(Optional.of(saved));

        assertEquals("Broken flow", service.findByReportId("bug-1234").getTitle());
    }

    @Test
    void findByReportIdThrowsWhenMissing() {
        when(bugReportRepository.findByReportId("bug-nope")).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.findByReportId("bug-nope"));
    }
}
