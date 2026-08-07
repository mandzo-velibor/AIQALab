package com.qalab.qalabai.controller;

import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.service.HealingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/healing")
public class HealingController {

    private static final Logger log = LoggerFactory.getLogger(HealingController.class);

    private final HealingService healingService;
    private final HealingSuggestionRepository healingSuggestionRepository;

    public HealingController(HealingService healingService,
                             HealingSuggestionRepository healingSuggestionRepository) {
        this.healingService = healingService;
        this.healingSuggestionRepository = healingSuggestionRepository;
    }

    @PostMapping("/analyze/{executionId}")
    public ResponseEntity<?> analyzeAndGenerateHealing(@PathVariable Long executionId) {
        log.info("POST /api/healing/analyze/{}", executionId);

        try {
            HealingSuggestion suggestion = healingService.generateHealingSuggestion(executionId);
            return ResponseEntity.ok(suggestion);
        } catch (Exception e) {
            log.error("Failed to generate healing suggestion: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Healing generation failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveSuggestion(@PathVariable Long id,
                                               @RequestParam(required = false, defaultValue = "user") String approvedBy) {
        log.info("POST /api/healing/{}/approve", id);

        try {
            HealingSuggestion suggestion = healingService.approveSuggestion(id, approvedBy);
            return ResponseEntity.ok(suggestion);
        } catch (Exception e) {
            log.error("Failed to approve suggestion: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Approval failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectSuggestion(@PathVariable Long id,
                                              @RequestParam(required = false, defaultValue = "user") String rejectedBy) {
        log.info("POST /api/healing/{}/reject", id);

        try {
            HealingSuggestion suggestion = healingService.rejectSuggestion(id, rejectedBy);
            return ResponseEntity.ok(suggestion);
        } catch (Exception e) {
            log.error("Failed to reject suggestion: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Rejection failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @GetMapping("/suggestions")
    public ResponseEntity<List<HealingSuggestion>> getSuggestions(@RequestParam(required = false) Long projectId) {
        log.info("GET /api/healing/suggestions");

        List<HealingSuggestion> suggestions;
        if (projectId != null) {
            suggestions = healingSuggestionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        } else {
            suggestions = healingSuggestionRepository.findAll();
        }

        return ResponseEntity.ok(suggestions);
    }
}
