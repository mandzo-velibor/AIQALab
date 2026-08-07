package com.qalab.qalabai.dto.planner;

import java.util.List;

public record TestPlanResponse(int scenarioCount, List<TestScenarioDto> scenarios) {}
