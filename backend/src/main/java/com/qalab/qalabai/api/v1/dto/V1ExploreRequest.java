package com.qalab.qalabai.api.v1.dto;

public record V1ExploreRequest(ProjectInfo project, String url, Boolean forceRefresh, String username, String password) {
}
