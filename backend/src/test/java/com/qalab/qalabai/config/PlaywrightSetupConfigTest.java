package com.qalab.qalabai.config;

import com.qalab.qalabai.service.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Playwright warm-up used to be a {@code CommandLineRunner}, which blocked
 * application startup for up to 10xN minutes (npm install + browser install per
 * workspace, 600 s each) and let a single hung npm consume the whole budget.
 */
class PlaywrightSetupConfigTest {

    @TempDir
    Path root;

    private void makeWorkspace(String name) throws IOException {
        Files.createDirectories(root.resolve(name));
    }

    @Test
    void preparesEveryProjectWorkspace() throws IOException {
        makeWorkspace("project-1");
        makeWorkspace("project-2");
        Files.createDirectories(root.resolve("not-a-project"));
        Files.writeString(root.resolve("loose-file.txt"), "x");

        WorkspaceManager manager = mock(WorkspaceManager.class);

        int prepared = PlaywrightSetupConfig.warmUpWorkspaces(root.toString(), manager);

        assertEquals(2, prepared, "only project-* directories are workspaces");
        verify(manager).prepareWorkspace(root.resolve("project-1").toString());
        verify(manager).prepareWorkspace(root.resolve("project-2").toString());
        verify(manager, never()).prepareWorkspace(root.resolve("not-a-project").toString());
    }

    @Test
    void oneFailingWorkspaceDoesNotStopTheOthers() throws IOException {
        makeWorkspace("project-1");
        makeWorkspace("project-2");
        makeWorkspace("project-3");

        WorkspaceManager manager = mock(WorkspaceManager.class);
        doThrow(new RuntimeException("npm install killed"))
                .when(manager).prepareWorkspace(root.resolve("project-2").toString());

        int prepared = PlaywrightSetupConfig.warmUpWorkspaces(root.toString(), manager);

        // The regression guard: previously an exception here propagated out of the
        // CommandLineRunner and aborted boot.
        assertEquals(2, prepared, "a failing workspace must not block its siblings");
        verify(manager).prepareWorkspace(root.resolve("project-3").toString());
    }

    @Test
    void missingWorkspacesDirectoryIsANoOp() {
        WorkspaceManager manager = mock(WorkspaceManager.class);

        assertEquals(0, PlaywrightSetupConfig.warmUpWorkspaces(root.resolve("absent").toString(), manager));
        verify(manager, never()).prepareWorkspace(anyString());
    }

    @Test
    void emptyWorkspacesDirectoryIsANoOp() {
        WorkspaceManager manager = mock(WorkspaceManager.class);

        assertEquals(0, PlaywrightSetupConfig.warmUpWorkspaces(root.toString(), manager));
        verify(manager, never()).prepareWorkspace(anyString());
    }

    @Test
    void warmupIsDisabledByConfiguration() {
        WorkspaceManager manager = mock(WorkspaceManager.class);

        PlaywrightSetupConfig disabled =
                new PlaywrightSetupConfig(manager, false, root.toString());
        // Firing the lifecycle hook must not touch any workspace.
        disabled.onApplicationReady();

        assertEquals(false, disabled.isWarmupEnabled());
        verify(manager, never()).prepareWorkspace(anyString());
    }

    @Test
    void lifecycleHookReturnsImmediatelyRatherThanBlocking() throws IOException {
        makeWorkspace("project-1");
        WorkspaceManager manager = mock(WorkspaceManager.class);
        // Simulate a slow install.
        doThrow(new RuntimeException("boom")).when(manager).prepareWorkspace(anyString());

        PlaywrightSetupConfig config = new PlaywrightSetupConfig(manager, true, root.toString());

        long start = System.currentTimeMillis();
        config.onApplicationReady();
        long elapsed = System.currentTimeMillis() - start;

        // The hook must hand off to the background executor and return; the actual
        // (slow) work happens off the startup path.
        assertTrue(elapsed < 1_000, "onApplicationReady must not block, took " + elapsed + "ms");
    }
}
