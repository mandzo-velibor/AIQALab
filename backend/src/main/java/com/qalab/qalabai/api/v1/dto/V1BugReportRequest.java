package com.qalab.qalabai.api.v1.dto;

public record V1BugReportRequest(ProjectInfo project, Long executionId, String instruction) {
}
