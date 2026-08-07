package com.qalab.qalabai.controller;

import com.qalab.qalabai.dto.project.CreateProjectRequest;
import com.qalab.qalabai.dto.project.ProjectResponse;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectRepository projectRepository;

    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @PostMapping
    public ResponseEntity<?> createProject(@RequestBody CreateProjectRequest request) {
        log.info("POST /api/projects - Creating project: {}", request.name());

        try {
            if (request.name() == null || request.name().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
            }
            if (request.baseUrl() == null || request.baseUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Base URL is required"));
            }
            if (request.framework() == null || request.framework().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Framework is required"));
            }

            Project project = new Project();
            project.setName(request.name());
            project.setDescription(request.description());
            project.setBaseUrl(request.baseUrl());
            project.setRepositoryUrl(request.repositoryUrl() != null ? request.repositoryUrl() : "");
            project.setFramework(request.framework());

            Project saved = projectRepository.save(project);

            ProjectResponse response = new ProjectResponse(
                    saved.getId(),
                    saved.getName(),
                    saved.getDescription(),
                    saved.getBaseUrl(),
                    saved.getRepositoryUrl(),
                    saved.getFramework(),
                    saved.getWorkspacePath(),
                    saved.getCreatedAt(),
                    saved.getUpdatedAt()
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to create project: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to create project",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> getAllProjects() {
        log.info("GET /api/projects");

        List<ProjectResponse> projects = projectRepository.findAll().stream()
                .map(p -> new ProjectResponse(
                        p.getId(),
                        p.getName(),
                        p.getDescription(),
                        p.getBaseUrl(),
                        p.getRepositoryUrl(),
                        p.getFramework(),
                        p.getWorkspacePath(),
                        p.getCreatedAt(),
                        p.getUpdatedAt()
                ))
                .toList();

        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProject(@PathVariable Long id) {
        log.info("GET /api/projects/{}", id);

        return projectRepository.findById(id)
                .map(p -> {
                    ProjectResponse response = new ProjectResponse(
                            p.getId(),
                            p.getName(),
                            p.getDescription(),
                            p.getBaseUrl(),
                            p.getRepositoryUrl(),
                            p.getFramework(),
                            p.getWorkspacePath(),
                            p.getCreatedAt(),
                            p.getUpdatedAt()
                    );
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
