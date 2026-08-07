package com.qalab.qalabai.dto.analysis;

import java.util.List;

public record DetectedTable(
        String name,
        List<String> columns
) {
    public DetectedTable {
        columns = columns != null ? List.copyOf(columns) : List.of();
    }
}
