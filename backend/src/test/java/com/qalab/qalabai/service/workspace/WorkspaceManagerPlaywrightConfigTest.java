package com.qalab.qalabai.service.workspace;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The generated Playwright config used to record {@code screenshot: 'on'},
 * {@code video: 'on'} and {@code trace: 'on'} for every test including passing
 * ones. A 20-test run therefore produced 20 videos and 20 traces regardless of
 * outcome — heavy disk, real wall-clock cost, and the failures buried among
 * noise.
 */
class WorkspaceManagerPlaywrightConfigTest {

    @TempDir
    Path workspace;

    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new WorkspaceManager(mock(ProjectRepository.class), mock(GitService.class),
                mock(PlaywrightTool.class));
        ReflectionTestUtils.setField(manager, "screenshotMode", "only-on-failure");
        ReflectionTestUtils.setField(manager, "videoMode", "off");
        ReflectionTestUtils.setField(manager, "traceMode", "retain-on-failure");
    }

    /**
     * Satisfies prepareWorkspace's dependency checks so it never shells out to npm.
     * Without this the test performs a real network install and takes minutes.
     */
    private void markPlaywrightInstalled() throws IOException {
        Files.createDirectories(workspace.resolve("node_modules/@playwright/test"));
        Files.writeString(workspace.resolve("node_modules/@playwright/test/package.json"), "{}");
        Files.writeString(workspace.resolve(".playwright-ready"), "installed");
    }

    private void setModes(String screenshot, String video, String trace) {
        ReflectionTestUtils.setField(manager, "screenshotMode", screenshot);
        ReflectionTestUtils.setField(manager, "videoMode", video);
        ReflectionTestUtils.setField(manager, "traceMode", trace);
    }

    @Test
    void defaultsCaptureEvidenceOnlyForFailures() {
        String config = manager.playwrightConfig();

        assertTrue(config.contains("screenshot: 'only-on-failure'"), config);
        assertTrue(config.contains("video: 'off'"), config);
        assertTrue(config.contains("trace: 'retain-on-failure'"), config);
    }

    @Test
    void noLongerRecordsEvidenceForEveryPassingTest() {
        String config = manager.playwrightConfig();

        // The regression guard for the old profile.
        assertFalse(config.contains("screenshot: 'on'"), "must not screenshot every test:\n" + config);
        assertFalse(config.contains("video: 'on'"), "must not record video for every test:\n" + config);
        assertFalse(config.contains("trace: 'on'"), "must not trace every test:\n" + config);
    }

    @Test
    void theProfileIsConfigurable() {
        setModes("on", "retain-on-failure", "on");

        String config = manager.playwrightConfig();

        assertTrue(config.contains("screenshot: 'on'"), config);
        assertTrue(config.contains("video: 'retain-on-failure'"), config);
        assertTrue(config.contains("trace: 'on'"), config);
    }

    @Test
    void theGeneratedConfigIsStructurallyValidTypescript() {
        String config = manager.playwrightConfig();

        assertTrue(config.contains("import { defineConfig } from '@playwright/test';"), config);
        assertTrue(config.contains("export default defineConfig({"), config);
        assertTrue(config.contains("testDir: './tests',"), config);
        // Every placeholder must have been substituted.
        assertFalse(config.contains("%s"), "unsubstituted placeholder:\n" + config);
        // Braces balanced.
        assertEquals(countOf(config, '{'), countOf(config, '}'), config);
    }

    @Test
    void anExistingUserConfigIsNeverOverwritten() throws IOException {
        markPlaywrightInstalled();
        Path config = workspace.resolve("playwright.config.ts");
        String mine = "// hand written by the user\nexport default { testDir: './e2e' };\n";
        Files.writeString(config, mine);
        Files.writeString(workspace.resolve("package.json"), "{}");

        // prepareWorkspace initialises the structure only when package.json is absent,
        // so with it present the user's config must survive untouched.
        manager.prepareWorkspace(workspace.toString());

        assertEquals(mine, Files.readString(config), "a user-owned config must never be clobbered");
    }

    @Test
    void aMissingConfigIsGeneratedWithTheConfiguredProfile() throws IOException {
        markPlaywrightInstalled();

        manager.prepareWorkspace(workspace.toString());

        Path config = workspace.resolve("playwright.config.ts");
        assertTrue(Files.exists(config), "a config should be generated when absent");
        String written = Files.readString(config);
        assertTrue(written.contains("screenshot: 'only-on-failure'"), written);
        assertTrue(written.contains("video: 'off'"), written);
    }

    @Test
    void theGeneratedConfigAdvertisesThatEditingIsSafe() {
        assertTrue(manager.playwrightConfig().contains("Safe to edit"),
                "the generated file should tell the user it is safe to customise");
    }

    @Test
    void initializationCreatesTheExpectedDirectoryLayout() throws IOException {
        markPlaywrightInstalled();

        manager.prepareWorkspace(workspace.toString());

        assertTrue(Files.isDirectory(workspace.resolve("tests")));
        assertTrue(Files.isDirectory(workspace.resolve("pages")));
        assertTrue(Files.isDirectory(workspace.resolve("fixtures")));
        assertTrue(Files.exists(workspace.resolve("package.json")));
    }

    private static int countOf(String haystack, char needle) {
        int count = 0;
        for (int i = 0; i < haystack.length(); i++) {
            if (haystack.charAt(i) == needle) count++;
        }
        return count;
    }
}
