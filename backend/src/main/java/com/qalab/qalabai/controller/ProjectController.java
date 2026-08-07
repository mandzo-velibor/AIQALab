package com.qalab.qalabai.controller;

import com.qalab.qalabai.dto.project.CreateProjectRequest;
import com.qalab.qalabai.dto.project.ProjectHistoryResponse;
import com.qalab.qalabai.dto.project.ProjectResponse;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectRepository projectRepository;
    private final TestExecutionRepository testExecutionRepository;
    private final PageAnalysisHistoryRepository pageAnalysisHistoryRepository;
    private final LocatorHistoryRepository locatorHistoryRepository;
    private final FailureAnalysisRepository failureAnalysisRepository;
    private final FailureHistoryRepository failureHistoryRepository;
    private final HealingSuggestionRepository healingSuggestionRepository;
    private final TestPlanRepository testPlanRepository;
    private final GeneratedTestRepository generatedTestRepository;

    public ProjectController(ProjectRepository projectRepository,
                             TestExecutionRepository testExecutionRepository,
                             PageAnalysisHistoryRepository pageAnalysisHistoryRepository,
                             LocatorHistoryRepository locatorHistoryRepository,
                             FailureAnalysisRepository failureAnalysisRepository,
                             FailureHistoryRepository failureHistoryRepository,
                             HealingSuggestionRepository healingSuggestionRepository,
                             TestPlanRepository testPlanRepository,
                             GeneratedTestRepository generatedTestRepository) {
        this.projectRepository = projectRepository;
        this.testExecutionRepository = testExecutionRepository;
        this.pageAnalysisHistoryRepository = pageAnalysisHistoryRepository;
        this.locatorHistoryRepository = locatorHistoryRepository;
        this.failureAnalysisRepository = failureAnalysisRepository;
        this.failureHistoryRepository = failureHistoryRepository;
        this.healingSuggestionRepository = healingSuggestionRepository;
        this.testPlanRepository = testPlanRepository;
        this.generatedTestRepository = generatedTestRepository;
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

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProject(@PathVariable Long id) {
        log.info("DELETE /api/projects/{}", id);

        Project project = projectRepository.findById(id).orElse(null);
        if (project == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            testExecutionRepository.deleteByProjectId(id);
            pageAnalysisHistoryRepository.deleteByProjectId(id);
            locatorHistoryRepository.deleteByProjectId(id);
            failureAnalysisRepository.deleteByProjectId(id);
            failureHistoryRepository.deleteByProjectId(id);
            healingSuggestionRepository.deleteByProjectId(id);
            testPlanRepository.deleteByProjectId(id);
            generatedTestRepository.deleteByProjectId(id);

            if (project.getWorkspacePath() != null && !project.getWorkspacePath().isBlank()) {
                try {
                    Path workspacePath = Paths.get(project.getWorkspacePath());
                    if (Files.exists(workspacePath)) {
                        Files.walk(workspacePath)
                                .sorted(Comparator.reverseOrder())
                                .forEach(path -> {
                                    try {
                                        Files.delete(path);
                                    } catch (IOException e) {
                                        log.warn("Failed to delete workspace file {}: {}", path, e.getMessage());
                                    }
                                });
                        log.info("Deleted workspace for project {}: {}", id, workspacePath);
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete workspace for project {}: {}", id, e.getMessage());
                }
            }

            projectRepository.delete(project);
            return ResponseEntity.ok(Map.of("message", "Project deleted"));
        } catch (Exception e) {
            log.error("Failed to delete project {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to delete project",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<?> getProjectHistory(@PathVariable Long id) {
        log.info("GET /api/projects/{}/history", id);

        if (!projectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        var executions = testExecutionRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(e -> new ProjectHistoryResponse.ExecutionEntry(
                        e.getId(),
                        e.getTestFile(),
                        e.getStatus(),
                        e.getDuration(),
                        e.getErrorMessage(),
                        e.getScreenshotPath(),
                        e.getConsoleLogs(),
                        e.getCreatedAt()
                ))
                .toList();

        var pageAnalyses = pageAnalysisHistoryRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(p -> new ProjectHistoryResponse.PageAnalysisEntry(
                        p.getId(),
                        p.getUrl(),
                        p.getPageType(),
                        p.getVersion(),
                        p.getCreatedAt()
                ))
                .toList();

        var locatorHistory = locatorHistoryRepository.findByProjectId(id).stream()
                .map(l -> new ProjectHistoryResponse.LocatorHistoryEntry(
                        l.getId(),
                        l.getElementName(),
                        l.getLocator(),
                        l.getStrategy(),
                        l.getStatus(),
                        l.getCreatedAt()
                ))
                .toList();

        var failureAnalyses = failureAnalysisRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(f -> new ProjectHistoryResponse.FailureAnalysisEntry(
                        f.getId(),
                        f.getExecutionId(),
                        f.getFailureType(),
                        f.getSummary(),
                        f.getAffectedElement(),
                        f.getHealingCandidate(),
                        f.getCreatedAt()
                ))
                .toList();

        var healingSuggestions = healingSuggestionRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(h -> new ProjectHistoryResponse.HealingSuggestionEntry(
                        h.getId(),
                        h.getElementName(),
                        h.getOldLocator(),
                        h.getNewLocator(),
                        h.getStatus(),
                        h.getConfidence(),
                        h.getCreatedAt()
                ))
                .toList();

        ProjectHistoryResponse response = new ProjectHistoryResponse(
                executions,
                pageAnalyses,
                locatorHistory,
                failureAnalyses,
                healingSuggestions
        );

        return ResponseEntity.ok(response);
    }
}
