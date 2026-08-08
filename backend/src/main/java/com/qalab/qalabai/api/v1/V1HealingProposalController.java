package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.v1.dto.V1HealingOutcomeResponse;
import com.qalab.qalabai.api.v1.dto.V1HealingProposalResponse;
import com.qalab.qalabai.api.v1.dto.V1HealingProposeRequest;
import com.qalab.qalabai.healing.model.FailureContext;
import com.qalab.qalabai.healing.model.HealingProposal;
import com.qalab.qalabai.healing.service.HealingAnalysisService;
import com.qalab.qalabai.healing.service.HealingOutcome;
import com.qalab.qalabai.healing.service.HealingProposalService;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Self-healing API (Sprint 13). Produces explainable, human-reviewable locator
 * healing proposals. The Core never modifies user source files.
 */
@RestController
@RequestMapping("/api/v1")
public class V1HealingProposalController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1HealingProposalController.class);

    private final HealingAnalysisService healingAnalysisService;
    private final HealingProposalService proposalService;

    public V1HealingProposalController(ProjectContextResolver contextResolver,
                                       HealingAnalysisService healingAnalysisService,
                                       HealingProposalService proposalService) {
        super(contextResolver);
        this.healingAnalysisService = healingAnalysisService;
        this.proposalService = proposalService;
    }

    @PostMapping("/healing/propose")
    public ResponseEntity<V1HealingOutcomeResponse> propose(@RequestBody V1HealingProposeRequest request) {
        ProjectContext project = project(request.project());
        String operationId = operationId();

        if (request.executionId() != null) {
            Long dbId = databaseId(request.project());
            if (dbId == null) {
                throw ApiException.invalidRequest("executionId analysis requires a registered project (databaseId)");
            }
            log.info("POST /api/v1/healing/propose operationId={} executionId={}", operationId, request.executionId());
            HealingOutcome outcome = healingAnalysisService.analyzeExecution(request.executionId(), dbId);
            return ResponseEntity.ok(V1HealingOutcomeResponse.from(operationId, outcome));
        }

        FailureContext failure = request.failure();
        if (failure == null) {
            throw ApiException.invalidRequest("executionId or failure context is required");
        }
        if (failure.getOriginalLocator() == null || failure.getOriginalLocator().isBlank()) {
            throw ApiException.invalidRequest("failure.originalLocator is required");
        }
        Long projectId = failure.getProjectId() != null ? failure.getProjectId() : databaseId(request.project());
        if (projectId != null) {
            failure.setProjectId(projectId);
        }
        log.info("POST /api/v1/healing/propose operationId={} originalLocator={}",
                operationId, failure.getOriginalLocator());
        HealingOutcome outcome = healingAnalysisService.analyze(failure, project);
        return ResponseEntity.ok(V1HealingOutcomeResponse.from(operationId, outcome));
    }

    @GetMapping("/healing/{proposalId}")
    public ResponseEntity<V1HealingProposalResponse> get(@PathVariable String proposalId) {
        log.info("GET /api/v1/healing/{}", proposalId);
        return ResponseEntity.ok(V1HealingProposalResponse.from(proposalService.findByProposalId(proposalId)));
    }

    @GetMapping("/healing")
    public ResponseEntity<List<V1HealingProposalResponse>> byExecution(@RequestParam(value = "executionId", required = false) String executionId,
                                                                       @RequestParam(value = "runId", required = false) String runId) {
        List<HealingProposal> proposals;
        if (runId != null) {
            proposals = proposalService.findByRunId(runId);
        } else if (executionId != null) {
            proposals = proposalService.findByRunId(executionId);
        } else {
            throw ApiException.invalidRequest("executionId or runId query parameter is required");
        }
        return ResponseEntity.ok(proposals.stream().map(V1HealingProposalResponse::from).toList());
    }

    @GetMapping("/projects/{projectId}/healing")
    public ResponseEntity<List<V1HealingProposalResponse>> history(@PathVariable Long projectId) {
        log.info("GET /api/v1/projects/{}/healing", projectId);
        return ResponseEntity.ok(proposalService.findByProjectId(projectId).stream()
                .map(V1HealingProposalResponse::from).toList());
    }

    @PostMapping("/healing/{proposalId}/accept")
    public ResponseEntity<V1HealingProposalResponse> accept(@PathVariable String proposalId) {
        log.info("POST /api/v1/healing/{}/accept", proposalId);
        return ResponseEntity.ok(V1HealingProposalResponse.from(proposalService.accept(proposalId)));
    }

    @PostMapping("/healing/{proposalId}/reject")
    public ResponseEntity<V1HealingProposalResponse> reject(@PathVariable String proposalId) {
        log.info("POST /api/v1/healing/{}/reject", proposalId);
        return ResponseEntity.ok(V1HealingProposalResponse.from(proposalService.reject(proposalId)));
    }
}
