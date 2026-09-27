package com.qalab.qalabai.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.healing.model.HealingProposal;
import com.qalab.qalabai.healing.service.HealingOutcome;
import com.qalab.qalabai.model.TestExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link TestReport} as JSON (and a human-readable Markdown) and
 * persists it next to the execution artifacts.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ObjectMapper objectMapper;

    public ReportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TestReport generate(TestExecution execution, Map<String, Object> artifacts) {
        return generate(execution, artifacts, null, null, null);
    }

    public TestReport generate(TestExecution execution, Map<String, Object> artifacts, HealingOutcome healing) {
        return generate(execution, artifacts, healing, null, null);
    }

    /**
     * @param testCases per-test results in run order, used for the HTML report's results
     *                  table. Null or empty is fine: the report then says so rather than
     *                  implying a clean run.
     */
    public TestReport generate(TestExecution execution, Map<String, Object> artifacts, HealingOutcome healing,
                               List<HtmlReportRenderer.TestCaseView> testCases,
                               Map<String, Object> extras) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", execution.getId());
        body.put("projectId", execution.getProjectId());
        body.put("testFile", execution.getTestFile());
        body.put("status", execution.getStatus());
        body.put("duration", execution.getDuration());
        if (execution.getErrorMessage() != null) {
            body.put("errorMessage", execution.getErrorMessage());
        }
        if (artifacts != null && !artifacts.isEmpty()) {
            body.put("artifacts", artifacts);
        }
        // Allure, when the workspace already produced it. Hoisted to the top level because
        // the report offers it as an alternative to itself: a reader who is used to Allure
        // should be able to see that it exists and where it is, rather than dig for it.
        copyIfPresent(artifacts, body, "allureReport", "allureReport");
        copyIfPresent(artifacts, body, "allureResults", "allureResults");
        copyIfPresent(artifacts, body, "allureNote", "allureNote");
        if (healing != null) {
            body.put("healing", healingSection(healing));
        }
        body.put("createdAt", execution.getCreatedAt());

        String reportPath = null;
        String htmlPath = null;
        String artifactDir = artifacts != null ? (String) artifacts.get("artifactDir") : null;
        if (artifactDir != null) {
            try {
                Path dir = Paths.get(artifactDir);
                Files.createDirectories(dir);
                Path json = dir.resolve("report.json");
                Files.writeString(json, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
                Path md = dir.resolve("report.md");
                Files.writeString(md, toMarkdown(body));

                // Self-contained HTML next to the JSON. Written after report.json on
                // purpose: the JSON is the machine contract and must not be at the mercy
                // of a rendering problem.
                try {
                    Path html = dir.resolve("report.html");
                    Files.writeString(html, renderHtml(body, artifacts, healing, testCases, extras, dir));
                    htmlPath = html.toAbsolutePath().toString();
                } catch (Exception e) {
                    // A failed render must not lose report.json or report.md.
                    log.warn("HTML report failed for execution {}: {}", execution.getId(), e.getMessage());
                }

                reportPath = json.toAbsolutePath().toString();
                log.info("Report written for execution {} at {} (html: {})",
                        execution.getId(), reportPath, htmlPath != null ? "yes" : "unavailable");
            } catch (Exception e) {
                log.warn("Failed to write report for execution {}: {}", execution.getId(), e.getMessage());
            }
        }

        return new TestReport(
                execution.getId(), execution.getProjectId(), execution.getTestFile(),
                execution.getStatus(), execution.getDuration(), execution.getErrorMessage(),
                artifacts, reportPath, htmlPath, execution.getCreatedAt());
    }

    /**
     * Builds the run summary the HTML header is drawn from, including the per-test
     * counters. Counts are derived from the per-test results when there are any, because
     * the execution's own status is a single verdict and says nothing about how many
     * tests ran.
     */
    /**
     * Re-renders {@code report.html} in place with extra sections.
     *
     * <p>Needed because the bug report is generated <em>after</em> the execution report is
     * written — it needs the execution id that the run only has once the tests are done.
     * Rather than leave the HTML permanently missing the most actionable section, it is
     * rewritten once the bug reports exist.</p>
     *
     * <p>Failure is swallowed: the report is an output, and losing a section must never
     * fail a run whose tests have already been reported.</p>
     *
     * @return the path written, or null when there is no report to update
     */
    public String reRender(TestExecution execution, List<HtmlReportRenderer.TestCaseView> testCases,
                           Map<String, Object> extras) {
        String artifactDir = artifactDirOf(execution);
        if (artifactDir == null) {
            log.debug("No artifact directory recorded for execution {}; nothing to re-render",
                    execution.getId());
            return null;
        }
        try {
            Path dir = Paths.get(artifactDir);
            if (!Files.isDirectory(dir)) {
                return null;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("executionId", execution.getId());
            body.put("projectId", execution.getProjectId());
            body.put("testFile", execution.getTestFile());
            body.put("status", execution.getStatus());
            body.put("duration", execution.getDuration());
            body.put("createdAt", execution.getCreatedAt());
            if (execution.getErrorMessage() != null) {
                body.put("errorMessage", execution.getErrorMessage());
            }
            Path html = dir.resolve("report.html");
            Files.writeString(html, renderHtml(body, null, null, testCases, extras, dir));
            log.info("Re-rendered HTML report for execution {} with {} extra section(s)",
                    execution.getId(), extras == null ? 0 : extras.size());
            return html.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Could not re-render HTML report for execution {}: {}",
                    execution.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * The artifact directory is not stored on the execution, but the report paths are
     * siblings of it, so it can be recovered without a new column.
     */
    private String artifactDirOf(TestExecution execution) {
        String reportPath = execution.getReportPath();
        if (reportPath == null || reportPath.isBlank()) {
            return null;
        }
        try {
            return Paths.get(reportPath).getParent().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> summaryFor(Map<String, Object> body,
                                           List<HtmlReportRenderer.TestCaseView> testCases) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("executionId", body.get("executionId"));
        summary.put("testFile", body.get("testFile"));
        summary.put("status", body.get("status"));
        summary.put("createdAt", body.get("createdAt"));
        summary.put("durationMs", body.get("duration"));

        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (HtmlReportRenderer.TestCaseView test : testCases == null ? List.<HtmlReportRenderer.TestCaseView>of() : testCases) {
            String status = test.status() == null ? "" : test.status();
            if ("passed".equals(status)) {
                passed++;
            } else if ("failed".equals(status)) {
                failed++;
            } else if ("skipped".equals(status)) {
                skipped++;
            }
        }
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        return summary;
    }

    private String renderHtml(Map<String, Object> body, Map<String, Object> artifacts,
                              HealingOutcome healing, List<HtmlReportRenderer.TestCaseView> testCases,
                              Map<String, Object> extras, Path dir) {
        Map<String, Object> sections = new LinkedHashMap<>();
        if (extras != null) {
            sections.putAll(extras);
        }
        if (healing != null) {
            sections.put("healing", healingSection(healing));
        }
        Map<String, Object> summary = summaryFor(body, testCases);
        return HtmlReportRenderer.render(summary, testCases, sections, dir);
    }

    private Map<String, Object> healingSection(HealingOutcome healing) {
        Map<String, Object> section = new LinkedHashMap<>();
        if (healing.classification() != null) {
            section.put("classification", healing.classification().type());
            section.put("classificationConfidence", healing.classification().confidence());
            section.put("classificationReason", healing.classification().reason());
        }
        section.put("healingAttempted", healing.healingAttempted());
        section.put("message", healing.message());
        if (healing.proposal() != null) {
            HealingProposal p = healing.proposal();
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("proposalId", p.getProposalId());
            proposal.put("originalLocator", p.getOriginalLocator());
            proposal.put("recommendedLocator", p.getRecommendedLocator());
            proposal.put("confidence", p.getConfidence());
            proposal.put("confidenceLabel", p.getConfidenceLabel());
            proposal.put("safeToApply", p.getSafeToApply());
            proposal.put("reason", p.getReason());
            proposal.put("status", p.getStatus());
            Map<String, Object> intelligence = new LinkedHashMap<>();
            intelligence.put("originalLocatorHealth", p.getOriginalLocatorHealth());
            intelligence.put("originalLocatorStability", p.getOriginalLocatorStability());
            intelligence.put("recommendedLocatorHealth", p.getRecommendedLocatorHealth());
            intelligence.put("recommendedLocatorStability", p.getRecommendedLocatorStability());
            intelligence.put("recommendedStabilityLevel", p.getRecommendedStabilityLevel());
            intelligence.put("recommendedSemanticScore", p.getRecommendedSemanticScore());
            intelligence.put("recommendedQualityScore", p.getRecommendedQualityScore());
            proposal.put("locatorIntelligence", intelligence);
            section.put("proposal", proposal);
        }
        return section;
    }

    public String toMarkdown(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Test Execution Report\n\n");
        sb.append("- **Execution ID:** ").append(report.get("executionId")).append("\n");
        sb.append("- **Project ID:** ").append(value(report.get("projectId"))).append("\n");
        sb.append("- **Test file:** ").append(value(report.get("testFile"))).append("\n");
        sb.append("- **Status:** ").append(value(report.get("status"))).append("\n");
        sb.append("- **Duration:** ").append(value(report.get("duration"))).append(" ms\n");
        if (report.get("errorMessage") != null) {
            sb.append("- **Error:** `").append(report.get("errorMessage")).append("`\n");
        }
        if (report.get("createdAt") != null) {
            sb.append("- **Created at:** ").append(report.get("createdAt")).append("\n");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> artifacts = (Map<String, Object>) report.get("artifacts");
        if (artifacts != null && !artifacts.isEmpty()) {
            sb.append("\n## Artifacts\n\n");
            artifacts.forEach((k, v) -> sb.append("- **").append(k).append(":** `").append(v).append("`\n"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> healing = (Map<String, Object>) report.get("healing");
        if (healing != null) {
            sb.append("\n## Self-Healing\n\n");
            sb.append("- **Classification:** ").append(value(healing.get("classification"))).append("\n");
            sb.append("- **Healing attempted:** ").append(value(healing.get("healingAttempted"))).append("\n");
            sb.append("- **Message:** ").append(value(healing.get("message"))).append("\n");
            @SuppressWarnings("unchecked")
            Map<String, Object> proposal = (Map<String, Object>) healing.get("proposal");
            if (proposal != null) {
                sb.append("\n### Proposal ").append(value(proposal.get("proposalId"))).append("\n\n");
                sb.append("- **Original:** `").append(value(proposal.get("originalLocator"))).append("`\n");
                sb.append("- **Suggested:** `").append(value(proposal.get("recommendedLocator"))).append("`\n");
                sb.append("- **Confidence:** ").append(value(proposal.get("confidence")))
                        .append(" (").append(value(proposal.get("confidenceLabel"))).append(")\n");
                sb.append("- **Safe to apply:** ").append(value(proposal.get("safeToApply"))).append("\n");
                sb.append("- **Reason:** ").append(value(proposal.get("reason"))).append("\n");
                sb.append("- **Status:** ").append(value(proposal.get("status"))).append("\n");
                @SuppressWarnings("unchecked")
                Map<String, Object> intelligence = (Map<String, Object>) proposal.get("locatorIntelligence");
                if (intelligence != null && !intelligence.isEmpty()) {
                    sb.append("- **Original health:** ").append(value(intelligence.get("originalLocatorHealth")))
                            .append(" (stability ").append(value(intelligence.get("originalLocatorStability")))
                            .append("/25)\n");
                    sb.append("- **Recommended health:** ").append(value(intelligence.get("recommendedLocatorHealth")))
                            .append(" (stability ").append(value(intelligence.get("recommendedLocatorStability")))
                            .append("/25, semantic ").append(value(intelligence.get("recommendedSemanticScore")))
                            .append("/25, quality ").append(value(intelligence.get("recommendedQualityScore")))
                            .append("/100)\n");
                }
            }
        }
        return sb.toString();
    }

    private String value(Object o) {
        return o != null ? String.valueOf(o) : "-";
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key,
                                      String target) {
        if (from == null) {
            return;
        }
        Object value = from.get(key);
        if (value != null) {
            to.put(target, value);
        }
    }
}
