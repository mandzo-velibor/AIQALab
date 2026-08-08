package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.FailureAnalysisDto;
import com.qalab.qalabai.api.v1.dto.V1FailureAnalysisRequest;
import com.qalab.qalabai.api.v1.dto.V1FailureAnalysisResponse;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.service.FailureAnalysisService;
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
public class V1FailureAnalysisController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1FailureAnalysisController.class);

    private final FailureAnalysisService failureAnalysisService;

    public V1FailureAnalysisController(ProjectContextResolver contextResolver,
                                       FailureAnalysisService failureAnalysisService) {
        super(contextResolver);
        this.failureAnalysisService = failureAnalysisService;
    }

    @PostMapping("/failures/analyze")
    public ResponseEntity<V1FailureAnalysisResponse> analyze(@RequestBody V1FailureAnalysisRequest request) {
        if (request.executionId() == null) {
            throw ApiException.invalidRequest("executionId is required");
        }
        ProjectContext project = project(request.project());
        Long dbId = databaseId(request.project());
        if (dbId == null) {
            throw ApiException.invalidRequest("failure analysis requires a registered project (databaseId)");
        }
        String operationId = operationId();
        log.info("POST /api/v1/failures/analyze operationId={} executionId={}", operationId, request.executionId());

        FailureAnalysis analysis = failureAnalysisService.analyzeExecution(request.executionId(), dbId);

        V1FailureAnalysisResponse response = new V1FailureAnalysisResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                request.executionId(),
                FailureAnalysisDto.from(analysis),
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
