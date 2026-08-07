package com.qalab.qalabai.dto.project;

import java.time.LocalDateTime;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        String baseUrl,
        String repositoryUrl,
        String framework,
        String workspacePath,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
