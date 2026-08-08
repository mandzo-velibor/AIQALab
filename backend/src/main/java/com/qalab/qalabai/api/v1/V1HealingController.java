package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.HealingSuggestionDto;
import com.qalab.qalabai.api.v1.dto.V1HealingRequest;
import com.qalab.qalabai.api.v1.dto.V1HealingResponse;
import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.service.HealingService;
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
public class V1HealingController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1HealingController.class);

    private final HealingService healingService;

    public V1HealingController(ProjectContextResolver contextResolver,
                               HealingService healingService) {
        super(contextResolver);
        this.healingService = healingService;
    }

    @PostMapping("/healing/analyze")
    public ResponseEntity<V1HealingResponse> analyze(@RequestBody V1HealingRequest request) {
        if (request.executionId() == null) {
            throw ApiException.invalidRequest("executionId is required");
        }
        ProjectContext project = project(request.project());
        String operationId = operationId();
        log.info("POST /api/v1/healing/analyze operationId={} executionId={}", operationId, request.executionId());

        HealingSuggestion suggestion = healingService.generateHealingSuggestion(request.executionId());

        V1HealingResponse response = new V1HealingResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                request.executionId(),
                HealingSuggestionDto.from(suggestion),
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
