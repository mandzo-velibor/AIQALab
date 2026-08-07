package com.qalab.qalabai.controller;

import com.qalab.qalabai.dto.AnalyzeRequest;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.service.ExplorerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    private final ExplorerService explorerService;

    public AnalysisController(ExplorerService explorerService) {
        this.explorerService = explorerService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody AnalyzeRequest request) {
        log.info("POST /api/analyze called with URL: {}", request.getUrl());

        if (request.getUrl() == null || request.getUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        try {
            AnalysisResponse response = explorerService.analyze(
                    request.getUrl(),
                    request.isForceRefresh(),
                    request.getProjectId(),
                    request.getUsername(),
                    request.getPassword()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Analysis failed for URL {}: {}", request.getUrl(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Analysis failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                    "url", request.getUrl()
            ));
        }
    }
}
