package com.qalab.qalabai.tool.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses Playwright's JSON reporter output into structured per-test results.
 *
 * <p>Until now the workflow only ever saw a truncated blob of Playwright's human
 * output, so it could not say <em>which</em> tests failed, could not attach a
 * screenshot to a specific failure, and the CLI had to grep for a bullet character.
 * Everything downstream — the HTML report, Allure, per-failure bug reports — needs
 * structure first.</p>
 *
 * <p>Playwright reports a <em>spec</em> with one or more <em>tests</em> (a retry
 * policy produces several) each having <em>results</em> (attempts). The last result
 * is the one that decides the outcome, and earlier ones are the retries, so the
 * parser reports the final attempt and counts the retries.</p>
 */
public final class PlaywrightResultParser {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightResultParser.class);

    /** ANSI colour codes, which Playwright embeds in error messages. */
    private static final Pattern ANSI = Pattern.compile("\\[[;\\d]*m");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlaywrightResultParser() {
    }

    /** One test's outcome. */
    public record TestCaseResult(
            String file,
            String title,
            String fullTitle,
            String status,
            long durationMs,
            int retries,
            String errorMessage,
            String errorSnippet,
            List<String> screenshots,
            List<String> videos,
            List<String> traces
    ) {
        public boolean failed() {
            return "failed".equals(status);
        }

        public boolean skipped() {
            return "skipped".equals(status);
        }
    }

    /** Aggregate counts plus the per-test detail. */
    public record RunSummary(
            int passed,
            int failed,
            int skipped,
            int flaky,
            long durationMs,
            List<TestCaseResult> tests,
            List<String> globalErrors
    ) {
        public int total() {
            return passed + failed + skipped;
        }
    }

    /**
     * Parses the reporter file. Returns an empty-but-valid summary when the file is
     * missing or unparseable, so a reporting problem never turns into a lost run —
     * the caller still has the exit code and the text output.
     */
    public static RunSummary parse(Path jsonFile) {
        if (jsonFile == null || !Files.exists(jsonFile)) {
            log.warn("Playwright JSON report not found at {}; per-test results unavailable", jsonFile);
            return empty();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(jsonFile));
            return fromRoot(root);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not parse Playwright JSON report {}: {}", jsonFile, e.getMessage());
            return empty();
        }
    }

    static RunSummary fromRoot(JsonNode root) {
        List<TestCaseResult> tests = new ArrayList<>();
        collectSuites(root.path("suites"), tests);

        JsonNode stats = root.path("stats");
        int passed = stats.path("expected").asInt(0);
        int failed = stats.path("unexpected").asInt(0);
        int skipped = stats.path("skipped").asInt(0);
        int flaky = stats.path("flaky").asInt(0);

        // Prefer the reporter's own counts, but do not trust them blindly: if suites
        // were parsed and the counts disagree, the detail is the stronger signal.
        if (!tests.isEmpty()) {
            int byStatusPassed = 0;
            int byStatusFailed = 0;
            int byStatusSkipped = 0;
            for (TestCaseResult t : tests) {
                if (t.failed()) byStatusFailed++;
                else if (t.skipped()) byStatusSkipped++;
                else byStatusPassed++;
            }
            passed = byStatusPassed;
            failed = byStatusFailed;
            skipped = byStatusSkipped;
        }

        List<String> globalErrors = new ArrayList<>();
        for (JsonNode err : root.path("errors")) {
            String message = clean(err.path("message").asText(null));
            if (message != null && !message.isBlank()) {
                globalErrors.add(message);
            }
        }

        return new RunSummary(passed, failed, skipped, flaky,
                (long) stats.path("duration").asDouble(0d), List.copyOf(tests), List.copyOf(globalErrors));
    }

    private static void collectSuites(JsonNode suites, List<TestCaseResult> out) {
        if (suites == null || !suites.isArray()) {
            return;
        }
        for (JsonNode suite : suites) {
            collectSpecs(suite.path("specs"), suite.path("title").asText(""), out);
            collectSuites(suite.path("suites"), out);
        }
    }

    private static void collectSpecs(JsonNode specs, String suiteTitle, List<TestCaseResult> out) {
        if (specs == null || !specs.isArray()) {
            return;
        }
        for (JsonNode spec : specs) {
            String file = spec.path("file").asText("");
            String title = spec.path("title").asText("");
            for (JsonNode test : spec.path("tests")) {
                addTest(file, suiteTitle, title, test, out);
            }
        }
    }

    private static void addTest(String file, String suiteTitle, String title,
                                JsonNode test, List<TestCaseResult> out) {
        JsonNode results = test.path("results");
        if (!results.isArray() || results.isEmpty()) {
            // No attempts recorded (e.g. a test that never ran).
            out.add(new TestCaseResult(file, title, fullTitle(suiteTitle, title),
                    test.path("status").asText("skipped"), 0L, 0, null, null,
                    List.of(), List.of(), List.of()));
            return;
        }

        JsonNode last = results.get(results.size() - 1);
        int retries = results.size() - 1;

        List<String> screenshots = new ArrayList<>();
        List<String> videos = new ArrayList<>();
        List<String> traces = new ArrayList<>();
        for (JsonNode attachment : last.path("attachments")) {
            String path = attachment.path("path").asText(null);
            if (path == null || path.isBlank()) {
                continue;
            }
            String contentType = attachment.path("contentType").asText("");
            if (contentType.startsWith("image/")) {
                screenshots.add(path);
            } else if (contentType.startsWith("video/")) {
                videos.add(path);
            } else if (contentType.contains("zip") || attachment.path("name").asText("").contains("trace")) {
                traces.add(path);
            }
        }

        JsonNode error = last.path("error");
        String message = error.isMissingNode() ? null : clean(error.path("message").asText(null));
        String snippet = error.isMissingNode() ? null : clean(error.path("snippet").asText(null));

        out.add(new TestCaseResult(
                file, title, fullTitle(suiteTitle, title),
                last.path("status").asText("unknown"),
                (long) last.path("duration").asDouble(0d),
                retries, message, snippet,
                List.copyOf(screenshots), List.copyOf(videos), List.copyOf(traces)));
    }

    private static String fullTitle(String suiteTitle, String title) {
        if (suiteTitle == null || suiteTitle.isBlank()) {
            return title;
        }
        // A file-level suite is usually named after the spec; avoid "file › file › test".
        return suiteTitle.equals(title) ? title : suiteTitle + " › " + title;
    }

    private static String clean(String value) {
        return value == null ? null : stripAnsi(value).trim();
    }

    /** Removes ANSI colour sequences so messages are readable in JSON and the terminal. */
    public static String stripAnsi(String value) {
        return value == null ? null : ANSI.matcher(value).replaceAll("");
    }

    private static RunSummary empty() {
        Map<String, Object> none = new LinkedHashMap<>();
        return new RunSummary(0, 0, 0, 0, 0L, List.of(), List.of());
    }
}
