package com.qalab.qalabai.agent;

/**
 * ProjectContext carries only the identity and context of an external QA project.
 *
 * <p>The Core never treats a project as a source repository it owns. The context
 * contains just enough information to execute an operation; the target project
 * owns its source code, tests, page objects and Playwright configuration.</p>
 */
public class ProjectContext {

    /** Logical project identity, e.g. "the-internet-tests". May equal the database id as a string. */
    private String projectId;
    private String projectName;
    private String baseUrl;
    private String framework;
    private String language;
    private String workspacePath;

    public ProjectContext() {
    }

    public ProjectContext(Long projectId, String workspacePath, String baseUrl, String framework) {
        this.projectId = projectId != null ? String.valueOf(projectId) : null;
        this.workspacePath = workspacePath;
        this.baseUrl = baseUrl;
        this.framework = framework;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getFramework() {
        return framework;
    }

    public void setFramework(String framework) {
        this.framework = framework;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public Long getDatabaseId() {
        if (projectId == null) {
            return null;
        }
        try {
            return Long.valueOf(projectId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String requireProjectId() {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("project.projectId is required");
        }
        return projectId;
    }

    public String requireBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("project.baseUrl is required");
        }
        return baseUrl;
    }
}
