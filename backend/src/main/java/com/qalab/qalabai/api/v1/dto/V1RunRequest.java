package com.qalab.qalabai.api.v1.dto;

public record V1RunRequest(
        ProjectInfo project,
        Long testId,
        Boolean runAll,
        String workspacePath,
        Boolean healingAnalysis,
        String instruction,
        // Added by B-033. The v1 controller hardcoded null here, so migrating the
        // dashboard off the legacy surface would have silently disabled its test-type
        // selector (e2e / ui / api) with no error anywhere.
        String testType
) {
}
