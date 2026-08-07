package com.qalab.qalabai.dto.project;

public record CreateProjectRequest(
        String name,
        String description,
        String baseUrl,
        String repositoryUrl,
        String framework
) {}
