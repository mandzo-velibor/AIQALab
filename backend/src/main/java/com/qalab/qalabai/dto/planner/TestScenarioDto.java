package com.qalab.qalabai.dto.planner;

import java.util.List;

public record TestScenarioDto(
        Long id,
        String name,
        String type,
        String priority,
        String description,
        List<String> steps,
        List<String> requiredElements
) {}
