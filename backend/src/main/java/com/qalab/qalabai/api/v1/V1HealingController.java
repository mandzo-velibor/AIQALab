package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.v1.dto.V1HealingOutcomeResponse;
import com.qalab.qalabai.api.v1.dto.V1HealingRequest;
import com.qalab.qalabai.healing.service.HealingAnalysisService;
import com.qalab.qalabai.healing.service.HealingOutcome;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class V1HealingController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1HealingController.class);

    private final HealingAnalysisService healingAnalysisService;

    public V1HealingController(ProjectContextResolver contextResolver,
                               HealingAnalysisService healingAnalysisService) {
        super(contextResolver);
        this.healingAnalysisService = healingAnalysisService;
    }

    @PostMapping("/healing/analyze")
    public ResponseEntity<V1HealingOutcomeResponse> analyze(@RequestBody V1HealingRequest request) {
        if (request.executionId() == null) {
            throw ApiException.invalidRequest("executionId is required");
        }
        ProjectContext project = project(request.project());
        String operationId = operationId();
        Long dbId = databaseId(request.project());
        if (dbId == null) {
            throw ApiException.invalidRequest("healing/analyze requires a registered project (databaseId)");
        }
        log.info("POST /api/v1/healing/analyze operationId={} executionId={}", operationId, request.executionId());

        HealingOutcome outcome = healingAnalysisService.analyzeExecution(request.executionId(), dbId);
        return ResponseEntity.ok(V1HealingOutcomeResponse.from(operationId, outcome));
    }
}
