package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest;
import com.qalab.qalabai.api.v1.dto.V1WorkflowAccepted;
import com.qalab.qalabai.api.v1.dto.V1WorkflowResponse;
import com.qalab.qalabai.service.OperationProgressStore;
import com.qalab.qalabai.service.ProjectContextResolver;
import com.qalab.qalabai.service.QaWorkflowService;
import com.qalab.qalabai.service.WorkflowJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full-test workflow, served asynchronously.
 *
 * <p>The workflow takes minutes and drives a browser, npm and several LLM calls.
 * Running it on the request thread meant every concurrent run pinned a Tomcat thread
 * for its duration, and any proxy in front dropped the connection at its own read
 * timeout — the {@code Connection reset by peer} in the cloud logs, where the backend
 * kept working and the user was told it had failed.</p>
 *
 * <p>So the POST now returns {@code 202 Accepted} with an operation id immediately,
 * the work runs on a bounded background pool, and the client polls
 * {@code GET /workflows/{operationId}} for the result. The pre-existing
 * {@code /progress} endpoint is unchanged, so a client already watching progress
 * needs no change.</p>
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class V1WorkflowController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1WorkflowController.class);

    private final QaWorkflowService qaWorkflowService;
    private final OperationProgressStore progressStore;
    private final WorkflowJobRunner jobRunner;

    public V1WorkflowController(ProjectContextResolver contextResolver,
                               QaWorkflowService qaWorkflowService,
                               OperationProgressStore progressStore,
                               WorkflowJobRunner jobRunner) {
        super(contextResolver);
        this.qaWorkflowService = qaWorkflowService;
        this.progressStore = progressStore;
        this.jobRunner = jobRunner;
    }

    @PostMapping("/full-test")
    public ResponseEntity<?> fullTest(@RequestBody V1FullWorkflowRequest request) {
        log.info("POST /api/v1/workflows/full-test url={}", request.url());
        String operationId = request.operationId() != null && !request.operationId().isBlank()
                ? request.operationId()
                : "op-" + java.util.UUID.randomUUID();

        try {
            jobRunner.submit(operationId, () -> {
                V1WorkflowResponse response = qaWorkflowService.runFullTest(request);
                // Hand the result back to the poller rather than leaving it only in
                // memory on the stack that ran it.
                jobRunner.complete(operationId, response);
            });
        } catch (WorkflowJobRunner.QueueFullException e) {
            log.warn("Rejecting full-test {} — {}", operationId, e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new V1WorkflowAccepted(operationId, "REJECTED",
                            "/api/v1/workflows/" + operationId, e.getMessage()));
        }

        return ResponseEntity.accepted()
                .body(new V1WorkflowAccepted(operationId, "QUEUED",
                        "/api/v1/workflows/" + operationId,
                        "Workflow accepted. Poll this url for the result; "
                                + "/api/v1/workflows/" + operationId + "/progress reports the live stage."));
    }

    /**
     * Live stage for an in-flight workflow.
     *
     * <p>Unchanged by the move to asynchronous execution, and deliberately so: a
     * client (the CLI) may already be polling this, and the async change was not a
     * reason to break it. Note it is a distinct path from
     * {@code GET /{operationId}}, which returns the terminal result.</p>
     */
    @GetMapping("/{operationId}/progress")
    public ResponseEntity<?> progress(@PathVariable String operationId) {
        OperationProgressStore.Progress progress = progressStore.get(operationId);
        if (progress == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "operationId", operationId, "status", "NOT_FOUND",
                    "stage", "UNKNOWN", "message", "no progress recorded for this operation"));
        }
        return ResponseEntity.ok(progress);
    }

    /**
     * Result of a previously submitted workflow.
     *
     * <ul>
     *   <li>{@code 200} — terminal, either COMPLETED or FAILED, with the full response</li>
     *   <li>{@code 202} — still running; the body carries the current stage</li>
     *   <li>{@code 404} — unknown or already evicted</li>
     * </ul>
     */
    @GetMapping("/{operationId}")
    public ResponseEntity<?> result(@PathVariable String operationId) {
        WorkflowJobRunner.Job job = jobRunner.get(operationId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", Map.of("code", "OPERATION_NOT_FOUND",
                            "message", "No workflow with operation id " + operationId
                                    + ". It may never have existed, or its result has been evicted.",
                            "operationId", operationId)));
        }

        if (!job.isTerminal()) {
            OperationProgressStore.Progress progress = progressStore.get(operationId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operationId", operationId);
            body.put("status", job.status());
            body.put("stage", progress != null ? progress.stage() : null);
            body.put("message", progress != null ? progress.message() : "queued");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        }

        if (job.result() instanceof V1WorkflowResponse response) {
            return ResponseEntity.ok(response);
        }
        // Terminal without a result means the workflow itself threw.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "operationId", operationId,
                "status", OperationStatus.FAILED.name(),
                "error", Map.of("code", "INTERNAL_ERROR",
                        "message", job.error() != null ? job.error() : "Workflow failed",
                        "operationId", operationId)));
    }
}
