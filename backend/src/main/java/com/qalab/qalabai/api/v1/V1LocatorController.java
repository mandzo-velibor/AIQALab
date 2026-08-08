package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1LocatorsRequest;
import com.qalab.qalabai.api.v1.dto.V1LocatorsResponse;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.service.LocatorService;
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
public class V1LocatorController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1LocatorController.class);

    private final LocatorService locatorService;

    public V1LocatorController(ProjectContextResolver contextResolver,
                               LocatorService locatorService) {
        super(contextResolver);
        this.locatorService = locatorService;
    }

    @PostMapping("/locators")
    public ResponseEntity<V1LocatorsResponse> generate(@RequestBody V1LocatorsRequest request) {
        requireUrl(request.url());
        ProjectContext project = project(request.project());
        String operationId = operationId();
        log.info("POST /api/v1/locators operationId={} url={}", operationId, request.url());

        LocatorResponse result = locatorService.generateLocators(request.url(), databaseId(request.project()));

        V1LocatorsResponse response = new V1LocatorsResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                request.url(),
                result.generated(),
                result.locators(),
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
