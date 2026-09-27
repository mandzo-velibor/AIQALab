package com.qalab.qalabai.service.report;

import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.playwright.PlaywrightTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end-to-end proof for B-030, and deliberately an <em>opt-in</em> test.
 *
 * <p>It needs a real Chromium, an npm install and a Java runtime for the Allure renderer,
 * so it is skipped unless {@code QALAB_ALLURE_E2E} names a prepared workspace containing
 * {@code allure-playwright}. The logic around it is covered by the fast unit tests; this
 * exists to answer the one question they cannot — does a real run actually produce Allure
 * output, and does the report really render.
 *
 * <p>To run it:
 * <pre>
 * cd /tmp/aw &amp;&amp; npm i -D @playwright/test allure-playwright
 * QALAB_ALLURE_E2E=/tmp/aw mvn test -Dtest=AllureReportServiceE2ETest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "QALAB_ALLURE_E2E", matches = ".+")
class AllureReportServiceE2ETest {

    @Test
    void aRealRunProducesAllureOutputThatRenders() throws Exception {
        Path workspace = Path.of(System.getenv("QALAB_ALLURE_E2E"));
        AllureReportService allure = new AllureReportService(true);

        assertThat(allure.isAvailable(workspace))
                .as("the workspace must have allure-playwright for this test to mean anything")
                .isTrue();

        PlaywrightTool tool = new PlaywrightTool(allure);
        // Constructed directly rather than injected, so the @Value fields are unset. Left
        // at 0 the run is killed before it starts, which looks like an Allure problem and
        // is not one.
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "timeoutSeconds", 300L);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "testsDir",
                workspace.resolve("tests").toString());

        Path artifacts = Files.createTempDirectory("allure-e2e-artifacts");

        // The real execution path, not a hand-written config: this is what proves the
        // reporter is wired in and that the user's own config survives.
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(
                new ToolContext().put("runAll", true)
                        .put("workspacePath", workspace.toString()));

        assertThat(result).as("the Playwright run itself must not fail because of Allure")
                .doesNotContainKey("error");

        AllureReportService.AllureOutcome outcome = allure.publish(workspace, artifacts);

        assertThat(outcome.available()).isTrue();
        assertThat(outcome.resultsPath())
                .as("Allure results must survive into the artifact directory")
                .isNotNull();
        assertThat(Files.list(Path.of(outcome.resultsPath())).count())
                .as("a two-test run produces one result file per test")
                .isGreaterThanOrEqualTo(2L);

        assertThat(outcome.renderNote())
                .as("if rendering failed, the user must be told the command to run: %s",
                        outcome.renderNote())
                .isNull();
        assertThat(outcome.hasReport()).isTrue();
        assertThat(Files.isRegularFile(Path.of(outcome.reportPath()).resolve("index.html")))
                .as("a renderer that exits 0 without a page would produce a report nobody "
                        + "can open, which is why index.html is asserted rather than the exit code")
                .isTrue();
    }
}
