package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
public class V1ReportController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1ReportController.class);

    private final TestExecutionRepository executionRepository;

    public V1ReportController(ProjectContextResolver contextResolver,
                              TestExecutionRepository executionRepository) {
        super(contextResolver);
        this.executionRepository = executionRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> reports = executionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long executionId) {
        TestExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> ApiException.projectNotFound("Execution not found: " + executionId));
        log.info("GET /api/v1/reports/{}", executionId);
        return ResponseEntity.ok(toDetail(execution));
    }

    private Map<String, Object> toSummary(TestExecution execution) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", execution.getId());
        map.put("projectId", execution.getProjectId());
        map.put("testFile", execution.getTestFile());
        map.put("status", execution.getStatus());
        map.put("duration", execution.getDuration());
        map.put("createdAt", execution.getCreatedAt());
        map.put("reportPath", execution.getReportPath());
        return map;
    }

    private Map<String, Object> toDetail(TestExecution execution) {
        Map<String, Object> map = toSummary(execution);
        if (execution.getErrorMessage() != null) {
            map.put("errorMessage", execution.getErrorMessage());
        }
        Map<String, Object> artifacts = new LinkedHashMap<>();
        if (execution.getScreenshotPath() != null) map.put("screenshot", execution.getScreenshotPath());
        if (execution.getVideoPath() != null) map.put("video", execution.getVideoPath());
        if (execution.getTracePath() != null) map.put("trace", execution.getTracePath());
        if (execution.getConsoleLogs() != null) {
            map.put("output", execution.getConsoleLogs().length() > 4000
                    ? execution.getConsoleLogs().substring(0, 4000) : execution.getConsoleLogs());
        }
        if (!artifacts.isEmpty()) {
            map.put("artifacts", artifacts);
        }
        return map;
    }
}
