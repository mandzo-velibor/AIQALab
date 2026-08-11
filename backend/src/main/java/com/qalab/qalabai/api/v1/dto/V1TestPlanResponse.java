package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.dto.planner.TestScenarioDto;

import java.time.LocalDateTime;
import java.util.List;

public record V1TestPlanResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        String url,
        int scenarioCount,
        List<TestScenarioDto> scenarios,
        String instruction,
        LocalDateTime createdAt
) {
}
