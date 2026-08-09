package com.qalab.qalabai.controller;

import com.qalab.qalabai.dto.executor.ExecutionRequest;
import com.qalab.qalabai.dto.executor.ExecutionResponse;
import com.qalab.qalabai.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody ExecutionRequest request) {
        log.info("POST /api/executions/run called with testId: {}, runAll: {}", request.testId(), request.runAll());

        try {
            ExecutionResponse response;
            if (request.runAll() != null && request.runAll()) {
                response = executionService.runAllTests(request.projectId(), false, request.testType(), request.instruction()).response();
            } else if (request.testId() != null) {
                response = executionService.runTest(request.testId(), request.projectId(), false, request.testType(), request.instruction()).response();
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "testId or runAll is required"));
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Execution failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Execution failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(required = false) Long projectId) {
        return ResponseEntity.ok(executionService.getExecutionHistory(projectId));
    }
}
