package com.qalab.qalabai.tool.playwright;

import com.qalab.qalabai.observability.AiMetrics;
import com.qalab.qalabai.tool.Tool;
import com.qalab.qalabai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qalab.qalabai.service.report.AllureReportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PlaywrightTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightTool.class);

    @Value("${qalab.tests-dir:./tests}")
    private String testsDir;

    /**
     * Wall-clock budget for a single Playwright run. Generous by default because a
     * full-suite run with video/trace legitimately takes minutes, but always finite
     * so a hung run cannot pin a request thread indefinitely.
     */
    @Value("${qalab.playwright.timeout-seconds:600}")
    private long timeoutSeconds;

    /** Cap on retained subprocess output; only the tail is kept for diagnostics. */
    private static final int MAX_OUTPUT_CHARS = 200_000;

    /**
     * Allure support, additive and optional. {@code allure-playwright} is just another
     * reporter, so it slots into the companion config exactly where the JSON reporter does
     * — the user's own config still is never touched, and no dependency is added to their
     * project. Absent the package, the config is byte-for-byte what it was before.
     */
    private final AllureReportService allure;

    public PlaywrightTool(AllureReportService allure) {
        this.allure = allure;
    }

    /**
     * Name of the generated companion config. Lives at the workspace root (see
     * {@link #writeReportingConfig}) and is removed after the run.
     */
    static final String COMPANION_CONFIG_NAME = "playwright.qalab-report.config.ts";

    @Override
    public String getName() {
        return "PlaywrightTool";
    }

    /**
     * Reads at most {@link #MAX_OUTPUT_CHARS} from the end of the subprocess log.
     * Playwright prints its per-test summary last, so the tail is the informative
     * part; the head is mostly the banner and the run header.
     */
    private String readTail(Path outputFile) {
        try {
            if (!Files.exists(outputFile)) {
                return "";
            }
            long size = Files.size(outputFile);
            if (size <= MAX_OUTPUT_CHARS) {
                return Files.readString(outputFile);
            }
            try (var in = Files.newInputStream(outputFile)) {
                in.skipNBytes(size - MAX_OUTPUT_CHARS);
                return "(output truncated, showing last " + MAX_OUTPUT_CHARS + " chars)\n"
                        + new String(in.readNBytes(MAX_OUTPUT_CHARS), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Could not read Playwright output file {}: {}", outputFile, e.getMessage());
            return "";
        }
    }

    @Override
    public Object execute(ToolContext context) {
        String testFile = context.getString("testFile");
        boolean runAll = context.get("runAll") != null && (Boolean) context.get("runAll");
        String workspacePath = context.getString("workspacePath");

        @SuppressWarnings("unchecked")
        List<String> testFiles = (List<String>) context.get("testFiles");

        log.info("PlaywrightTool executing: testFile={}, testFiles={}, runAll={}, workspacePath={}",
                testFile, testFiles, runAll, workspacePath);

        try {
            Path runPath = workspacePath != null && !workspacePath.isBlank()
                    ? Paths.get(workspacePath)
                    : Paths.get(testsDir);
            if (!Files.exists(runPath)) {
                Files.createDirectories(runPath);
            }

            List<String> command;
            if (runAll) {
                command = new ArrayList<>(List.of("npx", "playwright", "test"));
            } else if (testFiles != null && !testFiles.isEmpty()) {
                command = new ArrayList<>();
                command.add("npx");
                command.add("playwright");
                command.add("test");
                command.addAll(testFiles);
            } else if (testFile != null && !testFile.isBlank()) {
                command = new ArrayList<>(List.of("npx", "playwright", "test", testFile));
            } else {
                return Map.of("error", "No test file specified and runAll is false");
            }

            // Capture structured per-test results alongside the human output. A
            // companion config adds the JSON reporter without touching the user's own
            // playwright.config.ts (B-020's contract).
            Path jsonReport = Files.createTempFile("qalab-playwright-report-", ".json");
            Path allureResults = allure.isAvailable(runPath) ? allure.resultsDir(runPath) : null;
            if (allureResults != null) {
                // Clear anything left by a previous run. Allure appends to an existing
                // results directory, so a stale result from an earlier run would show up
                // in this run's report as though it had just happened.
                deleteRecursively(allureResults);
            }
            Path reportingConfig = writeReportingConfig(runPath, jsonReport, allureResults);

            ProcessOutcome outcome = runProcess(command, runPath, reportingConfig);
            long duration = outcome.durationMs();
            PlaywrightResultParser.RunSummary summary =
                    PlaywrightResultParser.parse(jsonReport);

            if (!outcome.completed()) {
                log.warn("Playwright exceeded {}s budget and was terminated", timeoutSeconds);
                Map<String, Object> timedOut = new HashMap<>();
                timedOut.put("status", "TIMEOUT");
                timedOut.put("duration", duration);
                timedOut.put("timeoutSeconds", timeoutSeconds);
                timedOut.put("output", outcome.output());
                timedOut.put("summary", summary);
                timedOut.put("error", "Playwright run exceeded the " + timeoutSeconds
                        + "s budget and was terminated. Narrow the run (testType filter, single test) "
                        + "or raise qalab.playwright.timeout-seconds.");
                return timedOut;
            }

            String status = outcome.exitCode() == 0 ? "PASSED" : "FAILED";

            Map<String, Object> result = new HashMap<>();
            result.put("status", status);
            result.put("duration", duration);
            result.put("exitCode", outcome.exitCode());
            result.put("output", outcome.output());
            result.put("summary", summary);

            log.info("Playwright execution completed: status={}, duration={}ms, passed={}, failed={}, "
                            + "skipped={}, flaky={}",
                    status, duration, summary.passed(), summary.failed(),
                    summary.skipped(), summary.flaky());
            recordMetrics(status, duration, summary);
            try {
                Files.deleteIfExists(jsonReport);
                if (reportingConfig != null) {
                    Files.deleteIfExists(reportingConfig);
                }
            } catch (IOException e) {
                log.debug("Could not remove the Playwright reporting temp files: {}", e.getMessage());
            }
            return result;

        } catch (Exception e) {
            log.error("Playwright execution failed: {}", e.getMessage());
            return Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()
            );
        }
    }


    /**
     * Writes a companion Playwright config that adds a JSON reporter alongside
     * whatever the workspace already uses.
     *
     * <p>Two constraints shaped this. First, {@code --reporter=json:<file>} is not
     * supported on the Playwright version this project targets (1.48 treats the
     * value as a module name), so the reporter has to be configured rather than
     * passed on the command line. Second, a workspace's own
     * {@code playwright.config.ts} belongs to the user and is never modified — so
     * this generates a separate file that imports the user's config, spreads it, and
     * appends the reporter. The run is then pointed at the companion with
     * {@code --config}.</p>
     *
     * @return the companion config path, or null when one could not be written
     */
    Path writeReportingConfig(Path workspace, Path jsonOutput) {
        return writeReportingConfig(workspace, jsonOutput, null);
    }

    /**
     * @param allureResults when non-null, adds the Allure reporter pointed at this
     *                      directory; the caller has already confirmed the workspace
     *                      has {@code allure-playwright} installed
     */
    Path writeReportingConfig(Path workspace, Path jsonOutput, Path allureResults) {
        try {
            // Written at the workspace ROOT, not a subdirectory, for two reasons:
            // Node resolves node_modules by walking up from the importing file, and
            // Playwright defaults testDir to the config file's own directory. A
            // companion in a subdirectory would therefore break both for any
            // workspace that does not set testDir explicitly.
            Path config = workspace.resolve(COMPANION_CONFIG_NAME);
            Path userConfig = findUserConfig(workspace);

            String contents = userConfig == null
                    ? """
                    import { defineConfig } from '@playwright/test';

                    // Generated by QALabAI to capture structured per-test results.
                    // It extends whatever the workspace uses and only ADDS a reporter.
                    export default defineConfig({
                      testDir: './tests',
                      reporter: [['list'], ['json', { outputFile: %s }]%s],
                    });
                    """.formatted(jsonLiteral(jsonOutput), allureReporterFragment(workspace, allureResults))
                    : """
                    import base from '%s';
                    import { defineConfig } from '@playwright/test';

                    // Generated by QALabAI to capture structured per-test results.
                    // It extends the workspace's own config and only ADDS a reporter,
                    // so the user's settings (workers, retries, artifact profile)
                    // are all preserved.
                    const existing = Array.isArray(base.reporter) ? base.reporter : [];
                    export default defineConfig({
                      ...base,
                      reporter: [...existing, ['json', { outputFile: %s }]%s],
                    });
                    """.formatted(relativeImport(workspace, userConfig), jsonLiteral(jsonOutput),
                    allureReporterFragment(workspace, allureResults));

            Files.writeString(config, contents);
            return config;
        } catch (IOException e) {
            log.warn("Could not write the Playwright reporting config: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The Allure reporter entry, or an empty string when Allure is not in play.
     *
     * <p>{@code resultsDir} is set explicitly so the results land in a dot-directory
     * rather than the workspace root. Left at the default they would appear as a stray
     * {@code allure-results/} next to the user's source, which is exactly the litter a
     * tool should not leave behind.
     */
    private String allureReporterFragment(Path workspace, Path allureResults) {
        if (allureResults == null) {
            return "";
        }
        return ", ['allure-playwright', { resultsDir: %s }]".formatted(
                quoteJs(relativeDir(workspace, allureResults)));
    }

    /** Quotes a path for a JS string literal. */
    private static String quoteJs(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** A POSIX-style path relative to the workspace, as a config file expects. */
    private static String relativeDir(Path workspace, Path target) {
        try {
            return workspace.relativize(target).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return target.toAbsolutePath().toString().replace('\\', '/');
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.debug("Could not clear {}: {}", dir, e.getMessage());
        }
    }

    private Path findUserConfig(Path workspace) {
        for (String name : List.of("playwright.config.ts", "playwright.config.js",
                "playwright.config.mts", "playwright.config.mjs")) {
            Path candidate = workspace.resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Import specifier for the user's config, relative to the companion file.
     *
     * <p>The {@code ./} prefix is essential: {@link Path#relativize} returns
     * {@code playwright.config.ts} for a sibling, which Node treats as a bare module
     * specifier and tries to resolve from {@code node_modules} instead of as a
     * relative path.</p>
     */
    private String relativeImport(Path workspace, Path userConfig) {
        String specifier;
        try {
            specifier = workspace.relativize(userConfig).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            specifier = userConfig.toAbsolutePath().toString().replace('\\', '/');
        }
        return specifier.startsWith(".") ? specifier : "./" + specifier;
    }

    private String jsonLiteral(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * Records the run for dashboards.
     *
     * <p>Static rather than injected: the tool is constructed directly in tests and in
     * {@link com.qalab.qalabai.service.workspace.WorkspaceManager}, and making metrics a
     * constructor dependency would mean every one of those call sites changes for no
     * benefit. The no-op fallback keeps this safe when no registry is present.</p>
     */
    private void recordMetrics(String status, long durationMs, PlaywrightResultParser.RunSummary summary) {
        AiMetrics metrics = AiMetrics.current();
        if (metrics != null) {
            try {
                metrics.recordPlaywrightRun(status, durationMs, summary.total(), summary.failed());
            } catch (RuntimeException e) {
                log.debug("Could not record Playwright metrics: {}", e.getMessage());
            }
        }
    }

    /** Outcome of a bounded subprocess run. */
    record ProcessOutcome(boolean completed, int exitCode, long durationMs, String output) {
    }

    /**
     * Runs {@code command} in {@code workingDir} under a hard wall-clock budget.
     *
     * <p>The child's stdout/stderr are redirected to a temp file rather than drained
     * on the calling thread. Draining inline blocks until the process exits, which
     * would make the {@code waitFor} budget unreachable and let a hung run pin the
     * request thread forever — the bug this method exists to prevent.
     */
    ProcessOutcome runProcess(List<String> command, Path workingDir) throws IOException, InterruptedException {
        return runProcess(command, workingDir, null);
    }

    /**
     * @param configOverride when set, appended as {@code --config=<path>} so the run
     *                       uses a companion config without modifying the user's own
     */
    ProcessOutcome runProcess(List<String> command, Path workingDir, Path configOverride)
            throws IOException, InterruptedException {
        List<String> effective = configOverride == null ? command : new ArrayList<>(command);
        if (configOverride != null) {
            effective.add("--config=" + configOverride.toAbsolutePath());
        }
        Path outputFile = Files.createTempFile("qalab-playwright-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(effective);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());

            long startTime = System.currentTimeMillis();
            Process process = pb.start();
            log.info("Playwright started (pid={}), budget {}s, output -> {}",
                    process.pid(), timeoutSeconds, outputFile);

            boolean completed;
            try {
                completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw e;
            }
            long duration = System.currentTimeMillis() - startTime;

            if (!completed) {
                process.destroyForcibly();
                // Give the OS a moment to reap the process tree before reading output.
                process.waitFor(5, TimeUnit.SECONDS);
                return new ProcessOutcome(false, -1, duration, readTail(outputFile));
            }
            return new ProcessOutcome(true, process.exitValue(), duration, readTail(outputFile));
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException e) {
                log.debug("Could not delete temp output file {}: {}", outputFile, e.getMessage());
            }
        }
    }
}
