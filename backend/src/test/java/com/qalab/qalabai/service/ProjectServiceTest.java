package com.qalab.qalabai.service;

import com.qalab.qalabai.dto.project.CreateProjectRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ProjectService.delete} removes nine tables' worth of rows and then recursively
 * deletes the project's workspace directory from disk. It had no coverage at all, which for
 * destructive code that deletes user files is the worst possible place to be.
 *
 * <p>Two properties are worth pinning. It must refuse to delete a project that does not
 * exist rather than reporting success. And a filesystem failure must not fail the delete,
 * because the rows are already gone by then — reporting failure would tell the user their
 * data is intact when it is not.
 */
class ProjectServiceTest {

    private ProjectRepository projectRepository;
    private TestExecutionRepository testExecutionRepository;
    private PageAnalysisHistoryRepository pageAnalysisHistoryRepository;
    private LocatorHistoryRepository locatorHistoryRepository;
    private FailureAnalysisRepository failureAnalysisRepository;
    private FailureHistoryRepository failureHistoryRepository;
    private HealingSuggestionRepository healingSuggestionRepository;
    private TestPlanRepository testPlanRepository;
    private GeneratedTestRepository generatedTestRepository;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        testExecutionRepository = mock(TestExecutionRepository.class);
        pageAnalysisHistoryRepository = mock(PageAnalysisHistoryRepository.class);
        locatorHistoryRepository = mock(LocatorHistoryRepository.class);
        failureAnalysisRepository = mock(FailureAnalysisRepository.class);
        failureHistoryRepository = mock(FailureHistoryRepository.class);
        healingSuggestionRepository = mock(HealingSuggestionRepository.class);
        testPlanRepository = mock(TestPlanRepository.class);
        generatedTestRepository = mock(GeneratedTestRepository.class);

        service = new ProjectService(projectRepository, testExecutionRepository,
                pageAnalysisHistoryRepository, locatorHistoryRepository, failureAnalysisRepository,
                failureHistoryRepository, healingSuggestionRepository, testPlanRepository,
                generatedTestRepository);
    }

    private Project project(Long id, String workspacePath) {
        Project p = new Project();
        p.setId(id);
        p.setName("The Internet Tests");
        p.setBaseUrl("https://the-internet.herokuapp.com");
        p.setFramework("PLAYWRIGHT_TYPESCRIPT");
        p.setWorkspacePath(workspacePath);
        return p;
    }

    @Test
    void createRefusesAProjectWithNoName() {
        assertThatThrownBy(() -> service.create(
                new CreateProjectRequest(null, null, "https://x.test", null, "PLAYWRIGHT")))
                .hasMessageContaining("name is required");
        verify(projectRepository, never()).save(any());
    }

    @Test
    void createRefusesAProjectWithNoFramework() {
        // Framework is what decides the generated test shape; defaulting it would produce
        // tests in a language the project does not use.
        assertThatThrownBy(() -> service.create(
                new CreateProjectRequest("name", null, "https://x.test", null, null)))
                .hasMessageContaining("framework is required");
        verify(projectRepository, never()).save(any());
    }

    @Test
    void createPersistsAndReturnsTheStoredProject() {
        when(projectRepository.save(any())).thenAnswer(i -> {
            Project p = i.getArgument(0);
            p.setId(5L);
            return p;
        });

        var created = service.create(new CreateProjectRequest(
                "name", "a description", "https://x.test", null, "PLAYWRIGHT"));

        assertThat(created.id()).isEqualTo(5L);
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void getThrowsRatherThanReturningNullForAMissingProject() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        // A null return would surface as a NullPointerException somewhere further up,
        // far from the actual cause.
        assertThatThrownBy(() -> service.get(99L))
                .hasMessageContaining("Project not found");
    }

    @Test
    void deleteRemovesEveryDependentTableBeforeTheProject() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L, null)));

        service.delete(1L);

        // Order matters: children first, or a foreign key rejects the parent delete and the
        // user is left with a half-deleted project.
        verify(testExecutionRepository).deleteByProjectId(1L);
        verify(pageAnalysisHistoryRepository).deleteByProjectId(1L);
        verify(locatorHistoryRepository).deleteByProjectId(1L);
        verify(failureAnalysisRepository).deleteByProjectId(1L);
        verify(failureHistoryRepository).deleteByProjectId(1L);
        verify(healingSuggestionRepository).deleteByProjectId(1L);
        verify(testPlanRepository).deleteByProjectId(1L);
        verify(generatedTestRepository).deleteByProjectId(1L);
        verify(projectRepository).delete(any(Project.class));
    }

    @Test
    void deleteRemovesTheWorkspaceDirectoryRecursively(@TempDir Path root) throws Exception {
        Path workspace = root.resolve("workspace");
        Path nested = workspace.resolve("tests/login");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("login.spec.ts"), "test('login', ...)");
        Files.writeString(workspace.resolve("playwright.config.ts"), "export default {}");

        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(project(1L, workspace.toString())));

        service.delete(1L);

        assertThat(Files.exists(workspace))
                .as("generated tests and page objects live in here; leaving them behind "
                        + "means disk grows forever and a recreated project sees stale files")
                .isFalse();
    }

    @Test
    void deleteOfAMissingProjectFailsWithoutTouchingAnything() {
        when(projectRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(404L))
                .hasMessageContaining("Project not found");

        // Deleting "successfully" for a project that was never there would hide a bug in
        // whatever asked for the delete.
        verify(testExecutionRepository, never()).deleteByProjectId(anyLong());
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void aMissingWorkspaceDirectoryIsNotAFailure() {
        when(projectRepository.findById(1L))
                .thenReturn(Optional.of(project(1L, "/nonexistent/workspace/path")));

        service.delete(1L);

        verify(projectRepository).delete(any(Project.class));
    }

    @Test
    void historyOfAMissingProjectIsRejectedRatherThanReturningEmptyLists() {
        when(projectRepository.existsById(7L)).thenReturn(false);

        // Empty lists would render as "this project has no history", which reads as a fact
        // rather than as a lookup that failed.
        assertThatThrownBy(() -> service.history(7L))
                .hasMessageContaining("Project not found");
    }

    @Test
    void listReturnsEveryProject() {
        when(projectRepository.findAll()).thenReturn(java.util.List.of(
                project(1L, null), project(2L, null)));

        assertThat(service.list()).hasSize(2);
    }
}
