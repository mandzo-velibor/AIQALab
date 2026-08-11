package com.qalab.qalabai.api.v1.dto;

public record V1AnalyzeRequest(ProjectInfo project, String url, Boolean forceRefresh, String username, String password, String instruction) {
}
