package com.qalab.qalabai.controller;

import com.qalab.qalabai.dto.locator.LocatorRequest;
import com.qalab.qalabai.dto.locator.LocatorResponse;
import com.qalab.qalabai.service.LocatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/locators")
public class LocatorController {

    private static final Logger log = LoggerFactory.getLogger(LocatorController.class);

    private final LocatorService locatorService;

    public LocatorController(LocatorService locatorService) {
        this.locatorService = locatorService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody LocatorRequest request) {
        log.info("POST /api/locators/generate called with URL: {}", request.url());

        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        try {
            LocatorResponse response = locatorService.generateLocators(request.url(), request.projectId(), request.instruction());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Locator generation failed for URL {}: {}", request.url(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Locator generation failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                    "url", request.url()
            ));
        }
    }

    @GetMapping
    public ResponseEntity<?> getByUrl(@RequestParam String url) {
        return ResponseEntity.ok(locatorService.getLocatorsForUrl(url));
    }
}
