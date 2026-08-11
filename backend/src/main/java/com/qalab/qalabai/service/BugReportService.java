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
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.BugReportRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
    private final ProjectRepository projectRepository;
    private final FailureContextFactory contextFactory;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public BugReportService(BugReportRepository bugReportRepository,
                            TestExecutionRepository executionRepository,
                            ProjectRepository projectRepository,
                            FailureContextFactory contextFactory,
                            AiGateway aiGateway,
                            ObjectMapper objectMapper) {
        this.bugReportRepository = bugReportRepository;
        this.executionRepository = executionRepository;
        this.projectRepository = projectRepository;
        this.contextFactory = contextFactory;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadPrompt();
    }

    /** Generates and persists a bug report for a failed execution. */
    public BugReport generate(Long executionId, Long projectId) {
        return generate(executionId, projectId, null);
    }

    /** Generates and persists a bug report for a failed execution, honouring optional user guidance. */
    public BugReport generate(Long executionId, Long projectId, String instruction) {
        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> ApiException.invalidRequest("Execution not found: " + executionId));

        if (isPassedOrSkipped(execution.getStatus())) {
            throw ApiException.invalidRequest("Execution " + executionId + " status is "
                    + execution.getStatus() + "; a bug report requires a failed test");
        }

        String normalizedInstruction = com.qalab.qalabai.util.UserInstructions.normalize(instruction);
        Long effectiveProjectId = projectId != null ? projectId : execution.getProjectId();
        String baseUrl = resolveBaseUrl(effectiveProjectId);
        FailureContext context = contextFactory.fromExecution(
                effectiveProjectId, String.valueOf(executionId), execution, baseUrl);

        BugReport report = generateReport(context, execution, normalizedInstruction);
        report.setReportId("bug-" + UUID.randomUUID().toString().substring(0, 8));
        report.setProjectId(effectiveProjectId);
        report.setExecutionId(executionId);
        report.setTestFile(execution.getTestFile());
        report.setTestName(context.getTestName() != null ? context.getTestName() : execution.getTestFile());
        report.setStatus(execution.getStatus());
        report.setErrorMessage(context.getError());
        report.setConsoleLogsExcerpt(truncate(context.getLogs(), LOG_EXCERPT_LIMIT));
        report.setInstruction(normalizedInstruction);

        BugReport saved = bugReportRepository.save(report);
        log.info("Bug report {} created for execution {}", saved.getReportId(), executionId);
        return saved;
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
            AiRequest request = AiRequest.builder(AiOperation.BUG_REPORT, systemPrompt, userPrompt)
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
                Console Logs:
                %s

                Write the bug report for this failed test execution.
                """,
                context.getTestName() != null ? context.getTestName() : "unknown",
                context.getTestFile() != null ? context.getTestFile() : "unknown",
                context.getCurrentUrl() != null ? context.getCurrentUrl() : "unknown",
                context.getPageTitle() != null ? context.getPageTitle() : "unknown",
                context.getOriginalLocator() != null ? context.getOriginalLocator() : "unknown",
                context.getError() != null ? context.getError() : "No error message",
                context.getClassification() != null ? context.getClassification() : "UNKNOWN",
                context.getLogs() != null ? truncate(context.getLogs(), LOG_EXCERPT_LIMIT) : "No logs")
                + com.qalab.qalabai.agent.PromptBlocks.userInstructions(instruction);
    }

    private BugReport parse(String aiResponse, FailureContext context) {
        BugReport report = new BugReport();
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJson(aiResponse));
        } catch (Exception e) {
            log.warn("Failed to parse bug report AI response: {}", e.getMessage());
            return deterministicFallback(context);
        }

        report.setTitle(text(root, "title", "Test failed: " + safe(context.getTestName())));
        report.setSeverity(normalizeSeverity(root.path("severity").asText("MEDIUM")));
        report.setSummary(text(root, "summary", "No summary provided."));
        report.setStepsToReproduce(text(root, "stepsToReproduce", "1. Open " + safe(context.getCurrentUrl())));
        report.setExpectedBehavior(text(root, "expectedBehavior", "Unknown."));
        report.setActualBehavior(text(root, "actualBehavior", "Unknown."));
        report.setAffectedElement(text(root, "affectedElement", null));
        report.setFailureType(normalizeFailureType(root.path("failureType").asText(
                context.getClassification() != null ? context.getClassification().name() : "UNKNOWN")));
        report.setSuggestedFix(text(root, "suggestedFix", null));
        report.setReportJson(extractJson(aiResponse));
        return report;
    }

    private BugReport deterministicFallback(FailureContext context) {
        BugReport report = new BugReport();
        report.setTitle("Test failed: " + safe(context.getTestName()));
        report.setSeverity("MEDIUM");
        report.setSummary("Automated test failed with error: " + safe(context.getError())
                + " (classification: "
                + (context.getClassification() != null ? context.getClassification() : "UNKNOWN") + ")");
        report.setStepsToReproduce("1. Open " + safe(context.getCurrentUrl())
                + "\n2. Run test " + safe(context.getTestName()));
        report.setExpectedBehavior("Unknown.");
        report.setActualBehavior(safe(context.getError()));
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

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        }
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/bug-report-generator.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load bug report generator prompt: {}", e.getMessage());
            return "You are a Senior QA Bug Reporter. Produce a structured bug report in JSON.";
        }
    }
}
