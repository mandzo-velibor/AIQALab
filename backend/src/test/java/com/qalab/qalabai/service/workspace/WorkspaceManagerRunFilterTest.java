package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.service.git.GitService;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.playwright.PlaywrightTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the deterministic, LLM-free run filter (Sprint 14.6): Run All with a
 * structured test type selects only the spec files carrying the matching type
 * marker, and a single-test run ignores the filter because the type is already
 * embedded in the resolved filename.
 */
class WorkspaceManagerRunFilterTest {

    @TempDir
    Path workspace;

    private ProjectRepository projectRepository;
    private GitService gitService;
    private PlaywrightTool playwrightTool;
    private WorkspaceManager manager;
    private ProjectContext project;

    @BeforeEach
    void setUp() throws IOException {
        projectRepository = mock(ProjectRepository.class);
        gitService = mock(GitService.class);
        playwrightTool = mock(PlaywrightTool.class);
        when(playwrightTool.execute(any())).thenReturn(
                Map.of("status", "PASSED", "duration", 10L, "output", "ok"));
        manager = new WorkspaceManager(projectRepository, gitService, playwrightTool);

        project = new ProjectContext();
        project.setProjectId("demo");
        project.setWorkspacePath(workspace.toString());

        Files.createDirectories(workspace.resolve("tests"));
        Files.writeString(workspace.resolve("tests/login.api.spec.ts"), "api()");
        Files.writeString(workspace.resolve("tests/login.ui.spec.ts"), "ui()");
        Files.writeString(workspace.resolve("tests/dashboard.e2e.spec.ts"), "e2e()");
    }

    @Test
    void runAllWithTypeRunsOnlyMatchingFiles() {
        Map<String, Object> result = manager.execute(project, null, true, "api");

        assertEquals("PASSED", result.get("status"));
        ArgumentCaptor<ToolContext> captor = ArgumentCaptor.forClass(ToolContext.class);
        verify(playwrightTool).execute(captor.capture());
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) captor.getValue().get("testFiles");
        assertEquals(List.of("tests/login.api.spec.ts"), files);
    }

    @Test
    void runAllWithNoMatchingTypeReturnsPassedStubWithoutToolCall() throws IOException {
        Path empty = workspace.resolve("empty");
        Files.createDirectories(empty.resolve("tests"));
        ProjectContext other = new ProjectContext();
        other.setProjectId("other");
        other.setWorkspacePath(empty.toString());

        Map<String, Object> result = manager.execute(other, null, true, "api");

        assertEquals("PASSED", result.get("status"));
        assertTrue(String.valueOf(result.get("output")).contains("No tests of type 'api'"));
        verify(playwrightTool, never()).execute(any());
    }

    @Test
    void singleTestRunIgnoresMarkerFilter() {
        Map<String, Object> result = manager.execute(project, "tests/login.ui.spec.ts", false, "ui");

        assertEquals("PASSED", result.get("status"));
        ArgumentCaptor<ToolContext> captor = ArgumentCaptor.forClass(ToolContext.class);
        verify(playwrightTool).execute(captor.capture());
        assertEquals("tests/login.ui.spec.ts", captor.getValue().getString("testFile"));
        assertTrue(captor.getValue().getParams().containsKey("runAll"));
    }

    @Test
    void resolveFileNameEmbedsTypeMarker() {
        com.qalab.qalabai.model.GeneratedTest test = new com.qalab.qalabai.model.GeneratedTest();
        test.setScenarioName("Login with valid credentials");
        test.setTestType("api");
        assertEquals("login-with-valid-credentials.api.spec.ts", WorkspaceManager.resolveFileName(test));
    }

    @Test
    void resolveFileNameSkipsMarkerWithoutType() {
        com.qalab.qalabai.model.GeneratedTest test = new com.qalab.qalabai.model.GeneratedTest();
        test.setScenarioName("Login with valid credentials");
        test.setTestType(null);
        assertEquals("login-with-valid-credentials.spec.ts", WorkspaceManager.resolveFileName(test));
    }
}
