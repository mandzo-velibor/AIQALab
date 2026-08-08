package com.qalab.qalabai.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.model.TestExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
        return sb.toString();
    }

    private String value(Object o) {
        return o != null ? String.valueOf(o) : "-";
    }
}
