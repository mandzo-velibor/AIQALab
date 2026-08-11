package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1HealingOutcomeResponse;
import com.qalab.qalabai.api.v1.dto.V1RunRequest;
import com.qalab.qalabai.api.v1.dto.V1RunResponse;
import com.qalab.qalabai.dto.executor.ExecutionResponse;
import com.qalab.qalabai.healing.service.HealingOutcome;
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

        ExecutionService.RunResult result;
        boolean healingAnalysis = Boolean.TRUE.equals(request.healingAnalysis());
        String instruction = com.qalab.qalabai.util.UserInstructions.normalize(request.instruction());
        if (Boolean.TRUE.equals(request.runAll())) {
            if (dbId == null) {
                throw ApiException.invalidRequest("runAll requires a registered project (databaseId)");
            }
            log.info("POST /api/v1/run operationId={} runAll=true project={} healingAnalysis={} instruction={}",
                    operationId, project.getProjectId(), healingAnalysis,
                    instruction != null ? "\"" + instruction + "\"" : "none");
            result = executionService.runAllTests(dbId, healingAnalysis, null, instruction);
        } else if (request.testId() != null) {
            log.info("POST /api/v1/run operationId={} testId={} healingAnalysis={} instruction={}",
                    operationId, request.testId(), healingAnalysis,
                    instruction != null ? "\"" + instruction + "\"" : "none");
            result = executionService.runTest(request.testId(), dbId, healingAnalysis, null, instruction);
        } else {
            throw ApiException.invalidRequest("testId or runAll is required");
        }
        ExecutionResponse exec = result.response();

        V1HealingOutcomeResponse healing = null;
        if (healingAnalysis && "FAILED".equals(exec.status()) && dbId != null) {
            HealingOutcome outcome = result.healing();
            if (outcome == null) {
                outcome = executionService.analyzeExecutionHealing(exec.executionId(), dbId);
            }
            healing = V1HealingOutcomeResponse.from(operationId, outcome);
        }

        V1RunResponse response = new V1RunResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                exec.executionId(),
                exec.status(),
                exec.durationMs(),
                exec.errorMessage(),
                exec.consoleLogs(),
                healing,
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
