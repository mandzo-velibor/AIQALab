package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.dto.testgen.GeneratedFile;

import java.time.LocalDateTime;
import java.util.List;

public record V1TestsResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        String url,
        int count,
        List<GeneratedFile> files,
        String instruction,
        String testType,
        String note,
        LocalDateTime createdAt
) {
}
