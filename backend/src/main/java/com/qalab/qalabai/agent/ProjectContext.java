package com.qalab.qalabai.agent;

public class ProjectContext {

    private Long projectId;
    private String workspacePath;
    private String baseUrl;
    private String framework;

    public ProjectContext() {
    }

    public ProjectContext(Long projectId, String workspacePath, String baseUrl, String framework) {
        this.projectId = projectId;
        this.workspacePath = workspacePath;
        this.baseUrl = baseUrl;
        this.framework = framework;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
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
}
