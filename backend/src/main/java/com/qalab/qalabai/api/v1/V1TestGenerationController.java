package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1GenerateTestsRequest;
import com.qalab.qalabai.api.v1.dto.V1TestsResponse;
import com.qalab.qalabai.service.CodeGenerationService;
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
public class V1TestGenerationController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1TestGenerationController.class);

    private final CodeGenerationService codeGenerationService;

    public V1TestGenerationController(ProjectContextResolver contextResolver,
                                      CodeGenerationService codeGenerationService) {
        super(contextResolver);
        this.codeGenerationService = codeGenerationService;
    }

    @PostMapping("/tests")
    public ResponseEntity<V1TestsResponse> generate(@RequestBody V1GenerateTestsRequest request) {
        requireUrl(request.url());
        ProjectContext project = project(request.project());
        String operationId = operationId();
        log.info("POST /api/v1/tests operationId={} url={}", operationId, request.url());

        CodeGenerationService.GeneratedContent content = codeGenerationService.generateContent(
                request.url(), databaseId(request.project()), request.instruction(), request.testType());

        V1TestsResponse response = new V1TestsResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                request.url(),
                content.files().size(),
                content.files(),
                content.instruction(),
                content.testType(),
                content.note(),
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
