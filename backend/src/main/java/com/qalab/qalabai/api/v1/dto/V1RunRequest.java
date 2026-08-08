package com.qalab.qalabai.api.v1.dto;

public record V1RunRequest(
        ProjectInfo project,
        Long testId,
        Boolean runAll,
        String workspacePath,
        Boolean healingAnalysis
) {
}
