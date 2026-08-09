package com.qalab.qalabai.dto.executor;

public record ExecutionResponse(
        Long executionId,
        String status,
        long durationMs,
        String errorMessage,
        String consoleLogs,
        String testType,
        String instruction,
        String note
) {}
