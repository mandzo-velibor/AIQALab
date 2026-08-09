package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.service.git.GitService;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.playwright.PlaywrightTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WorkspaceManager implements WorkspaceProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final ProjectRepository projectRepository;
    private final GitService gitService;
    private final PlaywrightTool playwrightTool;

    @Value("${qalab.workspaces-dir:./workspaces}")
    private String workspacesDir;

    public WorkspaceManager(ProjectRepository projectRepository,
                            GitService gitService,
                            PlaywrightTool playwrightTool) {
        this.projectRepository = projectRepository;
        this.gitService = gitService;
        this.playwrightTool = playwrightTool;
    }

    @Override
    public String getWorkspace(ProjectContext project) {
        if (project.getWorkspacePath() != null && !project.getWorkspacePath().isBlank()) {
            return project.getWorkspacePath();
        }
        if (project.getDatabaseId() != null) {
            Path legacy = Paths.get(workspacesDir, "project-" + project.getDatabaseId());
            return legacy.toAbsolutePath().normalize().toString();
        }
        throw new RuntimeException("No workspace path available for project: "
                + project.getProjectId() + ". Provide a workspacePath to execute tests.");
    }

    @Override
    public void prepareWorkspace(ProjectContext project) {
        String workspace = getWorkspace(project);
        prepareWorkspace(workspace);
    }

    @Override
    public String writeTests(ProjectContext project, List<GeneratedTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        String workspace = getWorkspace(project);
        Path root = Paths.get(workspace);
        Path testsDir = root.resolve("tests");
        Path pagesDir = root.resolve("pages");

        try {
            Files.createDirectories(testsDir);
            Files.createDirectories(pagesDir);

            for (GeneratedTest test : tests) {
                String fileName = resolveFileName(test);
                if (test.getTestCode() != null && !test.getTestCode().isBlank()) {
                    Files.writeString(testsDir.resolve(fileName), normalizeCode(test.getTestCode()));
                }
                writePageObject(pagesDir, normalizeCode(test.getPageObjectCode()));
            }

            log.info("Wrote {} test files to {}", tests.size(), testsDir);
            return workspace;
        } catch (Exception e) {
            log.error("Failed to write test files to workspace: {}", e.getMessage());
            throw new RuntimeException("Failed to write test files to workspace: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll) {
        return execute(project, testFile, runAll, null);
    }

    @Override
    public Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll, String testType) {
        String workspace = getWorkspace(project);
        ToolContext context = new ToolContext().put("workspacePath", workspace);

        String marker = testTypeMarker(testType);
        if (marker != null && runAll) {
            List<String> matching = findSpecFilesByType(workspace, marker);
            if (matching.isEmpty()) {
                return Map.of(
                        "status", "PASSED",
                        "duration", 0L,
                        "output", "No tests of type '" + marker + "' found in workspace " + workspace
                );
            }
            context.put("testFiles", matching);
        } else {
            if (testFile != null && !testFile.isBlank()) {
                context.put("testFile", testFile);
            }
            context.put("runAll", runAll);
        }
        return toMap(playwrightTool.execute(context));
    }

    /**
     * Deterministic, LLM-free selection of the spec files whose name carries the
     * given type marker (e.g. {@code .ui.}, {@code .e2e.}, {@code .api.}). Paths
     * are returned relative to the workspace so Playwright resolves them from its
     * working directory.
     */
    private List<String> findSpecFilesByType(String workspace, String marker) {
        Path testsDir = Paths.get(workspace, "tests");
        List<String> files = new ArrayList<>();
        if (!Files.exists(testsDir)) {
            return files;
        }
        try (var stream = Files.walk(testsDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches(".*\\." + Pattern.quote(marker) + "\\.spec\\.[tj]s$"))
                    .map(p -> {
                        Path relative = testsDir.getParent().relativize(p);
                        return relative.toString().replace(File.separatorChar, '/');
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list spec files by type '{}' in {}: {}", marker, workspace, e.getMessage());
        }
        return files;
    }

    /** Maps a user-provided test type (ALL/UI/E2E/API) to the deterministic file marker. */
    public static String testTypeMarker(String testType) {
        if (testType == null) {
            return null;
        }
        String lower = testType.trim().toLowerCase();
        if (lower.contains("api")) {
            return "api";
        }
        if (lower.contains("e2e")) {
            return "e2e";
        }
        if (lower.contains("ui")) {
            return "ui";
        }
        return null;
    }

    @Override
    public Map<String, String> collectArtifacts(ProjectContext project) {
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ERROR");
        result.put("error", "Unexpected result from PlaywrightTool");
        return result;
    }

    private void writePageObject(Path pagesDir, String pageObjectCode) throws IOException {
        if (pageObjectCode == null || pageObjectCode.isBlank()) {
            return;
        }
        String className = extractClassName(pageObjectCode);
        if (className != null) {
            Files.writeString(pagesDir.resolve(className + ".ts"), pageObjectCode);
        }
    }

    /**
     * Decodes double-encoded line breaks (literal "\n" backslash sequences in a
     * single-line string) back into real newlines so the written file is valid
     * source code. No-op when the code already contains real line breaks.
     */
    private String normalizeCode(String code) {
        if (code == null || code.isEmpty() || code.indexOf('\\') < 0) {
            return code;
        }
        int literalNewlines = countOccurrences(code, "\\n");
        int realNewlines = countOccurrences(code, "\n");
        if (literalNewlines > 0 && realNewlines == 0) {
            return code
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t");
        }
        return code;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String extractClassName(String code) {
        Pattern pattern = Pattern.compile("export\\s+class\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String resolveFileName(GeneratedTest test) {
        String base;
        if (test.getScenarioName() != null && !test.getScenarioName().isBlank()) {
            base = slug(test.getScenarioName());
        } else if (test.getTestFileName() != null && !test.getTestFileName().isBlank()) {
            base = test.getTestFileName().replaceFirst("\\.spec\\.[tj]s$", "").replaceFirst("\\.[a-z]+$", "");
        } else {
            base = "test";
        }
        String marker = testTypeMarker(test.getTestType());
        return marker != null ? base + "." + marker + ".spec.ts" : base + ".spec.ts";
    }

    private static String slug(String raw) {
        String value = raw.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return value.isBlank() ? "test" : value;
    }

    public ProjectContext getProjectContext(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));

        String workspacePath = project.getWorkspacePath();
        if (workspacePath == null || workspacePath.isBlank()) {
            workspacePath = initializeWorkspace(project);
            project.setWorkspacePath(workspacePath);
            projectRepository.save(project);
        }

        Path normalized = Paths.get(workspacePath);
        if (!normalized.isAbsolute()) {
            normalized = normalized.toAbsolutePath().normalize();
            project.setWorkspacePath(normalized.toString());
            projectRepository.save(project);
        }

        prepareWorkspace(normalized.toString());

        return new ProjectContext(
                project.getId(),
                normalized.toString(),
                project.getBaseUrl(),
                project.getFramework()
        );
    }

    private String initializeWorkspace(Project project) {
        try {
            Path workspacesPath = Paths.get(workspacesDir);
            Files.createDirectories(workspacesPath);

            String projectDirName = "project-" + project.getId();
            Path projectPath = workspacesPath.resolve(projectDirName);

            if (Files.exists(projectPath)) {
                log.info("Workspace already exists: {}", projectPath);
                return projectPath.toString();
            }

            log.info("Initializing workspace for project {}: {}", project.getName(), projectPath);

            if (project.getRepositoryUrl() != null && !project.getRepositoryUrl().isBlank()) {
                gitService.cloneRepository(project.getRepositoryUrl(), projectPath.toString());
            } else {
                Files.createDirectories(projectPath);
                initializeProjectStructure(projectPath);
            }

            return projectPath.toString();

        } catch (Exception e) {
            log.error("Failed to initialize workspace: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize workspace: " + e.getMessage(), e);
        }
    }

    private void initializeProjectStructure(Path projectPath) throws IOException {
        Files.createDirectories(projectPath.resolve("tests"));
        Files.createDirectories(projectPath.resolve("pages"));
        Files.createDirectories(projectPath.resolve("fixtures"));

        String packageJson = """
                {
                  "name": "qa-project",
                  "version": "1.0.0",
                  "scripts": {
                    "test": "playwright test"
                  },
                  "devDependencies": {
                    "@playwright/test": "1.48.0"
                  }
                }
                """;
        Files.writeString(projectPath.resolve("package.json"), packageJson);

        String playwrightConfig = """
                import { defineConfig } from '@playwright/test';
                
                export default defineConfig({
                  testDir: './tests',
                  timeout: 30000,
                  use: {
                    headless: true,
                    screenshot: 'on',
                    video: 'on',
                    trace: 'on',
                  },
                });
                """;
        Files.writeString(projectPath.resolve("playwright.config.ts"), playwrightConfig);

        log.info("Initialized project structure at: {}", projectPath);
    }

    public void validateWorkspace(String workspacePath) {
        Path path = Paths.get(workspacePath);
        if (!Files.exists(path)) {
            throw new RuntimeException("Workspace does not exist: " + workspacePath);
        }
        if (!Files.isDirectory(path)) {
            throw new RuntimeException("Workspace is not a directory: " + workspacePath);
        }
    }

    public void prepareWorkspace(String workspacePath) {
        Path path = Paths.get(workspacePath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                log.warn("Failed to create workspace directory {}: {}", workspacePath, e.getMessage());
            }
        }

        try {
            if (!Files.exists(path.resolve("package.json"))) {
                log.info("No package.json in workspace {}, initializing project structure.", workspacePath);
                initializeProjectStructure(path);
            }

            if (!Files.exists(path.resolve("node_modules"))) {
                log.info("Installing workspace dependencies in {}...", workspacePath);
                runProcess(path.toFile(), new String[]{"npm", "install"}, "npm install");
            }

            Path browsersMarker = path.resolve(".playwright-ready");
            if (!Files.exists(browsersMarker)) {
                log.info("Installing Playwright browsers for workspace {}...", workspacePath);
                boolean ok = runProcess(path.toFile(),
                        new String[]{"npx", "playwright", "install", "chromium"},
                        "npx playwright install chromium");
                if (ok) {
                    Files.writeString(browsersMarker, "installed");
                }
            }
        } catch (Exception e) {
            log.warn("Workspace dependency setup failed for {}: {}", workspacePath, e.getMessage());
        }
    }

    private boolean runProcess(File dir, String[] command, String what) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("{} timed out.", what);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("{} failed with exit code {}. Output tail: {}", what, exitCode,
                        output.length() > 800 ? output.substring(output.length() - 800) : output);
                return false;
            }
            log.info("{} completed.", what);
            return true;
        } catch (Exception e) {
            log.warn("{} error: {}", what, e.getMessage());
            return false;
        }
    }
}
