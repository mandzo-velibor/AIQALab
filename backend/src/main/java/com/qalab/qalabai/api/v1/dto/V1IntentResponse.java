package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.intent.Intent;

import java.time.LocalDateTime;
import java.util.List;

public record V1IntentResponse(
        String operationId,
        OperationStatus status,
        Intent intent,
        String url,
        List<String> steps,
        List<String> matchedKeywords,
        LocalDateTime createdAt
) {
}
