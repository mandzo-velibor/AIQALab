package com.qalab.qalabai.tool.playwright;

import com.qalab.qalabai.tool.Tool;
import com.qalab.qalabai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

            ProcessOutcome outcome = runProcess(command, runPath);
            long duration = outcome.durationMs();

            if (!outcome.completed()) {
                log.warn("Playwright exceeded {}s budget and was terminated", timeoutSeconds);
                Map<String, Object> timedOut = new HashMap<>();
                timedOut.put("status", "TIMEOUT");
                timedOut.put("duration", duration);
                timedOut.put("timeoutSeconds", timeoutSeconds);
                timedOut.put("output", outcome.output());
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

            log.info("Playwright execution completed: status={}, duration={}ms", status, duration);
            return result;

        } catch (Exception e) {
            log.error("Playwright execution failed: {}", e.getMessage());
            return Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()
            );
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
        Path outputFile = Files.createTempFile("qalab-playwright-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
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
