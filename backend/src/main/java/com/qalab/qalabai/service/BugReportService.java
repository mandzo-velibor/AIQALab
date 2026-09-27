package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.ai.gateway.AgentExecutionContext;
import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.AiOperation;
import com.qalab.qalabai.ai.gateway.AiRequest;
import com.qalab.qalabai.ai.gateway.AiResponse;
import com.qalab.qalabai.ai.provider.JsonValidators;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.healing.context.FailureContextFactory;
import com.qalab.qalabai.healing.model.FailureContext;
import com.qalab.qalabai.model.BugReport;
import com.qalab.qalabai.model.TestCaseResult;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.BugReportRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.repository.TestCaseResultRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.util.UserInstructions;
import com.qalab.qalabai.prompt.PromptLibrary;
import com.qalab.qalabai.prompt.VersionedPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.qalab.qalabai.ai.provider.LlmJson;

/**
 * Generates a bug report from a failed test execution.
 *
 * <p>The service builds a {@link FailureContext} from the persisted execution
 * (error message, console logs, failing locator, classification), asks the AI
 * to turn it into a structured, developer-actionable bug report and persists
 * the result. When the AI call fails, a deterministic fallback report is built
 * from the available data so the failure is never silently dropped.</p>
 */
@Service
public class BugReportService {

    private static final Logger log = LoggerFactory.getLogger(BugReportService.class);
    private static final int LOG_EXCERPT_LIMIT = 4000;

    private final BugReportRepository bugReportRepository;
    private final TestExecutionRepository executionRepository;
    private final TestCaseResultRepository testCaseResultRepository;
    private final ProjectRepository projectRepository;
    private final FailureContextFactory contextFactory;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final PromptLibrary prompts;
    private final VersionedPrompt systemPrompt;

    public BugReportService(BugReportRepository bugReportRepository,
                            TestExecutionRepository executionRepository,
                            TestCaseResultRepository testCaseResultRepository,
                            ProjectRepository projectRepository,
                            FailureContextFactory contextFactory,
                            AiGateway aiGateway,
                            ObjectMapper objectMapper,
                        PromptLibrary prompts) {
        this.prompts = prompts;
        this.bugReportRepository = bugReportRepository;
        this.executionRepository = executionRepository;
        this.testCaseResultRepository = testCaseResultRepository;
        this.projectRepository = projectRepository;
        this.contextFactory = contextFactory;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.systemPrompt = prompts.get("bug-report-generator");
    }

    /**
     * Generates and persists a bug report for a failed execution.
     *
     * <p>Kept as the single-report entry point for existing callers. When the run has
     * per-test results there may be several distinct bugs, so this returns the first and
     * {@link #generateAll} returns them all. Callers that can show a list should use that
     * instead of silently dropping the other bugs.</p>
     */
    public BugReport generate(Long executionId, Long projectId) {
        return generate(executionId, projectId, null);
    }

    /** Generates and persists a bug report for a failed execution, honouring optional user guidance. */
    public BugReport generate(Long executionId, Long projectId, String instruction) {
        List<BugReport> reports = generateAll(executionId, projectId, instruction);
        if (reports.isEmpty()) {
            throw ApiException.invalidRequest("Execution " + executionId
                    + " produced no failing tests; there is nothing to report");
        }
        return reports.get(0);
    }

    /**
     * One report per <em>distinct failure</em>, not per run and not per test.
     *
     * <p>The previous behaviour generated one report from the whole-run console output,
     * which is why the user's report came back titled after the test file with
     * {@code LOCATOR_FAILURE} and {@code Unknown error} rather than naming anything they
     * recognised. Now each report is grounded in the specific test that failed: its own
     * assertion, its own error, its own screenshot.</p>
     *
     * <p>Failures sharing a signature collapse onto one report whose occurrence count is
     * incremented, so a known bug is not re-filed every run — a list that grows on every
     * run is a list nobody reads.</p>
     *
     * <p>Falls back to the whole-execution path when the run recorded no per-test
     * results, so a run that predates the JSON reporter still produces a report.</p>
     */
    public List<BugReport> generateAll(Long executionId, Long projectId, String instruction) {
        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> ApiException.invalidRequest("Execution not found: " + executionId));

        if (isPassedOrSkipped(execution.getStatus())) {
            throw ApiException.invalidRequest("Execution " + executionId + " status is "
                    + execution.getStatus() + "; a bug report requires a failed test");
        }

        String normalizedInstruction = com.qalab.qalabai.util.UserInstructions.normalize(instruction);
        Long effectiveProjectId = projectId != null ? projectId : execution.getProjectId();
        String baseUrl = resolveBaseUrl(effectiveProjectId);
        String runId = String.valueOf(executionId);

        List<TestCaseResult> caseResults =
                testCaseResultRepository.findByExecutionIdOrderByOrdinalPositionAsc(executionId);
        List<FailureSignature.Group> groups = FailureSignature.groupFailing(caseResults);

        if (groups.isEmpty()) {
            log.info("No per-test failures recorded for execution {}; falling back to the "
                    + "whole-execution report", executionId);
            FailureContext context = contextFactory.fromExecution(
                    effectiveProjectId, runId, execution, baseUrl);
            return List.of(persist(generateReport(context, execution, normalizedInstruction),
                    execution, effectiveProjectId, context, null, null, normalizedInstruction));
        }

        List<BugReport> reports = new ArrayList<>();
        for (FailureSignature.Group group : groups) {
            TestCaseResult failing = group.representative();
            FailureContext context = contextForTest(effectiveProjectId, runId, execution,
                    baseUrl, failing, group);

            // A failure already filed is counted, not re-reported.
            var existing = effectiveProjectId == null
                    ? java.util.Optional.<BugReport>empty()
                    : bugReportRepository.findByProjectIdAndDedupKey(effectiveProjectId, group.signature());
            if (existing.isPresent()) {
                BugReport known = existing.get();
                known.setOccurrences(known.getOccurrences() == null ? 2 : known.getOccurrences() + 1);
                known.setExecutionId(executionId);
                reports.add(bugReportRepository.save(known));
                log.info("Bug {} already known (signature {}); occurrence count is now {}",
                        known.getReportId(), group.signature().substring(0, 8), known.getOccurrences());
                continue;
            }

            BugReport report = generateReport(context, execution, normalizedInstruction);
            // The report must name the failure, not the run. Without this the title falls
            // back to the test file, which is how a generic report was produced before.
            if (report.getTitle() == null || report.getTitle().isBlank()
                    || report.getTitle().startsWith("Test failed:")) {
                report.setTitle(fallbackTitle(context, group));
            }
            reports.add(persist(report, execution, effectiveProjectId, context,
                    group.signature(), runId, normalizedInstruction));
        }

        log.info("Execution {} produced {} report(s) from {} failing test(s) in {} distinct failure(s)",
                executionId, reports.size(),
                caseResults == null ? 0 : caseResults.stream()
                        .filter(t -> "failed".equalsIgnoreCase(t.getStatus())).count(),
                groups.size());
        return reports;
    }

    /**
     * Builds a failure context from a specific failing test rather than the whole run.
     *
     * <p>The error, stack and screenshot come from the per-test row, so the report cites
     * the assertion that actually failed instead of whatever Playwright printed last.
     * Classification is left unset: the per-test row carries no locator or action, and
     * inventing one from a stack frame would be a guess dressed as a finding.</p>
     */
    private FailureContext contextForTest(Long projectId, String runId, TestExecution execution,
                                          String baseUrl, TestCaseResult test,
                                          FailureSignature.Group group) {
        FailureContext context = new FailureContext();
        context.setProjectId(projectId);
        context.setRunId(runId);
        context.setTestName(test.getTestTitle());
        context.setTestFile(test.getSpecFile() != null ? test.getSpecFile() : execution.getTestFile());
        context.setError(test.getErrorMessage());
        context.setStackTrace(test.getErrorSnippet());
        context.setScreenshot(firstAttachment(test.getScreenshots()));
        context.setVideo(firstAttachment(test.getVideos()));
        context.setTrace(firstAttachment(test.getTraces()));
        context.setCurrentUrl(baseUrl);
        context.setExecutionTimestamp(execution.getCreatedAt());
        // The whole-run log is still the best available context for "what happened",
        // and the per-test error is the best available context for "what broke".
        context.setLogs(execution.getConsoleLogs());
        if (group.size() > 1) {
            context.setPageTitle(group.size() + " tests failed with this assertion");
        }
        return context;
    }

    private BugReport persist(BugReport report, TestExecution execution, Long projectId,
                              FailureContext context, String dedupKey, String runId,
                              String instruction) {
        report.setReportId("bug-" + UUID.randomUUID().toString().substring(0, 8));
        report.setProjectId(projectId);
        report.setExecutionId(execution.getId());
        report.setTestFile(context.getTestFile() != null ? context.getTestFile() : execution.getTestFile());
        report.setTestName(context.getTestName() != null ? context.getTestName() : execution.getTestFile());
        report.setStatus(execution.getStatus());
        report.setErrorMessage(context.getError());
        report.setConsoleLogsExcerpt(truncate(context.getLogs(), LOG_EXCERPT_LIMIT));
        report.setScreenshotPath(context.getScreenshot());
        // The user's guidance has to reach the report. It was silently dropped in an
        // earlier rewrite of this method, and the test that caught it only existed
        // because it asserted the instruction rather than the report's existence — which
        // is the argument for asserting the content and not just the object.
        report.setInstruction(instruction);
        if (dedupKey != null) {
            report.setDedupKey(dedupKey);
            report.setOccurrences(1);
            report.setFirstSeenRun(runId);
        }

        BugReport saved = bugReportRepository.save(report);
        log.info("Bug report {} created for execution {} (signature {})",
                saved.getReportId(), execution.getId(),
                dedupKey == null ? "n/a" : dedupKey.substring(0, 8));
        return saved;
    }

    /**
     * A title that names the failure when the model did not supply a better one.
     * Built from the assertion, because "Test failed: login.spec.ts" is not a bug
     * anyone can act on.
     */
    private String fallbackTitle(FailureContext context, FailureSignature.Group group) {
        String assertion = firstLine(context.getError());
        if (assertion != null && !assertion.isBlank() && !assertion.toLowerCase().startsWith("error")) {
            String prefix = group.size() > 1
                    ? group.size() + " tests fail: "
                    : "Test fails: ";
            return prefix + truncate(assertion.trim(), 120);
        }
        String testName = context.getTestName();
        return "Test failed: " + (testName != null ? testName : safe(context.getTestFile()));
    }

    private String firstLine(String value) {
        if (value == null) {
            return null;
        }
        for (String line : value.split("\\R")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    private String firstAttachment(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            String[] paths = objectMapper.readValue(json, String[].class);
            return paths != null && paths.length > 0 ? paths[0] : null;
        } catch (Exception e) {
            log.debug("Ignoring unreadable attachment list: {}", json);
            return null;
        }
    }

    public BugReport findByReportId(String reportId) {
        return bugReportRepository.findByReportId(reportId)
                .orElseThrow(() -> ApiException.invalidRequest("Bug report not found: " + reportId));
    }

    public List<BugReport> findByProjectId(Long projectId) {
        return bugReportRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public List<BugReport> findByExecutionId(Long executionId) {
        return bugReportRepository.findByExecutionIdOrderByCreatedAtDesc(executionId);
    }

    public List<BugReport> listAll() {
        return bugReportRepository.findAllByOrderByCreatedAtDesc();
    }

    private boolean isPassedOrSkipped(String status) {
        return status != null && ("PASSED".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status));
    }

    private String resolveBaseUrl(Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            return projectRepository.findById(projectId)
                    .map(com.qalab.qalabai.model.Project::getBaseUrl)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Unable to resolve baseUrl for project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    private BugReport generateReport(FailureContext context, TestExecution execution, String instruction) {
        try {
            String userPrompt = buildUserPrompt(context, instruction);
            AiRequest request = AiRequest.builder(AiOperation.BUG_REPORT, systemPrompt.text(), userPrompt)
                    .promptVersion(systemPrompt.version())
                    .validator(JsonValidators.isJsonObject())
                    .build();
            AgentExecutionContext ctx = AgentExecutionContext.builder()
                    .projectContext(new ProjectContext(context.getProjectId(), null, context.getCurrentUrl(), null))
                    .operationId("op-bug-" + UUID.randomUUID().toString().substring(0, 8))
                    .build();
            AiResponse aiResponse = aiGateway.complete(request, ctx);
            return parse(aiResponse.getContent(), context);
        } catch (Exception e) {
            log.warn("AI bug report generation failed for execution {}; using deterministic fallback: {}",
                    execution.getId(), e.getMessage());
            return deterministicFallback(context);
        }
    }

    private String buildUserPrompt(FailureContext context, String instruction) {
        return String.format("""
                Test Name: %s
                Test File: %s
                Page URL: %s
                Page Title: %s
                Failing Locator: %s
                Error Message: %s
                Failure Classification: %s
                Assertion That Failed: %s
                Screenshot: %s
                Console Logs:
                %s

                Write the bug report for THIS failing test. Name the assertion that
                failed in the title; do not describe the run as a whole.
                """,
                context.getTestName() != null ? context.getTestName() : "unknown",
                context.getTestFile() != null ? context.getTestFile() : "unknown",
                context.getCurrentUrl() != null ? context.getCurrentUrl() : "unknown",
                context.getPageTitle() != null ? context.getPageTitle() : "unknown",
                context.getOriginalLocator() != null ? context.getOriginalLocator() : "unknown",
                context.getError() != null ? context.getError() : "No error message",
                context.getClassification() != null ? context.getClassification() : "UNKNOWN",
                firstLine(context.getError()) != null ? firstLine(context.getError()) : "No assertion captured",
                context.getScreenshot() != null ? context.getScreenshot() : "none captured",
                context.getLogs() != null ? truncate(context.getLogs(), LOG_EXCERPT_LIMIT) : "No logs")
                + com.qalab.qalabai.agent.PromptBlocks.userInstructions(instruction);
    }

    private BugReport parse(String aiResponse, FailureContext context) {
        BugReport report = new BugReport();
        JsonNode root;
        try {
            root = objectMapper.readTree(LlmJson.extract(aiResponse));
        } catch (Exception e) {
            log.warn("Failed to parse bug report AI response: {}", e.getMessage());
            return deterministicFallback(context);
        }

        report.setTitle(text(root, "title", "Test failed: " + safe(context.getTestName())));
        report.setSeverity(normalizeSeverity(root.path("severity").asText("MEDIUM")));
        report.setSummary(text(root, "summary", "No summary provided."));
        report.setStepsToReproduce(text(root, "stepsToReproduce", "1. Open " + safe(context.getCurrentUrl())));
        // "Unknown." was the other half of the generic report. When the model omits
        // these, the captured assertion is still a fact, so use it rather than nothing.
        String assertion = firstLine(context.getError());
        report.setExpectedBehavior(text(root, "expectedBehavior",
                assertion != null ? "The assertion should have held: " + assertion
                        : "Not captured."));
        report.setActualBehavior(text(root, "actualBehavior",
                assertion != null ? "It failed with: " + assertion : "Not captured."));
        report.setAffectedElement(text(root, "affectedElement", null));
        report.setFailureType(normalizeFailureType(root.path("failureType").asText(
                context.getClassification() != null ? context.getClassification().name() : "UNKNOWN")));
        report.setSuggestedFix(text(root, "suggestedFix", null));
        report.setReportJson(LlmJson.extract(aiResponse));
        return report;
    }

    private BugReport deterministicFallback(FailureContext context) {
        BugReport report = new BugReport();
        report.setTitle("Test failed: " + safe(context.getTestName()));
        report.setSeverity("MEDIUM");
        report.setTitle("Test fails: " + (firstLine(context.getError()) != null
                ? truncate(firstLine(context.getError()).trim(), 120) : safe(context.getTestName())));
        report.setSummary("Automated test failed with error: " + safe(context.getError())
                + " (classification: "
                + (context.getClassification() != null ? context.getClassification() : "UNKNOWN") + ")");
        report.setStepsToReproduce("1. Open " + safe(context.getCurrentUrl())
                + "\n2. Run test " + safe(context.getTestName()));
        String assertion = firstLine(context.getError());
        report.setExpectedBehavior(assertion != null
                ? "The assertion should have held: " + assertion : "Not captured.");
        report.setActualBehavior(assertion != null
                ? "It failed with: " + assertion : safe(context.getError()));
        report.setAffectedElement(context.getOriginalLocator() != null
                ? "Locator: " + context.getOriginalLocator() : null);
        report.setFailureType(context.getClassification() != null
                ? context.getClassification().name() : "UNKNOWN");
        report.setReportJson("{}");
        return report;
    }

    private String normalizeSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "MEDIUM";
        }
        String upper = raw.trim().toUpperCase();
        if (upper.equals("CRITICAL") || upper.equals("HIGH")
                || upper.equals("MEDIUM") || upper.equals("LOW")) {
            return upper;
        }
        return "MEDIUM";
    }

    private String normalizeFailureType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        String upper = raw.trim().toUpperCase();
        if (upper.equals("APPLICATION_BUG") || upper.equals("TEST_ISSUE")
                || upper.equals("LOCATOR_ISSUE") || upper.equals("TIMEOUT")
                || upper.equals("NETWORK") || upper.equals("UNKNOWN")) {
            return upper;
        }
        return "UNKNOWN";
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private String safe(String value) {
        return value != null && !value.isBlank() ? value : "unknown";
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n... [truncated]";
    }

}
