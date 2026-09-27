package com.qalab.qalabai.agent.executor;

import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.service.workspace.WorkspaceProvider;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.playwright.PlaywrightResultParser;
import com.qalab.qalabai.tool.playwright.PlaywrightTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The seam that broke, exercised for real: a genuine Playwright run through
 * {@link ExecutorAgent}, with a real browser and real screenshots.
 *
 * <p>B-022's verification called the tool directly and called the agent with a stub. Both
 * were green while the feature did nothing in the product, because the two were never
 * run together. This test runs them together, and skips itself when Playwright is not
 * installed rather than failing a build that has no browsers.</p>
 */
@EnabledIf("playwrightAvailable")
class ExecutorAgentRealPlaywrightTest {

    /**
     * Browsers live in a different place per platform — {@code ~/.cache/ms-playwright}
     * on Linux, {@code ~/Library/Caches/ms-playwright} on macOS — and honour
     * {@code PLAYWRIGHT_BROWSERS_PATH}. Checking only the Linux path made this test skip
     * silently on the machine that would have run it.
     */
    static boolean playwrightAvailable() {
        String configured = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (configured != null && !configured.isBlank() && Files.isDirectory(Paths.get(configured))) {
            return true;
        }
        String home = System.getProperty("user.home");
        return Files.isDirectory(Paths.get(home, ".cache", "ms-playwright"))
                || Files.isDirectory(Paths.get(home, "Library", "Caches", "ms-playwright"));
    }

    @Test
    void aRealRunArrivesAtTheAgentAsStructuredResults() throws Exception {
        Path ws = Files.createTempDirectory("qalab-agent-e2e-");
        Files.createDirectories(ws.resolve("tests"));

        Path donor = Paths.get("workspaces", "project-2", "node_modules").toAbsolutePath();
        if (Files.isDirectory(donor)) {
            Files.createSymbolicLink(ws.resolve("node_modules"), donor);
        }
        Files.writeString(ws.resolve("playwright.config.ts"), """
                import { defineConfig } from '@playwright/test';
                export default defineConfig({
                  testDir: './tests',
                  reporter: [['line']],
                  use: { headless: true, screenshot: 'on', video: 'off', trace: 'on' },
                });
                """);
        Files.writeString(ws.resolve("tests/a.spec.ts"), """
                import { test, expect } from '@playwright/test';
                test('passes after opening a page', async ({ page }) => {
                  await page.setContent('<h1>Hello</h1>');
                  await expect(page.locator('h1')).toBeVisible();
                });
                test('fails after opening a page', async ({ page }) => {
                  await page.setContent('<h1>Hello</h1>');
                  await expect(page.locator('#nope')).toBeVisible();
                });
                """);

        PlaywrightTool tool = new PlaywrightTool(new com.qalab.qalabai.service.report.AllureReportService(true));
        ReflectionTestUtils.setField(tool, "testsDir", "./tests");
        ReflectionTestUtils.setField(tool, "timeoutSeconds", 180);

        // The real tool behind the provider seam, so nothing about the run is faked.
        WorkspaceProvider provider = mock(WorkspaceProvider.class);
        when(provider.execute(any(), any(), anyBoolean(), isNull())).thenAnswer(invocation ->
                (Map<String, Object>) tool.execute(new ToolContext()
                        .put("runAll", true)
                        .put("workspacePath", ws.toString())));

        Task task = new Task("task-1", "RUN_TEST", null);
        task.putContext("projectContext", new ProjectContext());
        task.putContext("runAll", true);

        AgentResult result = new ExecutorAgent(provider).execute(task);

        assertTrue(result.isSuccess(), result.getMessage());
        Object carried = result.getData().get("summary");
        assertTrue(carried instanceof PlaywrightResultParser.RunSummary,
                "a real run must reach the agent as a structured summary, got: " + carried);

        PlaywrightResultParser.RunSummary summary = (PlaywrightResultParser.RunSummary) carried;
        assertEquals(1, summary.passed(), "one of the two specs should pass");
        assertEquals(1, summary.failed(), "one should fail, and it must be attributable");
        assertEquals(2, summary.tests().size());

        PlaywrightResultParser.TestCaseResult failed = summary.tests().stream()
                .filter(PlaywrightResultParser.TestCaseResult::failed)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no failing test in: " + summary.tests()));
        assertEquals("fails after opening a page", failed.title());
        assertEquals(1, failed.screenshots().size(),
                "the failing test must carry its own screenshot, or the report cannot "
                        + "show the user their failure: " + failed);
        assertTrue(Files.isRegularFile(Path.of(failed.screenshots().get(0))),
                "the screenshot path must point at a real file");
    }
}
