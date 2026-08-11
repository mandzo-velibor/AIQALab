package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1ExploreRequest;
import com.qalab.qalabai.api.v1.dto.V1ExploreResponse;
import com.qalab.qalabai.dto.ExploreResponse;
import com.qalab.qalabai.service.ExplorationService;
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
public class V1ExploreController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1ExploreController.class);

    private final ExplorationService explorationService;

    public V1ExploreController(ProjectContextResolver contextResolver,
                               ExplorationService explorationService) {
        super(contextResolver);
        this.explorationService = explorationService;
    }

    @PostMapping("/explore")
    public ResponseEntity<V1ExploreResponse> explore(@RequestBody V1ExploreRequest request) {
        requireUrl(request.url());
        ProjectContext project = project(request.project());
        String operationId = operationId();
        log.info("POST /api/v1/explore operationId={} url={} instruction={}", operationId, request.url(),
                com.qalab.qalabai.util.UserInstructions.isPresent(request.instruction())
                        ? "\"" + request.instruction() + "\"" : "none");

        ExploreResponse snapshot = explorationService.explore(request.url());

        V1ExploreResponse response = new V1ExploreResponse(
                operationId,
                OperationStatus.COMPLETED,
                project.getProjectId(),
                request.url(),
                snapshot.getTitle(),
                null,
                snapshot.getButtonCount(),
                snapshot.getInputCount(),
                snapshot.getLinkCount(),
                snapshot.getFormCount(),
                snapshot.getScreenshotBase64(),
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }
}
