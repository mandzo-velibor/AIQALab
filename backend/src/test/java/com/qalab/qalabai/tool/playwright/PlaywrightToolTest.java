package com.qalab.qalabai.tool.playwright;

import com.qalab.qalabai.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the Playwright runner's wall-clock budget.
 *
 * <p>Before the fix the subprocess stdout was drained on the calling thread
 * <em>before</em> {@code waitFor(timeout)} was reached, so the timeout was
 * unreachable, {@code destroyForcibly()} was dead code, and a hung Playwright run
 * pinned the request thread indefinitely. Observed in practice: a 158 s run under a
 * nominal "60 s" timeout.
 */
class PlaywrightToolTest {

    private static final Set<String> VALID_STATUSES = Set.of("PASSED", "FAILED", "ERROR", "TIMEOUT");

    private PlaywrightTool tool(long timeoutSeconds) {
        PlaywrightTool tool = new PlaywrightTool();
        ReflectionTestUtils.setField(tool, "testsDir", "./tests");
        ReflectionTestUtils.setField(tool, "timeoutSeconds", timeoutSeconds);
        return tool;
    }

    @Test
    void runProcessTerminatesAProcessThatOverrunsTheBudget() throws Exception {
        PlaywrightTool tool = tool(2);
        Path dir = Files.createTempDirectory("qalab-pw-timeout");

        long start = System.currentTimeMillis();
        // sleep 60 must not be able to outlast a 2s budget.
        PlaywrightTool.ProcessOutcome outcome = tool.runProcess(List.of("sleep", "60"), dir);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(outcome.completed(), "process overran the budget but was reported as completed");
        assertTrue(elapsed < 30_000, "process should be killed near the budget, took " + elapsed + "ms");
        assertNotNull(outcome.output());
    }

    @Test
    void runProcessReportsExitCodeForFastProcess() throws Exception {
        PlaywrightTool tool = tool(30);
        Path dir = Files.createTempDirectory("qalab-pw-exit");

        PlaywrightTool.ProcessOutcome ok = tool.runProcess(List.of("sh", "-c", "exit 0"), dir);
        PlaywrightTool.ProcessOutcome fail = tool.runProcess(List.of("sh", "-c", "exit 3"), dir);

        assertTrue(ok.completed());
        assertEquals(0, ok.exitCode());
        assertTrue(fail.completed());
        assertEquals(3, fail.exitCode());
    }

    @Test
    void runProcessCapturesStdoutAndStderr() throws Exception {
        PlaywrightTool tool = tool(30);
        Path dir = Files.createTempDirectory("qalab-pw-output");

        PlaywrightTool.ProcessOutcome outcome = tool.runProcess(
                List.of("sh", "-c", "echo OUT-MARKER; echo ERR-MARKER 1>&2"), dir);

        assertTrue(outcome.completed());
        assertTrue(outcome.output().contains("OUT-MARKER"), "stdout missing: " + outcome.output());
        assertTrue(outcome.output().contains("ERR-MARKER"), "stderr must be merged: " + outcome.output());
    }

    @Test
    void executeRejectsRunWithNoTarget() {
        PlaywrightTool tool = tool(5);
        ToolContext context = new ToolContext().put("runAll", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(context);

        assertTrue(String.valueOf(result.get("error")).contains("No test file specified"));
    }

    @Test
    void executeAlwaysReturnsAStructuredStatusAndNeverHangs() {
        PlaywrightTool tool = tool(120);
        ToolContext context = new ToolContext()
                .put("runAll", true)
                .put("workspacePath", tempDir().toString());

        long start = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(context);
        long elapsed = System.currentTimeMillis() - start;

        // An empty temp dir has no Playwright config; whatever npx does, the call must
        // return a structured status within the budget rather than hanging or throwing.
        assertNotNull(result.get("status"), "result: " + result);
        assertTrue(VALID_STATUSES.contains(result.get("status")), "unexpected status: " + result);
        assertNotNull(result.get("duration"));
        assertTrue(elapsed < 130_000, "execute must respect its budget, took " + elapsed + "ms");
    }

    @Test
    void readTailKeepsOnlyTheEndOfLargeOutput() throws IOException {
        PlaywrightTool tool = tool(5);
        Path file = Files.createTempFile("tail", ".log");
        String head = "BANNER-HEAD\n".repeat(30_000); // ~330k chars, over the 200k cap
        String tail = "FINAL-LINE-UNIQUE-MARKER\n";
        Files.writeString(file, head + tail);

        String out = ReflectionTestUtils.invokeMethod(tool, "readTail", file);

        assertTrue(out.contains("FINAL-LINE-UNIQUE-MARKER"), "tail must be retained");
        assertTrue(out.startsWith("(output truncated"),
                "truncation must be announced, was: " + out.substring(0, Math.min(60, out.length())));
        assertTrue(out.length() < head.length(), "output must be smaller than the original");
    }

    @Test
    void readTailReturnsWholeSmallFile() throws IOException {
        PlaywrightTool tool = tool(5);
        Path file = Files.createTempFile("tail-small", ".log");
        Files.writeString(file, "line one\nline two\n");

        assertEquals("line one\nline two\n", ReflectionTestUtils.invokeMethod(tool, "readTail", file));
    }

    @Test
    void readTailToleratesMissingFile() {
        PlaywrightTool tool = tool(5);
        assertEquals("", ReflectionTestUtils.invokeMethod(tool, "readTail", Path.of("/nonexistent/qalab.log")));
    }

    private Path tempDir() {
        try {
            return Files.createTempDirectory("qalab-pw-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
