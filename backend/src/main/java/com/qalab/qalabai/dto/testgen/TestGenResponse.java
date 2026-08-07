package com.qalab.qalabai.dto.testgen;

import java.util.List;

public record TestGenResponse(int generated, List<GeneratedTestDto> tests) {}
