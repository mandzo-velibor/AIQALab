package com.qalab.qalabai.service;

import com.qalab.qalabai.dto.executor.ExecutionResultsResponse;
import com.qalab.qalabai.model.TestCaseResult;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.TestCaseResultRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-test endpoint exists so the UI can show a run's outcome without anyone opening
 * a file. Two properties matter and are easy to lose: the counts must be derived from the
 * stored rows rather than the execution's single verdict, and a run with no rows must say
 * so rather than reading as a clean run.
 */
class ExecutionServiceResultsTest {

    private TestExecutionRepository executions;
    private TestCaseResultRepository caseResults;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        executions = mock(TestExecutionRepository.class);
        caseResults = mock(TestCaseResultRepository.class);
        // The Allure collaborator is last and defaults to "not available", so this test
        // exercises the bespoke report path exactly as it did before Allure existed.
        service = new ExecutionService(null, null, executions, null, null, null, null, null,
                null, caseResults, new ObjectMapper());
    }

    private TestExecution execution(Long id, String status) {
        TestExecution execution = new TestExecution();
        execution.setId(id);
        execution.setStatus(status);
        execution.setDuration(18_612L);
        return execution;
    }

    private TestCaseResult row(int ordinal, String title, String status) {
        TestCaseResult row = new TestCaseResult();
        row.setOrdinalPosition(ordinal);
        row.setSpecFile("login.spec.ts");
        row.setTestTitle(title);
        row.setStatus(status);
        row.setDurationMs(1000L + ordinal);
        row.setRetries(0);
        return row;
    }

    @Test
    void countsComeFromTheStoredTestsNotTheExecutionVerdict() {
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "FAILED")));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of(
                row(0, "Login succeeds", "passed"),
                row(1, "Login fails", "failed"),
                row(2, "Search skipped", "skipped")));

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        // The execution says FAILED, which tells a user nothing about the three tests.
        assertEquals(3, results.totalCount());
        assertEquals(1, results.passedCount());
        assertEquals(1, results.failedCount());
        assertEquals(1, results.skippedCount());
        assertEquals("FAILED", results.status());
    }

    @Test
    void everyTestIsReturnedInRunOrderWithItsError() {
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "FAILED")));
        TestCaseResult failing = row(1, "Login fails", "failed");
        failing.setErrorMessage("Error: expect(locator).toBeVisible() failed\n  at a.ts:1");
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of(
                row(0, "Login succeeds", "passed"), failing));

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertEquals(2, results.tests().size());
        assertEquals("Login succeeds", results.tests().get(0).title());
        assertEquals("Login fails", results.tests().get(1).title());
        assertNotNull(results.tests().get(1).error());
        assertTrue(results.tests().get(1).error().contains("toBeVisible"));
    }

    @Test
    void aRunWithNoStoredTestsReportsZeroesRatherThanFailing() {
        // The UI must be able to distinguish "no results" from "everything passed".
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "PASSED")));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of());

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertNotNull(results.tests());
        assertTrue(results.tests().isEmpty());
        assertEquals(0, results.totalCount());
    }

    @Test
    void anUnknownStatusIsCountedInTheTotalButNotInAnyBucket() {
        // A new Playwright status must not make the counts add up to less than the total.
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "FAILED")));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of(
                row(0, "Passed", "passed"), row(1, "Something new", "interrupted")));

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertEquals(2, results.totalCount());
        assertEquals(1, results.passedCount());
        assertEquals(0, results.failedCount());
    }

    @Test
    void evidenceIsFlaggedSoTheUiCanSayWhetherThereIsAny() {
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "FAILED")));
        TestCaseResult withShot = row(0, "Has evidence", "failed");
        withShot.setScreenshots("[\"/abs/shot.png\"]");
        TestCaseResult withNothing = row(1, "No evidence", "failed");
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L))
                .thenReturn(List.of(withShot, withNothing));

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertTrue(results.tests().get(0).hasEvidence());
        assertEquals(1, results.tests().get(0).screenshots().size());
        assertFalse(results.tests().get(1).hasEvidence());
    }

    @Test
    void retriesAreSurfacedBecauseAFlakeIsNotAFailure() {
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "PASSED")));
        TestCaseResult flaky = row(0, "Eventually passes", "passed");
        flaky.setRetries(2);
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of(flaky));

        assertEquals(2, service.getExecutionResults(1L).tests().get(0).retries());
    }

    @Test
    void aMalformedAttachmentCellIsIgnoredRatherThanFailingTheRequest() {
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "FAILED")));
        TestCaseResult bad = row(0, "Corrupt cell", "failed");
        bad.setScreenshots("{not json");
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of(bad));

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertEquals(1, results.tests().size());
        assertTrue(results.tests().get(0).screenshots().isEmpty());
        assertFalse(results.tests().get(0).hasEvidence());
    }

    @Test
    void theReportPathsTravelWithTheResults() {
        TestExecution execution = execution(1L, "FAILED");
        execution.setReportPath("/artifacts/execution-1/report.json");
        execution.setHtmlReportPath("/artifacts/execution-1/report.html");
        when(executions.findById(1L)).thenReturn(Optional.of(execution));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of());

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertEquals("/artifacts/execution-1/report.html", results.htmlReport());
        assertEquals("/artifacts/execution-1/report.json", results.reportPath());
    }

    @Test
    void reportPathsAreNullWhenNoReportWasWritten() {
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "FAILED")));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of());

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertNull(results.htmlReport());
        assertNull(results.reportPath());
    }

    @Test
    void anUnknownExecutionFailsLoudlyRatherThanReturningAnEmptyResult() {
        // A 200 with empty counts would be indistinguishable from a clean run.
        when(executions.findById(99L)).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.getExecutionResults(99L));
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    @Test
    void aNullDurationDoesNotBreakTheResponse() {
        TestExecution execution = new TestExecution();
        execution.setId(1L);
        execution.setStatus("FAILED");
        when(executions.findById(1L)).thenReturn(Optional.of(execution));
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of());

        ExecutionResultsResponse results = service.getExecutionResults(1L);

        assertNull(results.durationMs());
        assertNotNull(results.tests());
    }

    @Test
    void aNullOrdinalFallsBackToPositionSoTheUiAlwaysHasAKey() {
        when(executions.findById(1L)).thenReturn(Optional.of(execution(1L, "FAILED")));
        TestCaseResult noOrdinal = row(0, "No ordinal", "passed");
        noOrdinal.setOrdinalPosition(null);
        when(caseResults.findByExecutionIdOrderByOrdinalPositionAsc(1L)).thenReturn(List.of(noOrdinal));

        assertEquals(0, service.getExecutionResults(1L).tests().get(0).ordinal());
    }
}
