package com.qalab.qalabai.agent;

import com.qalab.qalabai.ai.gateway.AiCredentialMode;
import com.qalab.qalabai.ai.gateway.AiProviderConfig;
import com.qalab.qalabai.ai.gateway.AiProviderType;

/**
 * Builds an {@link com.qalab.qalabai.ai.gateway.AgentExecutionContext} from a
 * {@link Task}, letting agents carry the shared workflow context (project id,
 * operation id, provider config) into every AI call without touching providers.
 */
public final class ProjectContextUtil {

    private ProjectContextUtil() {
    }

    public static ProjectContext fromTask(Task task) {
        ProjectContext project = new ProjectContext();
        Long projectId = task.getContextValue("projectId") instanceof Number n ? n.longValue() : null;
        if (projectId != null) {
            project.setProjectId(String.valueOf(projectId));
        }
        String projectName = (String) task.getContextValue("projectName");
        if (projectName != null) {
            project.setProjectName(projectName);
        }
        String baseUrl = (String) task.getContextValue("baseUrl");
        if (baseUrl != null) {
            project.setBaseUrl(baseUrl);
        }
        return project;
    }

    public static AiProviderConfig providerConfig(AiProviderType provider, String model, AiCredentialMode mode) {
        return new AiProviderConfig(provider, model, mode);
    }
}
