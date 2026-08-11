package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.v1.dto.V1BugReportRequest;
import com.qalab.qalabai.api.v1.dto.V1BugReportResponse;
import com.qalab.qalabai.model.BugReport;
import com.qalab.qalabai.service.BugReportService;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class V1BugReportController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1BugReportController.class);

    private final BugReportService bugReportService;

    public V1BugReportController(ProjectContextResolver contextResolver,
                                 BugReportService bugReportService) {
        super(contextResolver);
        this.bugReportService = bugReportService;
    }

    @PostMapping("/bug-reports")
    public ResponseEntity<V1BugReportResponse> generate(@RequestBody V1BugReportRequest request) {
        if (request == null || request.executionId() == null) {
            throw ApiException.invalidRequest("executionId is required");
        }
        project(request.project());
        String operationId = operationId();
        Long projectId = databaseId(request.project());
        String instruction = com.qalab.qalabai.util.UserInstructions.normalize(request.instruction());
        log.info("POST /api/v1/bug-reports operationId={} executionId={} instruction={}", operationId,
                request.executionId(), instruction != null ? "\"" + instruction + "\"" : "none");

        BugReport report = bugReportService.generate(request.executionId(), projectId, instruction);
        return ResponseEntity.ok(V1BugReportResponse.from(report));
    }

    @GetMapping("/bug-reports")
    public ResponseEntity<List<V1BugReportResponse>> list() {
        return ResponseEntity.ok(bugReportService.listAll().stream().map(V1BugReportResponse::from).toList());
    }

    @GetMapping("/bug-reports/{reportId}")
    public ResponseEntity<V1BugReportResponse> get(@PathVariable String reportId) {
        return ResponseEntity.ok(V1BugReportResponse.from(bugReportService.findByReportId(reportId)));
    }

    @GetMapping("/projects/{projectId}/bug-reports")
    public ResponseEntity<List<V1BugReportResponse>> byProject(@PathVariable Long projectId) {
        return ResponseEntity.ok(bugReportService.findByProjectId(projectId).stream()
                .map(V1BugReportResponse::from).toList());
    }
}
