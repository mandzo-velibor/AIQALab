package com.qalab.qalabai.controller;

import com.qalab.qalabai.dto.planner.TestPlanRequest;
import com.qalab.qalabai.dto.planner.TestPlanResponse;
import com.qalab.qalabai.service.PlanningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/test-plans")
public class TestPlanController {

    private static final Logger log = LoggerFactory.getLogger(TestPlanController.class);

    private final PlanningService planningService;

    public TestPlanController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody TestPlanRequest request) {
        log.info("POST /api/test-plans/generate called with URL: {}", request.url());

        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        try {
            TestPlanResponse response = planningService.generateTestPlan(request.url(), request.projectId(), request.instruction());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Test plan generation failed for URL {}: {}", request.url(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Test plan generation failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                    "url", request.url()
            ));
        }
    }

    @GetMapping
    public ResponseEntity<?> getByUrl(@RequestParam String url) {
        return ResponseEntity.ok(planningService.getTestPlansForUrl(url));
    }
}
