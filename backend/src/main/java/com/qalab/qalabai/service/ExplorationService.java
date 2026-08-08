package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.explorer.ExplorerAgent;
import com.qalab.qalabai.dto.ExploreResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * EXPLORE capability: opens a URL and describes the page (title, counts,
 * screenshot). No AI involvement; the raw page snapshot is returned.
 */
@Service
public class ExplorationService {

    private static final Logger log = LoggerFactory.getLogger(ExplorationService.class);

    private final ExplorerAgent explorerAgent;

    public ExplorationService(ExplorerAgent explorerAgent) {
        this.explorerAgent = explorerAgent;
    }

    public ExploreResponse explore(String url) {
        log.info("Exploring URL: {}", url);

        Task task = new Task(UUID.randomUUID().toString(), "EXPLORE", url);
        AgentResult result = explorerAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Explore failed: " + result.getMessage());
        }

        ExploreResponse response = new ExploreResponse();
        response.setUrl(url);
        response.setTitle((String) result.getData().get("title"));
        response.setScreenshotBase64((String) result.getData().get("screenshotBase64"));
        response.setButtonCount(toLong(result.getData().get("buttonCount")));
        response.setInputCount(toLong(result.getData().get("inputCount")));
        response.setLinkCount(toLong(result.getData().get("linkCount")));
        response.setFormCount(toLong(result.getData().get("formCount")));
        return response;
    }

    private long toLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        return 0L;
    }
}
