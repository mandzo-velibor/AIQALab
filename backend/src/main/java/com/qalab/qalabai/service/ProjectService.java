package com.qalab.qalabai.service;

import com.qalab.qalabai.dto.project.CreateProjectRequest;
import com.qalab.qalabai.dto.project.ProjectHistoryResponse;
import com.qalab.qalabai.dto.project.ProjectResponse;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.FailureAnalysisRepository;
import com.qalab.qalabai.repository.FailureHistoryRepository;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
import com.qalab.qalabai.repository.PageAnalysisHistoryRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.repository.TestPlanRepository;
import com.qalab.qalabai.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

/**
 * Project reads, creation and cascading delete.
 *
 * <p>Extracted from {@code ProjectController} during B-033 so that retiring the legacy
 * {@code /api/*} surface deletes a URL, not the behaviour behind it. The cascade delete in
 * particular is worth having in one place: it is the kind of logic that must not exist
 * twice, because a second copy is the one that misses a table.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final TestExecutionRepository testExecutionRepository;
    private final PageAnalysisHistoryRepository pageAnalysisHistoryRepository;
    private final LocatorHistoryRepository locatorHistoryRepository;
    private final FailureAnalysisRepository failureAnalysisRepository;
    private final FailureHistoryRepository failureHistoryRepository;
    private final HealingSuggestionRepository healingSuggestionRepository;
    private final TestPlanRepository testPlanRepository;
    private final GeneratedTestRepository generatedTestRepository;

    public ProjectService(ProjectRepository projectRepository,
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

    public List<ProjectResponse> list() {
        return projectRepository.findAll().stream().map(this::toResponse).toList();
    }

    public ProjectResponse get(Long id) {
        return projectRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.projectNotFound("Project not found: " + id));
    }

    public ProjectResponse create(CreateProjectRequest request) {
        require(request.name(), "name is required");
        require(request.baseUrl(), "baseUrl is required");
        require(request.framework(), "framework is required");

        Project project = new Project();
        project.setName(request.name());
        project.setDescription(request.description());
        project.setBaseUrl(request.baseUrl());
        project.setRepositoryUrl(request.repositoryUrl() != null ? request.repositoryUrl() : "");
        project.setFramework(request.framework());
        return toResponse(projectRepository.save(project));
    }

    /**
     * Deletes the project and everything hanging off it, then its workspace directory.
     *
     * <p>The workspace is deleted only after the database rows succeed, so a failed cascade
     * leaves the files in place to retry against rather than leaving rows that reference a
     * workspace which no longer exists.
     */
    @Transactional
    public void delete(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> ApiException.projectNotFound("Project not found: " + id));

        testExecutionRepository.deleteByProjectId(id);
        pageAnalysisHistoryRepository.deleteByProjectId(id);
        locatorHistoryRepository.deleteByProjectId(id);
        failureAnalysisRepository.deleteByProjectId(id);
        failureHistoryRepository.deleteByProjectId(id);
        healingSuggestionRepository.deleteByProjectId(id);
        testPlanRepository.deleteByProjectId(id);
        generatedTestRepository.deleteByProjectId(id);

        projectRepository.delete(project);
        deleteWorkspace(project.getWorkspacePath(), id);
    }

    private void deleteWorkspace(String workspacePath, Long projectId) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return;
        }
        try {
            Path root = Paths.get(workspacePath);
            if (!Files.exists(root)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        log.warn("Failed to delete workspace file {}: {}", path, e.getMessage());
                    }
                });
            }
            log.info("Deleted workspace for project {}: {}", projectId, root);
        } catch (IOException e) {
            // The rows are already gone. Failing the request here would report a delete as
            // failed when the data is in fact deleted, and the leftover directory is
            // recoverable — a failed response would not be.
            log.warn("Failed to delete workspace for project {}: {}", projectId, e.getMessage());
        }
    }

    public ProjectHistoryResponse history(Long id) {
        if (!projectRepository.existsById(id)) {
            throw ApiException.projectNotFound("Project not found: " + id);
        }

        var executions = testExecutionRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(e -> new ProjectHistoryResponse.ExecutionEntry(
                        e.getId(), e.getTestFile(), e.getStatus(), e.getDuration(),
                        e.getErrorMessage(), e.getScreenshotPath(), e.getConsoleLogs(),
                        e.getCreatedAt()))
                .toList();

        var pageAnalyses = pageAnalysisHistoryRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(p -> new ProjectHistoryResponse.PageAnalysisEntry(
                        p.getId(), p.getUrl(), p.getPageType(), p.getVersion(), p.getCreatedAt()))
                .toList();

        var locatorHistory = locatorHistoryRepository.findByProjectId(id).stream()
                .map(l -> new ProjectHistoryResponse.LocatorHistoryEntry(
                        l.getId(), l.getElementName(), l.getLocator(), l.getStrategy(),
                        l.getStatus(), l.getCreatedAt()))
                .toList();

        var failureAnalyses = failureAnalysisRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                .map(f -> new ProjectHistoryResponse.FailureAnalysisEntry(
                        f.getId(), f.getExecutionId(), f.getFailureType(), f.getSummary(),
                        f.getAffectedElement(), f.getHealingCandidate(), f.getCreatedAt()))
                .toList();

        var healingSuggestions =
                healingSuggestionRepository.findByProjectIdOrderByCreatedAtDesc(id).stream()
                        .map(h -> new ProjectHistoryResponse.HealingSuggestionEntry(
                                h.getId(), h.getElementName(), h.getOldLocator(),
                                h.getNewLocator(), h.getStatus(), h.getConfidence(),
                                h.getCreatedAt()))
                        .toList();

        return new ProjectHistoryResponse(
                executions, pageAnalyses, locatorHistory, failureAnalyses, healingSuggestions);
    }

    private ProjectResponse toResponse(Project p) {
        return new ProjectResponse(
                p.getId(), p.getName(), p.getDescription(), p.getBaseUrl(),
                p.getRepositoryUrl(), p.getFramework(), p.getWorkspacePath(),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidRequest(message);
        }
    }
}
