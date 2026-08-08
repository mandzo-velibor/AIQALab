package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.v1.dto.ProjectInfo;
import com.qalab.qalabai.service.ProjectContextResolver;

import java.util.UUID;

abstract class AbstractV1Controller {

    protected final ProjectContextResolver contextResolver;

    protected AbstractV1Controller(ProjectContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    protected ProjectContext project(ProjectInfo info) {
        return contextResolver.resolve(info);
    }

    protected Long databaseId(ProjectInfo info) {
        return contextResolver.databaseId(info);
    }

    protected void requireUrl(String url) {
        if (url == null || url.isBlank()) {
            throw ApiException.invalidRequest("url is required");
        }
    }

    protected String operationId() {
        return "op-" + UUID.randomUUID();
    }
}
