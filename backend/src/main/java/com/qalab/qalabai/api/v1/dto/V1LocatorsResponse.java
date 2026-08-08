package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.api.OperationStatus;
import com.qalab.qalabai.dto.locator.LocatorDto;

import java.time.LocalDateTime;
import java.util.List;

public record V1LocatorsResponse(
        String operationId,
        OperationStatus status,
        String projectId,
        String url,
        int count,
        List<LocatorDto> locators,
        LocalDateTime createdAt
) {
}
