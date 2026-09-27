package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.dto.project.CreateProjectRequest;
import com.qalab.qalabai.dto.project.ProjectHistoryResponse;
import com.qalab.qalabai.dto.project.ProjectResponse;
import com.qalab.qalabai.service.ProjectContextResolver;
import com.qalab.qalabai.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Project reads, creation and deletion on the v1 surface.
 *
 * <p>This replaces {@code ProjectController} on {@code /api/projects}. The behaviour moved
 * to {@link ProjectService} rather than being copied here, so retiring the legacy URL
 * removed a route rather than a feature — the cascade delete in particular must exist in
 * exactly one place, because a second copy is the one that silently misses a table.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class V1ProjectController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1ProjectController.class);

    private final ProjectService projectService;

    public V1ProjectController(ProjectContextResolver contextResolver,
                               ProjectService projectService) {
        super(contextResolver);
        this.projectService = projectService;
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list() {
        return ResponseEntity.ok(projectService.list());
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@RequestBody CreateProjectRequest request) {
        log.info("POST /api/v1/projects name={}", request.name());
        return ResponseEntity.ok(projectService.create(request));
    }

    @GetMapping("/{databaseId}")
    public ResponseEntity<ProjectResponse> get(@PathVariable Long databaseId) {
        return ResponseEntity.ok(projectService.get(databaseId));
    }

    @DeleteMapping("/{databaseId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long databaseId) {
        log.info("DELETE /api/v1/projects/{}", databaseId);
        projectService.delete(databaseId);
        return ResponseEntity.ok(Map.of("message", "Project deleted"));
    }

    @GetMapping("/{databaseId}/history")
    public ResponseEntity<ProjectHistoryResponse> history(@PathVariable Long databaseId) {
        return ResponseEntity.ok(projectService.history(databaseId));
    }
}
