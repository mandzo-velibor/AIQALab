package com.qalab.qalabai.dto.analysis;

import java.util.List;

public record DetectedForm(
        String name,
        List<String> inputs
) {
    public DetectedForm {
        inputs = inputs != null ? List.copyOf(inputs) : List.of();
    }
}
