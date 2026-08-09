package com.qalab.qalabai.dto.executor;

public record ExecutionRequest(
        Long testId,
        Boolean runAll,
        Long projectId,
        String testType,
        String instruction
) {}
