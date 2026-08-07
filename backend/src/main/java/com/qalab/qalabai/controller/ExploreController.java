package com.qalab.qalabai.controller;

import com.qalab.qalabai.agent.AgentOrchestrator;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.dto.ExploreRequest;
import com.qalab.qalabai.dto.ExploreResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ExploreController {

    private static final Logger log = LoggerFactory.getLogger(ExploreController.class);

    private final AgentOrchestrator orchestrator;

    public ExploreController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/explore")
    public ResponseEntity<?> explore(@RequestBody ExploreRequest request) {
        log.info("POST /api/explore called with URL: {}", request.getUrl());

        if (request.getUrl() == null || request.getUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        String taskId = UUID.randomUUID().toString();
        Task task = new Task(taskId, "EXPLORE", request.getUrl());

        try {
            AgentResult explorerResult = orchestrator.executeSingle("Explorer", task);

            ExploreResponse response = new ExploreResponse();
            response.setUrl(request.getUrl());

            if (explorerResult.isSuccess()) {
                Map<String, Object> data = explorerResult.getData();
                response.setTitle((String) data.get("title"));
                response.setScreenshotBase64((String) data.get("screenshotBase64"));
                response.setButtonCount(toLong(data.get("buttonCount")));
                response.setInputCount(toLong(data.get("inputCount")));
                response.setLinkCount(toLong(data.get("linkCount")));
                response.setFormCount(toLong(data.get("formCount")));
            }

            Map<String, Object> agentResults = new HashMap<>();
            agentResults.put(explorerResult.getAgentName(), Map.of(
                    "success", explorerResult.isSuccess(),
                    "message", explorerResult.getMessage() != null ? explorerResult.getMessage() : ""
            ));
            response.setAgentResults(agentResults);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Explore failed for URL {}: {}", request.getUrl(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Explore failed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    private long toLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        return 0L;
    }
}
