package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.AiResponse;
import com.qalab.qalabai.healing.context.FailureContextFactory;
import com.qalab.qalabai.model.BugReport;
import com.qalab.qalabai.model.TestCaseResult;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.BugReportRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.repository.TestCaseResultRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The user's complaint was that the generated bug report was not one of their known
 * bugs: generic title, {@code LOCATOR_FAILURE}, {@code Unknown error}. The cause was that
 * one report was generated from the whole-run console output, so it could not name any
 * specific failure.
 */
class BugReportServicePerSignatureTest {

    private BugReportRepository bugReports;
    private TestExecutionRepository executions;
    private TestCaseResultRepository caseResults;
    private AiGateway aiGateway;
    private BugReportService service;

    private final AtomicLong ids = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        bugReports = mock(BugReportRepository.class);
        executions = mock(TestExecutionRepository.class);
        caseResults = mock(TestCaseResultRepository.class);
        aiGateway = mock(AiGateway.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        FailureContextFactory contexts = mock(FailureContextFactory.class);

        when(bugReports.save(any(BugReport.class))).thenAnswer(inv -> {
            BugReport report = inv.getArgument(0);
            if (report.getId() == null) {
                report.setId(ids.incrementAndGet());
            }
            return report;
        });
        when(bugReports.findByProjectIdAndDedupKey(any(), anyString()))
                .thenReturn(Optional.empty());

        service = new BugReportService(bugReports, executions, caseResults, projects, contexts,
                aiGateway, new ObjectMapper());
    }

    private TestExecution failedExecution() {
        TestExecution execution = new TestExecution();
        execution.setId(500L);
        execution.setProjectId(42L);
        execution.setTestFile("all");
        execution.setStatus("FAILED");
        return execution;
    }

    private TestCaseResult failing(int ordinal, String file, String title, String error) {
        TestCaseResult row = new TestCaseResult();
        row.setExecution(failedExecution());
        row.setOrdinalPosition(ordinal);
        row.setSpecFile(file);
        row.setTestTitle(title);
        row.setStatus("failed");
        row.setErrorMessage(error);
        row.setRetries(0);
        return row;
    }

    private TestCaseResult passing(int ordinal, String title) {
        TestCaseResult row = new TestCaseResult();
        row.setExecution(failedExecution());
        row.setOrdinalPosition(ordinal);
        row.setSpecFile("a.spec.ts");
        row.setTestTitle(title);
        row.setStatus("passed");
        row.setRetries(0);
        return row;
    }

    /** The AI path is stubbed to fail so the deterministic fallback is exercised. */
    private void makeAiFail() {
        when(aiGateway.complete(any(), any()))
                .thenThrow(new RuntimeException("no provider configured"));
    }

    @Test
    void threeDistinctFailuresProduceThreeReports() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "login.spec.ts", "A", "Error: expect(#email).toBeVisible() failed"),
                failing(1, "login.spec.ts", "B", "Error: expect(#password).toBeVisible() failed"),
                failing(2, "checkout.spec.ts", "C", "Error: expect(page).toHaveTitle() failed")));
        makeAiFail();

        List<BugReport> reports = service.generateAll(500L, 42L, null);

        assertEquals(3, reports.size(), "three genuinely different failures are three bugs");
        for (BugReport report : reports) {
            assertNotNull(report.getDedupKey());
            assertEquals(1, report.getOccurrences());
        }
    }

    @Test
    void identicalFailuresCollapseIntoOneReportWithACount() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "login.spec.ts", "A", "Error: expect(#flash).toBeVisible() failed"),
                failing(1, "login.spec.ts", "B", "Error: expect(#flash).toBeVisible() failed at line 91")));
        makeAiFail();

        List<BugReport> reports = service.generateAll(500L, 42L, null);

        assertEquals(1, reports.size(), "the same assertion in two tests is one bug");
        // The group's representative is the first failing test, so the citation is
        // deterministic rather than "whichever error happened to be seen last".
        assertEquals("Error: expect(#flash).toBeVisible() failed", reports.get(0).getErrorMessage(),
                "the report cites the assertion that failed");
    }

    @Test
    void aKnownBugIsCountedRatherThanReFiled() {
        TestCaseResult row = failing(0, "login.spec.ts", "A", "Error: expect(#flash).toBeVisible() failed");
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(row));

        String signature = FailureSignature.of(row);
        BugReport known = new BugReport();
        known.setReportId("bug-existing1");
        known.setDedupKey(signature);
        known.setOccurrences(3);
        known.setTitle("Known: the flash banner never appears");
        when(bugReports.findByProjectIdAndDedupKey(42L, signature)).thenReturn(Optional.of(known));

        List<BugReport> reports = service.generateAll(500L, 42L, null);

        assertEquals(1, reports.size());
        assertSame(known, reports.get(0), "the existing report must be reused, not replaced");
        assertEquals(4, known.getOccurrences(), "a recurring bug is more urgent, not a new bug");
        assertEquals(500L, known.getExecutionId(), "and it must point at the run that hit it");
        // No AI call: re-filing a known bug would spend money to say the same thing.
        verify(aiGateway, never()).complete(any(), any());
    }

    @Test
    void aReportNameTheAssertionRatherThanTheTestFile() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "login.spec.ts", "Login with empty password",
                        "Error: expect(locator('#error')).toBeVisible() failed")));
        makeAiFail();

        BugReport report = service.generateAll(500L, 42L, null).get(0);

        assertTrue(report.getTitle().contains("toBeVisible"),
                "the title must name the assertion, was: " + report.getTitle());
        assertNotEquals("Test failed: login.spec.ts", report.getTitle());
    }

    @Test
    void expectedAndActualAreNeverLeftAsUnknown() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "login.spec.ts", "A",
                        "Error: expect(page).toHaveTitle('Dashboard') failed")));
        makeAiFail();

        BugReport report = service.generateAll(500L, 42L, null).get(0);

        // "Unknown." was the other half of the generic report.
        assertTrue(report.getExpectedBehavior().contains("toHaveTitle"),
                report.getExpectedBehavior());
        assertTrue(report.getActualBehavior().contains("toHaveTitle"),
                report.getActualBehavior());
    }

    @Test
    void theScreenshotFromTheFailingTestIsAttached() {
        TestCaseResult withShot = failing(0, "login.spec.ts", "A", "Error: expect(x) failed");
        withShot.setScreenshots("[\"/artifacts/execution-500/tests/0-a/shot.png\"]");
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(withShot));
        makeAiFail();

        BugReport report = service.generateAll(500L, 42L, null).get(0);

        assertEquals("/artifacts/execution-500/tests/0-a/shot.png", report.getScreenshotPath());
    }

    @Test
    void passingTestsNeverProduceAReport() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                passing(0, "Fine"), passing(1, "Also fine")));
        makeAiFail();

        // Nothing failed, so there is nothing to report. A report here would be noise.
        assertThrows(RuntimeException.class, () -> service.generateAll(500L, 42L, null));
        verify(bugReports, never()).save(any());
    }

    @Test
    void aRunWithNoPerTestResultsFallsBackToTheWholeExecutionReport() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of());
        makeAiFail();
        com.qalab.qalabai.healing.model.FailureContext context =
                new com.qalab.qalabai.healing.model.FailureContext();
        context.setTestName("legacy run");
        context.setTestFile("all");
        context.setError("Error: legacy whole-run failure");
        // The factory is a mock, so the fallback path needs a usable context.
        FailureContextFactory factory = mock(FailureContextFactory.class);
        when(factory.fromExecution(any(), anyString(), any(), any())).thenReturn(context);
        service = new BugReportService(bugReports, executions, caseResults, mock(ProjectRepository.class),
                factory, aiGateway, new ObjectMapper());

        List<BugReport> reports = service.generateAll(500L, 42L, null);

        assertEquals(1, reports.size());
        assertTrue(reports.get(0).getErrorMessage().contains("legacy"));
    }

    @Test
    void theUserInstructionIsCarriedOntoEveryReport() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "a.spec.ts", "A", "Error: expect(one) failed"),
                failing(1, "a.spec.ts", "B", "Error: expect(two) failed")));
        makeAiFail();

        List<BugReport> reports = service.generateAll(500L, 42L, "focus on the checkout flow");

        for (BugReport report : reports) {
            assertTrue(report.getInstruction().contains("checkout"),
                    "user guidance must reach every report, was: " + report.getInstruction());
        }
    }

    @Test
    void aPassedExecutionIsRejectedRatherThanProducingAnEmptyReport() {
        TestExecution passed = failedExecution();
        passed.setStatus("PASSED");
        when(executions.findById(500L)).thenReturn(Optional.of(passed));

        assertThrows(RuntimeException.class, () -> service.generateAll(500L, 42L, null));
        verify(bugReports, never()).save(any());
    }

    @Test
    void anUnknownExecutionIsRejected() {
        when(executions.findById(999L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.generateAll(999L, 42L, null));
    }

    @Test
    void aFirstSeenRunIsRecordedSoTheReportCanBeTracedBack() {
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "a.spec.ts", "A", "Error: expect(x) failed")));
        makeAiFail();

        BugReport report = service.generateAll(500L, 42L, null).get(0);

        assertEquals("500", report.getFirstSeenRun());
    }

    @Test
    void anExplicitNullProjectFallsBackToTheExecutionsOwn() {
        // Passing null means "infer from the execution", not "no project" — and the
        // inference is what keeps a run's reports filed against the right account.
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "a.spec.ts", "A", "Error: expect(x) failed")));
        makeAiFail();

        List<BugReport> reports = service.generateAll(500L, null, null);

        assertEquals(42L, reports.get(0).getProjectId());
        verify(bugReports).findByProjectIdAndDedupKey(42L, FailureSignature.of("a.spec.ts", "A",
                "Error: expect(x) failed"));
    }

    @Test
    void aRunOwnedByNoProjectIsNeverDeduped() {
        // A shared signature across accounts would leak one account's bugs into
        // another's list, so an unowned run gets a fresh report every time.
        TestExecution unowned = failedExecution();
        unowned.setProjectId(null);
        when(executions.findById(500L)).thenReturn(Optional.of(unowned));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "a.spec.ts", "A", "Error: expect(x) failed")));
        makeAiFail();

        List<BugReport> reports = service.generateAll(500L, null, null);

        assertEquals(1, reports.size());
        verify(bugReports, never()).findByProjectIdAndDedupKey(any(), anyString());
    }

    @Test
    void oneAiCallPerDistinctFailureNotPerTest() {
        when(aiGateway.complete(any(), any())).thenReturn(new AiResponse(
                "{\"title\":\"t\",\"severity\":\"LOW\",\"summary\":\"s\","
                        + "\"stepsToReproduce\":\"a\",\"expectedBehavior\":\"b\","
                        + "\"actualBehavior\":\"c\"}",
                null, null, 10, 10, false, null, null));
        when(executions.findById(500L)).thenReturn(Optional.of(failedExecution()));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(500L)).thenReturn(List.of(
                failing(0, "a.spec.ts", "A", "Error: expect(one) failed"),
                failing(1, "a.spec.ts", "B", "Error: expect(one) failed"),
                failing(2, "a.spec.ts", "C", "Error: expect(two) failed")));

        service.generateAll(500L, 42L, null);

        // Two distinct failures, two calls. Not three, and not one for the whole run.
        verify(aiGateway, times(2)).complete(any(), any());
    }
}
