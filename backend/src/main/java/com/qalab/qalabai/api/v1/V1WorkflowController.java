package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.v1.dto.V1FullWorkflowRequest;
import com.qalab.qalabai.api.v1.dto.V1WorkflowResponse;
import com.qalab.qalabai.service.OperationProgressStore;
import com.qalab.qalabai.service.ProjectContextResolver;
import com.qalab.qalabai.service.QaWorkflowService;
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

@RestController
@RequestMapping("/api/v1")
public class V1WorkflowController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1WorkflowController.class);

    private final QaWorkflowService qaWorkflowService;
    private final OperationProgressStore progressStore;

    public V1WorkflowController(ProjectContextResolver contextResolver,
                                QaWorkflowService qaWorkflowService,
                                OperationProgressStore progressStore) {
        super(contextResolver);
        this.qaWorkflowService = qaWorkflowService;
        this.progressStore = progressStore;
    }

    @PostMapping("/workflows/full-test")
    public ResponseEntity<V1WorkflowResponse> fullTest(@RequestBody V1FullWorkflowRequest request) {
        log.info("POST /api/v1/workflows/full-test url={}", request.url());
        V1WorkflowResponse response = qaWorkflowService.runFullTest(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/workflows/{operationId}/progress")
    public ResponseEntity<?> progress(@PathVariable String operationId) {
        OperationProgressStore.Progress progress = progressStore.get(operationId);
        if (progress == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    java.util.Map.of("operationId", operationId, "status", "NOT_FOUND",
                            "stage", "UNKNOWN", "message", "no progress recorded for this operation"));
        }
        return ResponseEntity.ok(progress);
    }
}
