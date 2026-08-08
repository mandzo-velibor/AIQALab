package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.GeneratedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Legacy facade used by the UI flows. All filesystem access is delegated to
 * {@link WorkspaceProvider}; this service holds no path logic of its own.
 */
@Service
public class TestWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(TestWorkspaceService.class);

    private final WorkspaceProvider workspaceProvider;
    private final WorkspaceManager workspaceManager;

    public TestWorkspaceService(WorkspaceProvider workspaceProvider,
                                WorkspaceManager workspaceManager) {
        this.workspaceProvider = workspaceProvider;
        this.workspaceManager = workspaceManager;
    }

    public String writeTestFiles(Long projectId, List<GeneratedTest> tests) {
        if (projectId == null || tests == null || tests.isEmpty()) {
            return null;
        }

        ProjectContext context = workspaceManager.getProjectContext(projectId);
        String workspace = workspaceProvider.writeTests(context, tests);
        log.info("Wrote {} test files to workspace {}", tests.size(), workspace);
        return workspace;
    }

    public static String resolveFileName(GeneratedTest test) {
        return WorkspaceManager.resolveFileName(test);
    }
}
