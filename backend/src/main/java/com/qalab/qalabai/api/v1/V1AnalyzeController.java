package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1AnalyzeRequest;
import com.qalab.qalabai.api.v1.dto.V1AnalyzeResponse;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.service.ExplorerService;
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
public class V1AnalyzeController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1AnalyzeController.class);

    private final ExplorerService explorerService;

    public V1AnalyzeController(ProjectContextResolver contextResolver,
                               ExplorerService explorerService) {
        super(contextResolver);
        this.explorerService = explorerService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<V1AnalyzeResponse> analyze(@RequestBody V1AnalyzeRequest request) {
        requireUrl(request.url());
        ProjectContext project = project(request.project());
        String operationId = operationId();
        log.info("POST /api/v1/analyze operationId={} url={}", operationId, request.url());

        AnalysisResponse analysis = explorerService.analyze(
                request.url(),
                Boolean.TRUE.equals(request.forceRefresh()),
                databaseId(request.project()),
                request.username(),
                request.password(),
                request.instruction()
        );

        V1AnalyzeResponse response = new V1AnalyzeResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                request.url(),
                analysis,
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
