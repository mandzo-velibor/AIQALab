package com.qalab.qalabai.healing.context;

import com.qalab.qalabai.healing.classification.FailureClassifier;
import com.qalab.qalabai.healing.model.ExecutionTestResult;
import com.qalab.qalabai.healing.model.FailureContext;
import com.qalab.qalabai.model.TestExecution;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link FailureContext} from an {@link ExecutionTestResult} or a
 * persisted {@link TestExecution}. The context is partial-tolerant: whatever is
 * missing stays null and the pipeline adapts.
 *
 * <p>When built from a persisted execution, real failure data that Playwright
 * printed to the console is recovered: the failing locator and the test title.
 * The page URL comes from the registered project so the live DOM can be fetched
 * during healing analysis.</p>
 */
@Component
public class FailureContextFactory {

    private static final Pattern TEST_TITLE = Pattern.compile("›\\s*([^\\n]+)");
    private static final Pattern WAITING_FOR = Pattern.compile("waiting for\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern ANY_LOCATOR = Pattern.compile(
            "(getByRole\\([^)]*\\)|getByTestId\\([^)]*\\)|getByText\\([^)]*\\)"
                    + "|getByPlaceholder\\([^)]*\\)|getByLabel\\([^)]*\\)|locator\\([^)]*\\))");

    private final FailureClassifier classifier;

    public FailureContextFactory(FailureClassifier classifier) {
        this.classifier = classifier;
    }

    public FailureContext fromExecutionTestResult(Long projectId, String runId, ExecutionTestResult result) {
        FailureClassifier.Classification classification = classifier.classify(result);

        FailureContext context = new FailureContext();
        context.setProjectId(projectId);
        context.setRunId(runId);
        context.setTestId(result.testId());
        context.setTestName(result.testName());
        context.setTestFile(result.testFile());
        context.setSourceLine(result.sourceLine());
        context.setSourceCode(result.sourceCode());
        context.setAction(result.action());
        context.setOriginalLocator(result.locator());
        context.setError(result.error());
        context.setStackTrace(result.stackTrace());
        context.setLogs(result.consoleLogs());
        context.setScreenshot(result.screenshot());
        context.setTrace(result.trace());
        context.setVideo(result.video());
        context.setCurrentUrl(result.currentUrl());
        context.setPageTitle(result.pageTitle());
        context.setDomSnapshot(result.domSnapshot());
        context.setExecutionTimestamp(result.timestamp());
        context.setClassification(classification.type());
        context.setClassificationConfidence(classification.confidence());
        context.setClassificationReason(classification.reason());
        return context;
    }

    /**
     * Builds a context from a persisted execution. Real failure signals that
     * Playwright printed to the console are recovered (locator, test title);
     * {@code baseUrl} is the registered project URL used for live DOM capture.
     */
    public FailureContext fromExecution(Long projectId, String runId, TestExecution execution, String baseUrl) {
        ExecutionTestResult result = new ExecutionTestResult(
                null,
                execution.getTestFile(),
                execution.getTestFile(),
                execution.getStatus(),
                null,
                null,
                null,
                null,
                execution.getErrorMessage(),
                null,
                execution.getConsoleLogs(),
                execution.getScreenshotPath(),
                execution.getTracePath(),
                execution.getVideoPath(),
                baseUrl,
                null,
                null,
                execution.getCreatedAt()
        );
        FailureContext context = fromExecutionTestResult(projectId, runId, result);

        String searchable = join(execution.getErrorMessage(), execution.getConsoleLogs());
        String locator = extractLocator(searchable);
        if (locator != null) {
            context.setOriginalLocator(locator);
        }
        String title = extractTestTitle(execution.getConsoleLogs());
        if (title != null) {
            context.setTestName(title);
        }
        if (context.getCurrentUrl() == null) {
            context.setCurrentUrl(baseUrl);
        }
        return context;
    }

    /** Recovers the failing locator Playwright printed, e.g. "waiting for getByRole(...)". */
    public static String extractLocator(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher waiting = WAITING_FOR.matcher(text);
        if (waiting.find()) {
            String locator = waiting.group(1).trim();
            locator = locator.replaceAll("\\u001B\\[[;0-9]*m", "").trim();
            if (!locator.isBlank() && !locator.startsWith("**") && !locator.startsWith("*")) {
                return locator;
            }
        }
        Matcher any = ANY_LOCATOR.matcher(text);
        if (any.find()) {
            return any.group(1);
        }
        return null;
    }

    /** Recovers the test title Playwright printed, e.g. "…› Login with valid credentials (10s)". */
    public static String extractTestTitle(String consoleLogs) {
        if (consoleLogs == null || consoleLogs.isBlank()) {
            return null;
        }
        Matcher matcher = TEST_TITLE.matcher(consoleLogs);
        if (matcher.find()) {
            String title = matcher.group(1).trim();
            title = title.replaceAll("\\s+\\([0-9.]+s?\\)\\s*$", "").trim();
            if (!title.isBlank()) {
                return title;
            }
        }
        return null;
    }

    private String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                sb.append(part).append("\n");
            }
        }
        return sb.toString();
    }
}
