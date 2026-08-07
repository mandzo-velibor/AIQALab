package com.qalab.qalabai.controller;

import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.service.FailureAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/executions")
public class FailureAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalysisController.class);

    private final FailureAnalysisService failureAnalysisService;

    public FailureAnalysisController(FailureAnalysisService failureAnalysisService) {
        this.failureAnalysisService = failureAnalysisService;
    }

    @PostMapping("/{executionId}/analyze")
    public ResponseEntity<?> analyzeExecution(@PathVariable Long executionId,
                                              @RequestParam Long projectId) {
        log.info("POST /api/executions/{}/analyze for project {}", executionId, projectId);

        try {
            FailureAnalysis analysis = failureAnalysisService.analyzeExecution(executionId, projectId);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            log.error("Failed to analyze execution: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Analysis failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }
}
