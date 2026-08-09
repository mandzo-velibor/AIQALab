package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.api.v1.dto.V1LocatorAnalyzeRequest;
import com.qalab.qalabai.api.v1.dto.V1LocatorAnalyzeResponse;
import com.qalab.qalabai.api.v1.dto.V1LocatorsRequest;
import com.qalab.qalabai.api.v1.dto.V1LocatorsResponse;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.locator.intelligence.LocatorIntelligenceService;
import com.qalab.qalabai.locator.intelligence.model.HistoricalObservation;
import com.qalab.qalabai.locator.intelligence.model.LocatorIntelligence;
import com.qalab.qalabai.locator.intelligence.LocatorObservationService;
import com.qalab.qalabai.service.LocatorService;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class V1LocatorController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1LocatorController.class);

    private final LocatorService locatorService;
    private final LocatorIntelligenceService intelligenceService;
    private final LocatorObservationService observationService;

    public V1LocatorController(ProjectContextResolver contextResolver,
                               LocatorService locatorService,
                               LocatorIntelligenceService intelligenceService,
                               LocatorObservationService observationService) {
        super(contextResolver);
        this.locatorService = locatorService;
        this.intelligenceService = intelligenceService;
        this.observationService = observationService;
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

    @PostMapping("/locators/analyze")
    public ResponseEntity<V1LocatorAnalyzeResponse> analyze(@RequestBody V1LocatorAnalyzeRequest request) {
        requireUrl(request.url());
        if (request.locator() == null || request.locator().isBlank()) {
            throw com.qalab.qalabai.api.ApiException.invalidRequest("locator is required");
        }
        String operationId = operationId();
        log.info("POST /api/v1/locators/analyze operationId={} url={} locator={}",
                operationId, request.url(), request.locator());

        LocatorIntelligence intelligence = intelligenceService.analyze(
                request.url(), request.locator(), request.projectId());

        V1LocatorAnalyzeResponse response = new V1LocatorAnalyzeResponse(
                operationId,
                OperationStatus.COMPLETED,
                request.projectId() != null ? String.valueOf(request.projectId()) : null,
                request.url(),
                intelligence,
                LocalDateTime.now()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/projects/{projectId}/locators/history")
    public ResponseEntity<List<HistoricalObservation>> history(@PathVariable Long projectId) {
        log.info("GET /api/v1/projects/{}/locators/history", projectId);
        return ResponseEntity.ok(observationService.history(projectId));
    }

    @GetMapping("/projects/{projectId}/locators/{fingerprint}/history")
    public ResponseEntity<List<HistoricalObservation>> historyByFingerprint(@PathVariable Long projectId,
                                                                            @PathVariable String fingerprint) {
        log.info("GET /api/v1/projects/{}/locators/{}/history", projectId, fingerprint);
        return ResponseEntity.ok(observationService.historyByFingerprint(projectId, fingerprint));
    }
}
