package com.qalab.qalabai.api.v1.dto;

/**
 * Identity and context of the external QA project calling the Core.
 *
 * <p>Only the minimal information required to execute an operation. The Core
 * never receives or owns the project's source code through this record.</p>
 *
 * @param projectId   logical identity, e.g. "the-internet-tests"
 * @param name        human-readable name (optional)
 * @param baseUrl     application base URL
 * @param framework   e.g. PLAYWRIGHT
 * @param language    e.g. TYPESCRIPT
 * @param workspacePath optional local workspace for execution
 * @param databaseId  optional id of a project registered in the Core UI
 */
public record ProjectInfo(
        String projectId,
        String name,
        String baseUrl,
        String framework,
        String language,
        String workspacePath,
        Long databaseId
) {

    public static ProjectInfo of(String projectId, String baseUrl, String framework, String language) {
        return new ProjectInfo(projectId, null, baseUrl, framework, language, null, null);
    }

    public ProjectInfo withDatabaseId(Long id) {
        return new ProjectInfo(projectId, name, baseUrl, framework, language, workspacePath, id);
    }
}
