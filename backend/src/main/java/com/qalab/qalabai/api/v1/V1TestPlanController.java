package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1TestPlanRequest;
import com.qalab.qalabai.api.v1.dto.V1TestPlanResponse;
import com.qalab.qalabai.dto.planner.TestPlanResponse;
import com.qalab.qalabai.service.PlanningService;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class V1TestPlanController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1TestPlanController.class);

    private final PlanningService planningService;

    public V1TestPlanController(ProjectContextResolver contextResolver,
                                PlanningService planningService) {
        super(contextResolver);
        this.planningService = planningService;
    }

    @PostMapping("/test-plan")
    public ResponseEntity<V1TestPlanResponse> generate(@RequestBody V1TestPlanRequest request) {
        requireUrl(request.url());
        ProjectContext project = project(request.project());
        String operationId = operationId();
        log.info("POST /api/v1/test-plan operationId={} url={}", operationId, request.url());

        TestPlanResponse result = planningService.generateTestPlan(request.url(), databaseId(request.project()), request.instruction());

        V1TestPlanResponse response = new V1TestPlanResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                request.url(),
                result.scenarioCount(),
                result.scenarios(),
                result.instruction(),
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * The previously generated test plans for a URL, so the dashboard can show what already exists before asking
     * for a fresh generation. Promoted from the legacy surface by B-033.
     */
    @GetMapping("/test-plans")
    public ResponseEntity<List<TestPlanResponse>> byUrl(@RequestParam String url) {
        log.info("GET /api/v1/test-plans?url={}", url);
        return ResponseEntity.ok(planningService.getTestPlansForUrl(url));
    }
}
