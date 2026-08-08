package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1RunRequest;
import com.qalab.qalabai.api.v1.dto.V1RunResponse;
import com.qalab.qalabai.dto.executor.ExecutionResponse;
import com.qalab.qalabai.service.ExecutionService;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1")
public class V1ExecutionController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1ExecutionController.class);

    private final ExecutionService executionService;

    public V1ExecutionController(ProjectContextResolver contextResolver,
                                 ExecutionService executionService) {
        super(contextResolver);
        this.executionService = executionService;
    }

    @PostMapping("/run")
    public ResponseEntity<V1RunResponse> run(@RequestBody V1RunRequest request) {
        ProjectContext project = project(request.project());
        String operationId = operationId();
        Long dbId = databaseId(request.project());

        ExecutionResponse result;
        if (Boolean.TRUE.equals(request.runAll())) {
            if (dbId == null) {
                throw ApiException.invalidRequest("runAll requires a registered project (databaseId)");
            }
            log.info("POST /api/v1/run operationId={} runAll=true project={}", operationId, project.getProjectId());
            result = executionService.runAllTests(dbId);
        } else if (request.testId() != null) {
            log.info("POST /api/v1/run operationId={} testId={}", operationId, request.testId());
            result = executionService.runTest(request.testId(), dbId);
        } else {
            throw ApiException.invalidRequest("testId or runAll is required");
        }

        V1RunResponse response = new V1RunResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                result.executionId(),
                result.status(),
                result.duration(),
                result.errorMessage(),
                result.consoleLogs(),
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
