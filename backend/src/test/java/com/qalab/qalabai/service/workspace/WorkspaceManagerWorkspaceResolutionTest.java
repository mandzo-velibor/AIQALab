package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.service.git.GitService;
import com.qalab.qalabai.tool.playwright.PlaywrightTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Workspace resolution: a client-supplied workspace must win, must be reported
 * as an absolute path, and the written files must land inside it.
 */
class WorkspaceManagerWorkspaceResolutionTest {

    @TempDir
    Path tempRoot;

    private WorkspaceManager manager;
    private PlaywrightTool playwrightTool;

    @BeforeEach
    void setUp() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        GitService gitService = mock(GitService.class);
        playwrightTool = mock(PlaywrightTool.class);
        manager = new WorkspaceManager(projectRepository, gitService, playwrightTool);
        ReflectionTestUtils.setField(manager, "workspacesDir", tempRoot.resolve("server-workspaces").toString());
    }

    private ProjectContext projectWith(String workspacePath, Long databaseId) {
        ProjectContext project = new ProjectContext();
        // ProjectContext derives getDatabaseId() from a numeric projectId, which is how
        // a registered project is distinguished from a purely logical one.
        project.setProjectId(databaseId != null ? String.valueOf(databaseId) : "demo");
        project.setWorkspacePath(workspacePath);
        return project;
    }

    @Test
    void explicitWorkspacePathWinsOverTheServerSideFallback() {
        Path clientWorkspace = tempRoot.resolve("client-workspace");
        // A databaseId is present, so the legacy <workspacesDir>/project-N path would be
        // used if the explicit path were not preferred.
        String resolved = manager.getWorkspace(projectWith(clientWorkspace.toString(), 42L));

        assertEquals(clientWorkspace.toAbsolutePath().normalize().toString(), resolved);
        assertTrue(!resolved.contains("server-workspaces"),
                "must not fall back to the server workspace, got " + resolved);
    }

    @Test
    void relativeWorkspacePathIsNormalisedToAbsolute() {
        // Regression guard: a relative path used to be returned verbatim, so it resolved
        // against the server's working directory — which differs between a local run and
        // a container, silently writing somewhere the client never asked for.
        String resolved = manager.getWorkspace(projectWith("relative/qa-workspace", null));

        assertTrue(Path.of(resolved).isAbsolute(), "expected an absolute path, got " + resolved);
        assertTrue(resolved.endsWith("relative/qa-workspace"), "unexpected path: " + resolved);
    }

    @Test
    void blankWorkspaceFallsBackToTheRegisteredProjectDirectory() {
        String resolved = manager.getWorkspace(projectWith("   ", 7L));

        assertEquals(tempRoot.resolve("server-workspaces").resolve("project-7")
                .toAbsolutePath().normalize().toString(), resolved);
    }

    @Test
    void noWorkspaceAndNoRegisteredProjectFailsLoudly() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> manager.getWorkspace(projectWith(null, null)));
        assertTrue(e.getMessage().contains("workspacePath"),
                "the error should tell the caller what to provide, was: " + e.getMessage());
    }

    @Test
    void writtenFilesLandInsideTheClientWorkspaceAndAreReported() throws IOException {
        Path clientWorkspace = tempRoot.resolve("client-workspace");
        Files.createDirectories(clientWorkspace);

        GeneratedTest test = new GeneratedTest();
        test.setScenarioName("Successful login");
        test.setTestCode("import { LoginPage } from '../pages/LoginPage_successful-login';\n"
                + "test('x', async () => {});");
        test.setPageObjectCode("export class LoginPage { readonly a: string; }");

        WorkspaceProvider.WriteResult result =
                manager.writeTestsAndReport(projectWith(clientWorkspace.toString(), null), List.of(test));

        assertEquals(clientWorkspace.toAbsolutePath().normalize().toString(), result.workspace());

        // The spec must exist under tests/ with its import already rewritten, and the
        // page object it imports must exist too — otherwise the client receives a tree
        // that cannot compile.
        Path spec = clientWorkspace.resolve("tests/successful-login.spec.ts");
        assertTrue(Files.exists(spec), "spec not written to " + spec);
        assertTrue(Files.readString(spec).contains("pages/LoginPage_successful-login"),
                "import was not repointed at the per-test page object");

        Path perTest = clientWorkspace.resolve("pages/LoginPage_successful-login.ts");
        Path merged = clientWorkspace.resolve("pages/LoginPage.ts");
        assertTrue(Files.exists(perTest), "per-test page object missing");
        assertTrue(Files.exists(merged), "merged shared page object missing");

        assertEquals(1, result.tests().size());
        assertEquals(2, result.pageObjects().size());
        assertEquals("tests/successful-login.spec.ts", result.tests().get(0).path());
        assertTrue(result.pageObjects().stream().anyMatch(f -> f.path().equals("pages/LoginPage_successful-login.ts")));
        assertTrue(result.pageObjects().stream().anyMatch(f -> f.path().equals("pages/LoginPage.ts")));
    }

    @Test
    void emptyTestListYieldsAnEmptyResultRatherThanNull() {
        WorkspaceProvider.WriteResult result =
                manager.writeTestsAndReport(projectWith(tempRoot.toString(), null), List.of());

        assertEquals(0, result.tests().size());
        assertEquals(0, result.pageObjects().size());
    }

    @Test
    void playwrightToolIsNotInvokedByWriting() throws IOException {
        Path clientWorkspace = tempRoot.resolve("client-workspace");
        Files.createDirectories(clientWorkspace);
        GeneratedTest test = new GeneratedTest();
        test.setScenarioName("Successful login");
        test.setTestCode("test('x', async () => {});");

        manager.writeTestsAndReport(projectWith(clientWorkspace.toString(), null), List.of(test));

        org.mockito.Mockito.verify(playwrightTool, org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any());
    }
}
