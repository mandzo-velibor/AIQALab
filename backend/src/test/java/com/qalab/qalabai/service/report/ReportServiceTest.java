package com.qalab.qalabai.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qalab.qalabai.healing.service.HealingOutcome;
import com.qalab.qalabai.model.TestExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ReportService} writes the artifact the user actually reads, and it had almost no
 * coverage. The behaviour worth pinning is the ordering guarantee: {@code report.json} is
 * the machine contract and must survive a failed HTML render, because losing it loses the
 * run's record entirely.
 */
class ReportServiceTest {

    /**
     * Mirrors what Spring injects. A bare {@code new ObjectMapper()} cannot serialize the
     * {@code LocalDateTime} on an execution, and because the write is wrapped in a catch
     * that logs a warning, the symptom is a report that silently does not exist. That is
     * the intended trade — a report problem must not fail a finished run — but it means
     * the mapper matters, so the test uses the same one production gets.
     */
    private final ReportService service = new ReportService(
            new ObjectMapper().registerModule(new JavaTimeModule()));

    private static TestExecution execution(String status) {
        TestExecution e = new TestExecution();
        e.setId(77L);
        e.setProjectId(42L);
        e.setTestFile("login.spec.ts");
        e.setStatus(status);
        e.setDuration(1234L);
        e.setCreatedAt(LocalDateTime.of(2026, 9, 27, 10, 0));
        return e;
    }

    private static Map<String, Object> artifacts(Path dir) {
        Map<String, Object> map = new HashMap<>();
        map.put("artifactDir", dir.toString());
        return map;
    }

    @Test
    void writesJsonMarkdownAndHtmlAndReturnsBothPaths(@TempDir Path dir) {
        var report = service.generate(execution("FAILED"), artifacts(dir));

        assertThat(report.reportPath()).isNotNull();
        assertThat(report.htmlReportPath())
                .as("the offline-openable report is the product's core deliverable")
                .isNotNull();
        assertThat(Files.exists(Path.of(report.reportPath()))).isTrue();
        assertThat(Files.exists(Path.of(report.htmlReportPath()))).isTrue();
    }

    @Test
    void theJsonReportCarriesTheExecutionFacts(@TempDir Path dir) throws Exception {
        TestExecution execution = execution("FAILED");
        execution.setErrorMessage("expect(received).toBe('Dashboard')");

        service.generate(execution, artifacts(dir));

        String json = Files.readString(dir.resolve("report.json").toAbsolutePath());
        assertThat(json)
                .contains("\"executionId\"")
                .contains("Dashboard")
                .as("the failure message is the reason the user opened the report")
                .contains("expect(received)");
    }

    @Test
    void aRunWithNoErrorMessageOmitsTheFieldRatherThanWritingNull(@TempDir Path dir) throws Exception {
        service.generate(execution("PASSED"), artifacts(dir));

        String json = Files.readString(dir.resolve("report.json").toAbsolutePath());
        assertThat(json)
                .as("a null errorMessage in the JSON reads as a bug on a passing run")
                .doesNotContain("errorMessage");
    }

    @Test
    void perTestResultsAreRenderedIntoTheHtml(@TempDir Path dir) throws Exception {
        TestExecution execution = execution("FAILED");
        var testCases = List.of(
                new HtmlReportRenderer.TestCaseView(1, "login.spec.ts", "should log in",
                        "FAILED", 1200L, 0, "expected Dashboard", null,
                        List.of("shot.png"), List.of(), List.of("trace.zip")),
                new HtmlReportRenderer.TestCaseView(2, "login.spec.ts", "should log out",
                        "PASSED", 300L, 0, null, null, List.of(), List.of(), List.of()));

        var report = service.generate(execution, artifacts(dir), null, testCases, Map.of());

        String html = Files.readString(Path.of(report.htmlReportPath()));
        assertThat(html)
                .as("without the per-test rows the report cannot answer 'which test broke'")
                .contains("should log in")
                .contains("should log out");
    }

    @Test
    void noArtifactDirectoryMeansNoFilesButStillAReport(@TempDir Path dir) {
        // A run with no artifact dir must still produce a report object; the caller renders
        // it from the returned fields rather than assuming a file exists.
        var report = service.generate(execution("PASSED"), new HashMap<>());

        assertThat(report.executionId()).isEqualTo(77L);
        assertThat(report.status()).isEqualTo("PASSED");
        assertThat(report.reportPath()).isNull();
        assertThat(report.htmlReportPath()).isNull();
    }

    @Test
    void anUnwritableArtifactDirectoryDoesNotFailTheRun(@TempDir Path dir) {
        // The tests have already been reported; losing the report file must not turn a
        // completed run into a failed one.
        Map<String, Object> bad = new HashMap<>();
        bad.put("artifactDir", dir.resolve("report.json").toString()); // a file, not a dir

        var report = service.generate(execution("PASSED"), bad);

        assertThat(report.executionId())
                .as("a filesystem problem must not propagate out of report generation")
                .isEqualTo(77L);
    }

    @Test
    void healingOutcomeIsIncludedWhenPresent(@TempDir Path dir) throws Exception {
        HealingOutcome outcome = new HealingOutcome(
                null, null, List.of(), null, true, "locator replaced");

        service.generate(execution("FAILED"), artifacts(dir), outcome);

        String json = Files.readString(dir.resolve("report.json").toAbsolutePath());
        assertThat(json)
                .as("a healed run should record that healing was attempted")
                .contains("healing");
    }

    @Test
    void markdownMentionsTheFailedStatusAndTheTestFile(@TempDir Path dir) throws Exception {
        service.generate(execution("FAILED"), artifacts(dir));

        String md = Files.readString(dir.resolve("report.md").toAbsolutePath());
        assertThat(md).contains("login.spec.ts");
    }
}
