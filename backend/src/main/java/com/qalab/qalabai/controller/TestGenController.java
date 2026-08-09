package com.qalab.qalabai.controller;

import com.qalab.qalabai.dto.testgen.TestGenRequest;
import com.qalab.qalabai.dto.testgen.TestGenResponse;
import com.qalab.qalabai.service.CodeGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tests")
public class TestGenController {

    private static final Logger log = LoggerFactory.getLogger(TestGenController.class);

    private final CodeGenerationService codeGenerationService;

    public TestGenController(CodeGenerationService codeGenerationService) {
        this.codeGenerationService = codeGenerationService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody TestGenRequest request) {
        log.info("POST /api/tests/generate called with URL: {}", request.url());

        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        try {
            TestGenResponse response = codeGenerationService.generateTests(request.url(), request.projectId(), request.instruction(), request.testType());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Test generation failed for URL {}: {}", request.url(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Test generation failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                    "url", request.url()
            ));
        }
    }

    @GetMapping
    public ResponseEntity<?> getByUrl(@RequestParam String url) {
        return ResponseEntity.ok(codeGenerationService.getTestsForUrl(url));
    }
}
