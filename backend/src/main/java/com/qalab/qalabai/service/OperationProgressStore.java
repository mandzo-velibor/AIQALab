package com.qalab.qalabai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of lightweight progress snapshots for long-running operations
 * (e.g. the full QA workflow). The Core keeps operations synchronous; this store
 * lets a client (like the CLI) poll stage messages while the POST request is still
 * in flight.
 */
@Service
public class OperationProgressStore {

    private static final Logger log = LoggerFactory.getLogger(OperationProgressStore.class);

    public record Progress(String operationId, String status, String stage, String message, LocalDateTime updatedAt) {
    }

    private final Map<String, Progress> progress = new ConcurrentHashMap<>();

    public void update(String operationId, String status, String stage, String message) {
        if (operationId == null || operationId.isBlank()) {
            return;
        }
        progress.put(operationId, new Progress(operationId, status, stage, message, LocalDateTime.now()));
    }

    public Progress get(String operationId) {
        return progress.get(operationId);
    }

    public void clear(String operationId) {
        if (operationId != null) {
            progress.remove(operationId);
        }
    }
}
