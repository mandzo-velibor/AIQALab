package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.api.v1.dto.ProjectInfo;
import com.qalab.qalabai.repository.ProjectRepository;
import org.springframework.stereotype.Service;

/**
 * Builds a {@link ProjectContext} from the external project identity supplied
 * in a service request, optionally enriching it from a project registered in
 * the Core (used by the UI). No source code ever enters the context.
 */
@Service
public class ProjectContextResolver {

    private final ProjectRepository projectRepository;

    public ProjectContextResolver(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public ProjectContext resolve(ProjectInfo info) {
        if (info == null) {
            throw ApiException.invalidProjectContext("project is required");
        }
        if (info.projectId() == null || info.projectId().isBlank()) {
            throw ApiException.invalidProjectContext("project.projectId is required");
        }

        ProjectContext context = new ProjectContext();
        context.setProjectId(info.projectId());
        context.setProjectName(info.name());
        context.setBaseUrl(info.baseUrl());
        context.setFramework(info.framework());
        context.setLanguage(info.language());
        context.setWorkspacePath(info.workspacePath());

        if (info.databaseId() != null) {
            enrichFromDatabase(context, info.databaseId());
        }

        return context;
    }

    public String projectId(ProjectInfo info) {
        if (info == null || info.projectId() == null || info.projectId().isBlank()) {
            return null;
        }
        return info.databaseId() != null ? String.valueOf(info.databaseId()) : info.projectId();
    }

    public Long databaseId(ProjectInfo info) {
        return info != null ? info.databaseId() : null;
    }

    private void enrichFromDatabase(ProjectContext context, Long databaseId) {
        var project = projectRepository.findById(databaseId)
                .orElseThrow(() -> ApiException.projectNotFound(
                        "Project databaseId not found: " + databaseId));
        if (isBlank(context.getBaseUrl())) {
            context.setBaseUrl(project.getBaseUrl());
        }
        if (isBlank(context.getFramework())) {
            context.setFramework(project.getFramework());
        }
        if (isBlank(context.getProjectName())) {
            context.setProjectName(project.getName());
        }
        if (isBlank(context.getWorkspacePath())) {
            context.setWorkspacePath(project.getWorkspacePath());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
