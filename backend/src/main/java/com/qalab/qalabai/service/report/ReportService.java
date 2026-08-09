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
        return generate(execution, artifacts, null);
    }

    public TestReport generate(TestExecution execution, Map<String, Object> artifacts, HealingOutcome healing) {
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
        if (healing != null) {
            body.put("healing", healingSection(healing));
        }
        body.put("createdAt", execution.getCreatedAt());

        String reportPath = null;
        String artifactDir = artifacts != null ? (String) artifacts.get("artifactDir") : null;
        if (artifactDir != null) {
            try {
                Path dir = Paths.get(artifactDir);
                Files.createDirectories(dir);
                Path json = dir.resolve("report.json");
                Files.writeString(json, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
                Path md = dir.resolve("report.md");
                Files.writeString(md, toMarkdown(body));
                reportPath = json.toAbsolutePath().toString();
                log.info("Report written for execution {} at {}", execution.getId(), reportPath);
            } catch (Exception e) {
                log.warn("Failed to write report for execution {}: {}", execution.getId(), e.getMessage());
            }
        }

        return new TestReport(
                execution.getId(), execution.getProjectId(), execution.getTestFile(),
                execution.getStatus(), execution.getDuration(), execution.getErrorMessage(),
                artifacts, reportPath, execution.getCreatedAt());
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
}
