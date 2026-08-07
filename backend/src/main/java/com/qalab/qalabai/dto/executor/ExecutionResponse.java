package com.qalab.qalabai.dto.executor;

public record ExecutionResponse(
        Long executionId,
        String status,
        Long duration,
        String errorMessage,
        String consoleLogs
) {}
