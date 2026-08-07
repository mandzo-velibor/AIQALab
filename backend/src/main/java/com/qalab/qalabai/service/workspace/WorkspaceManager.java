package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.service.git.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

        return new ProjectContext(
                project.getId(),
                workspacePath,
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
                    "@playwright/test": "^1.40.0"
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
}
