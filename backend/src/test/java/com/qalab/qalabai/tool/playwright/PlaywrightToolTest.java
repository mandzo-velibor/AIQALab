package com.qalab.qalabai.tool.playwright;

import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.service.report.AllureReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
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
        PlaywrightTool tool = new PlaywrightTool(new com.qalab.qalabai.service.report.AllureReportService(true));
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

    // ---- B-022: structured results need a reporter the run actually emits ----
    //
    // --reporter=json:<file> is unsupported on the targeted Playwright (1.48 treats
    // the value as a module name), so the JSON reporter has to be configured. The
    // workspace's own playwright.config.ts belongs to the user, so the runner writes
    // a separate companion config and points the run at it via --config.

    @Test
    void reportingConfigExtendsTheUsersConfigWithoutModifyingIt() throws IOException {
        PlaywrightTool tool = tool(30);
        Path ws = tempDir();
        Path userConfig = ws.resolve("playwright.config.ts");
        String original = "export default { testDir: './e2e', retries: 2, workers: 1 };\n";
        Files.writeString(userConfig, original);

        Path json = ws.resolve("out.json");
        Path companion = tool.writeReportingConfig(ws, json);

        assertNotNull(companion, "a companion config must be written");
        assertEquals(ws.resolve(PlaywrightTool.COMPANION_CONFIG_NAME), companion,
                "the companion must sit at the workspace root so node module resolution "
                        + "and Playwright's default testDir behave as they do for the "
                        + "user's own config");

        String generated = Files.readString(companion);
        assertTrue(generated.contains("import base from './playwright.config.ts'"),
                "must import the user config relatively: " + generated);
        assertTrue(generated.contains("...base"), "must spread the user config: " + generated);
        assertTrue(generated.contains("['json'"), "must add the json reporter: " + generated);
        assertTrue(generated.contains("existing"), "must preserve the user's own reporters: " + generated);

        // The critical contract: the user's file is untouched, so its workers, retries
        // and artifact profile all keep applying to the run.
        assertEquals(original, Files.readString(userConfig),
                "the user's playwright.config.ts must not be modified");
    }

    @Test
    void reportingConfigFallsBackToTestDirWhenTheWorkspaceHasNoConfig() throws IOException {
        PlaywrightTool tool = tool(30);
        Path ws = tempDir();

        Path companion = tool.writeReportingConfig(ws, ws.resolve("out.json"));

        assertNotNull(companion);
        String generated = Files.readString(companion);
        assertFalse(generated.contains("import base"), "nothing to import: " + generated);
        assertTrue(generated.contains("testDir"), "must still be a usable config: " + generated);
        assertTrue(generated.contains("'json'"), "must add the json reporter: " + generated);
    }

    @Test
    void reportingConfigSupportsJavaScriptWorkspacesToo() throws IOException {
        PlaywrightTool tool = tool(30);
        Path ws = tempDir();
        Files.writeString(ws.resolve("playwright.config.js"), "module.exports = {};");

        String generated = Files.readString(tool.writeReportingConfig(ws, ws.resolve("o.json")));

        // A bare "playwright.config.js" would be resolved from node_modules, not
        // relative to the companion, so the ./ prefix is part of the contract.
        assertTrue(generated.contains("import base from './playwright.config.js'"), generated);
    }

    @Test
    void runProcessPassesTheCompanionConfigToTheRun() throws Exception {
        PlaywrightTool tool = tool(30);
        Path ws = tempDir();
        // Echo the argv the runner would have used, so the flag can be asserted.
        Path companion = ws.resolve("companion.ts");
        Files.writeString(companion, "// config");

        PlaywrightTool.ProcessOutcome outcome =
                tool.runProcess(List.of("sh", "-c", "for a in \"$@\"; do echo \"arg=$a\"; done",
                        "sh", "base", "--reporter=list"), ws, companion);

        assertTrue(outcome.output().contains("arg=base"), outcome.output());
        assertTrue(outcome.output().contains("arg=--reporter=list"), outcome.output());
        assertTrue(outcome.output().contains("arg=--config=" + companion.toAbsolutePath()),
                "the run must be pointed at the companion config: " + outcome.output());
    }

    @Test
    void runProcessOmitsTheConfigFlagWhenThereIsNoOverride() throws Exception {
        PlaywrightTool tool = tool(30);
        PlaywrightTool.ProcessOutcome outcome = tool.runProcess(
                List.of("sh", "-c", "for a in \"$@\"; do echo \"arg=$a\"; done", "sh", "base"),
                tempDir());

        assertFalse(outcome.output().contains("--config="),
                "an existing caller path must not gain a config flag: " + outcome.output());
    }

    private Path tempDir() {
        try {
            return Files.createTempDirectory("qalab-pw-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * B-030: the Allure reporter is added to the companion config only when the workspace
     * already has {@code allure-playwright}. Both directions matter — adding it
     * unconditionally would make a reporter load that does not exist, and the user is never
     * given the dependency in the first place.
     */
    @Test
    void theCompanionConfigIsUnchangedWhenAllureIsAbsent(@TempDir Path workspace) throws Exception {
        PlaywrightTool tool = new PlaywrightTool(new AllureReportService(true));
        Path json = Files.createTempFile("results", ".json");

        String config = Files.readString(tool.writeReportingConfig(workspace, json, null));

        // A complete no-op, not merely a smaller change: the absence of Allure must not
        // alter a byte of what the user's run is otherwise configured with.
        assertThat(config)
                .doesNotContain("allure")
                .contains("'json'");
    }

    @Test
    void theCompanionConfigAddsTheAllureReporterWhenAvailable(@TempDir Path workspace)
            throws Exception {
        PlaywrightTool tool = new PlaywrightTool(new AllureReportService(true));
        Path json = Files.createTempFile("results", ".json");
        AllureReportService allure = new AllureReportService(true);

        String config = Files.readString(
                tool.writeReportingConfig(workspace, json, allure.resultsDir(workspace)));

        assertThat(config)
                .contains("allure-playwright")
                .contains("resultsDir")
                .contains(".qalab/allure-results");
        assertThat(config)
                .as("the JSON reporter must survive alongside it: the bespoke report and the "
                        + "per-test results both depend on it")
                .contains("'json'");
    }

    @Test
    void aUsersOwnConfigIsNeverTouchedWhenAllureIsActive(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("playwright.config.ts"),
                "export default { testDir: './tests', workers: 1 };");
        PlaywrightTool tool = new PlaywrightTool(new AllureReportService(true));
        AllureReportService allure = new AllureReportService(true);
        String before = Files.readString(workspace.resolve("playwright.config.ts"));

        Files.readString(tool.writeReportingConfig(workspace,
                Files.createTempFile("r", ".json"), allure.resultsDir(workspace)));

        assertThat(Files.readString(workspace.resolve("playwright.config.ts")))
                .as("B-020's contract: the user's config is never modified")
                .isEqualTo(before);
    }
}
