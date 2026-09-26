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

    /** A file written into the workspace, with a workspace-relative POSIX path. */
    record WrittenFile(String path, String content) {
    }

    /**
     * What {@link #writeTests} actually put on disk.
     *
     * <p>Returned so the workflow response is derived from the write itself rather
     * than recomputed separately. Previously the response advertised only spec
     * sources while the page objects each spec imports were written — or not
     * written — independently, so the client could receive a set of files that
     * did not match, and could not compile, the workspace.</p>
     *
     * @param workspace   absolute path the files were written to
     * @param tests       spec files, relative to {@code workspace}
     * @param pageObjects page object files, relative to {@code workspace}
     */
    record WriteResult(String workspace, List<WrittenFile> tests, List<WrittenFile> pageObjects) {
    }

    /**
     * Persists tests and reports exactly what was written, including page objects.
     * The default implementation delegates to {@link #writeTests} and reports the
     * spec sources only, so alternative providers stay source-compatible.
     */
    default WriteResult writeTestsAndReport(ProjectContext project, List<GeneratedTest> tests) {
        String workspace = writeTests(project, tests);
        List<WrittenFile> specs = tests == null ? List.of() : tests.stream()
                .filter(t -> t.getTestCode() != null && !t.getTestCode().isBlank())
                .map(t -> new WrittenFile(TestWorkspaceService.resolveFileName(t), t.getTestCode()))
                .toList();
        return new WriteResult(workspace, specs, List.of());
    }

    /**
     * Executes tests in the given workspace.
     *
     * @return map with keys: status, duration, output (and optionally error).
     */
    Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll);

    /**
     * Executes tests in the given workspace, applying a deterministic structured
     * test-type filter (ALL/UI/E2E/API) before invoking the runner. A {@code null}
     * or blank {@code testType} behaves exactly like {@link #execute}.
     */
    default Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll, String testType) {
        return execute(project, testFile, runAll);
    }

    /** Collects artifacts (screenshots, traces, videos) produced by the last execution. */
    Map<String, String> collectArtifacts(ProjectContext project);
}
