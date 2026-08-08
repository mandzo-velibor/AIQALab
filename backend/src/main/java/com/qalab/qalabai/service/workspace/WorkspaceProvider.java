package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.GeneratedTest;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over where tests are executed and where source artifacts live.
 *
 * <p>The Core never assumes it owns the target project's filesystem. All
 * workspace access flows through this provider. Agents must never construct
 * filesystem paths themselves.</p>
 */
public interface WorkspaceProvider {

    /** Returns the working directory for the given project, or throws if unavailable. */
    String getWorkspace(ProjectContext project);

    /** Ensures the workspace exists and is ready (dependencies installed). No-op when not applicable. */
    void prepareWorkspace(ProjectContext project);

    /**
     * Persists generated test source into the target workspace.
     * This is explicit opt-in from the client; the Core does not auto-write by default.
     */
    String writeTests(ProjectContext project, List<GeneratedTest> tests);

    /**
     * Executes tests in the given workspace.
     *
     * @return map with keys: status, duration, output (and optionally error).
     */
    Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll);

    /** Collects artifacts (screenshots, traces, videos) produced by the last execution. */
    Map<String, String> collectArtifacts(ProjectContext project);
}
