package com.qalab.qalabai.agent.executor;

import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.service.workspace.WorkspaceProvider;
import com.qalab.qalabai.tool.playwright.PlaywrightResultParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A regression that survived a green test suite and a real Playwright run.
 *
 * <p>B-022 added structured per-test results and verified them by calling
 * {@code PlaywrightTool.execute} directly. That proved the parser and the runner worked.
 * It did not prove anything about the <em>product</em>, because
 * {@code ExecutorAgent} copies a hand-picked list of keys out of the runner's map —
 * and {@code summary} was not on the list.</p>
 *
 * <p>The consequence was that {@code steps.execution.results} was never present in a
 * workflow response, and nothing was ever written to {@code test_case_result}. The CLI's
 * structured summary silently fell back to its old behaviour. Everything looked fine:
 * the unit tests were green, the parser was green, and the tool was green.</p>
 *
 * <p>Testing a component is not the same as exercising the path a user takes.</p>
 */
class ExecutorAgentStructuredResultsTest {

    private WorkspaceProvider workspaceProviderReturning(Map<String, Object> runnerResult) {
        WorkspaceProvider provider = mock(WorkspaceProvider.class);
        when(provider.execute(any(), any(), anyBoolean(), isNull())).thenReturn(runnerResult);
        return provider;
    }

    private Task task() {
        Task task = new Task("task-1", "RUN_TEST", null);
        task.putContext("projectContext", new ProjectContext());
        task.putContext("runAll", true);
        return task;
    }

    private PlaywrightResultParser.RunSummary summary() {
        return new PlaywrightResultParser.RunSummary(
                1, 1, 0, 0, 5000L,
                List.of(new PlaywrightResultParser.TestCaseResult(
                        "a.spec.ts", "Login fails", "a.spec.ts › Login fails", "failed",
                        4000L, 0, "Error: boom", "at A.java:1",
                        List.of("/ws/test-results/a/test-failed-1.png"), List.of(), List.of())),
                List.of());
    }

    @Test
    void theStructuredSummarySurvivesTheExecutor() {
        Map<String, Object> runnerResult = new LinkedHashMap<>();
        runnerResult.put("status", "FAILED");
        runnerResult.put("duration", 5000L);
        runnerResult.put("output", "1 failed");
        runnerResult.put("summary", summary());

        AgentResult result = new ExecutorAgent(workspaceProviderReturning(runnerResult)).execute(task());

        assertTrue(result.isSuccess(), result.getMessage());
        Object carried = result.getData().get("summary");
        assertNotNull(carried,
                "the structured summary was dropped: without it the workflow has no results "
                        + "and nothing is persisted");
        assertTrue(carried instanceof PlaywrightResultParser.RunSummary,
                "the summary must arrive as its typed form, not as a string: " + carried);
    }

    @Test
    void theSummaryIsUsableByEverythingDownstream() {
        // What the two consumers actually need: the workflow builds its `results` map
        // from this, and ExecutionService writes rows to test_case_result from it.
        Map<String, Object> runnerResult = new LinkedHashMap<>();
        runnerResult.put("status", "FAILED");
        runnerResult.put("duration", 5000L);
        runnerResult.put("output", "1 failed");
        runnerResult.put("summary", summary());

        AgentResult result = new ExecutorAgent(workspaceProviderReturning(runnerResult)).execute(task());
        PlaywrightResultParser.RunSummary carried =
                (PlaywrightResultParser.RunSummary) result.getData().get("summary");

        assertEquals(1, carried.passed());
        assertEquals(1, carried.failed());
        assertEquals(1, carried.tests().size());
        assertEquals("Login fails", carried.tests().get(0).title());
        assertEquals(1, carried.tests().get(0).screenshots().size(),
                "the screenshot path is what lets the report attribute evidence to a failure");
    }

    @Test
    void aRunWithNoStructuredResultsIsNotAnError() {
        // A workspace whose config could not be extended still has to produce a usable
        // response; the absence of a summary must not fail the agent.
        Map<String, Object> runnerResult = new LinkedHashMap<>();
        runnerResult.put("status", "PASSED");
        runnerResult.put("duration", 100L);
        runnerResult.put("output", "2 passed");

        AgentResult result = new ExecutorAgent(workspaceProviderReturning(runnerResult)).execute(task());

        assertTrue(result.isSuccess());
        assertEquals("PASSED", result.getData().get("status"));
        assertTrue(result.getData().get("summary") == null,
                "no summary means no results key, which the workflow reports honestly");
    }

    @Test
    void theExistingKeysStillPassThrough() {
        // The summary is additive: nothing that worked before may regress.
        Map<String, Object> runnerResult = new LinkedHashMap<>();
        runnerResult.put("status", "FAILED");
        runnerResult.put("duration", 1234L);
        runnerResult.put("output", "boom");
        runnerResult.put("error", "locator failed");
        runnerResult.put("summary", summary());

        Map<String, Object> data = new ExecutorAgent(workspaceProviderReturning(runnerResult))
                .execute(task()).getData();

        assertEquals("FAILED", data.get("status"));
        assertEquals(1234L, data.get("duration"));
        assertEquals("boom", data.get("output"));
        assertEquals("locator failed", data.get("error"));
        assertNotNull(data.get("summary"));
    }
}
