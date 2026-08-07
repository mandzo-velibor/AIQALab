package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.service.git.GitService;
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
import java.util.concurrent.TimeUnit;

@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final ProjectRepository projectRepository;
    private final GitService gitService;

    @Value("${qalab.workspaces-dir:./workspaces}")
    private String workspacesDir;

    public WorkspaceManager(ProjectRepository projectRepository, GitService gitService) {
        this.projectRepository = projectRepository;
        this.gitService = gitService;
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
