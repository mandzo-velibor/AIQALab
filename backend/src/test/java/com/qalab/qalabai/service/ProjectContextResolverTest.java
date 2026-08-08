package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.api.v1.dto.ProjectInfo;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectContextResolverTest {

    private final ProjectRepository repository = mock(ProjectRepository.class);
    private final ProjectContextResolver resolver = new ProjectContextResolver(repository);

    @Test
    void resolveBuildsContextFromRequestOnlyWhenNoDatabaseId() {
        ProjectInfo info = ProjectInfo.of("the-internet-tests", "https://the-internet.herokuapp.com/login", "PLAYWRIGHT", "TYPESCRIPT");
        ProjectContext ctx = resolver.resolve(info);

        assertEquals("the-internet-tests", ctx.getProjectId());
        assertEquals("https://the-internet.herokuapp.com/login", ctx.getBaseUrl());
        assertEquals("PLAYWRIGHT", ctx.getFramework());
        assertEquals("TYPESCRIPT", ctx.getLanguage());
        assertNull(ctx.getWorkspacePath());
    }

    @Test
    void resolveRejectsMissingProject() {
        ApiException e = assertThrows(ApiException.class, () -> resolver.resolve(null));
        assertEquals(ErrorCode.INVALID_PROJECT_CONTEXT, e.getCode());
    }

    @Test
    void resolveRejectsBlankProjectId() {
        ApiException e = assertThrows(ApiException.class,
                () -> resolver.resolve(ProjectInfo.of("  ", null, null, null)));
        assertEquals(ErrorCode.INVALID_PROJECT_CONTEXT, e.getCode());
    }

    @Test
    void resolveThrowsProjectNotFoundWhenDatabaseIdMissing() {
        ProjectInfo info = ProjectInfo.of("p", "https://example.com", null, null)
                .withDatabaseId(99L);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> resolver.resolve(info));
        assertEquals(ErrorCode.PROJECT_NOT_FOUND, e.getCode());
    }

    @Test
    void resolveEnrichesFromDatabaseWhenProvided() {
        Project project = new Project();
        project.setId(5L);
        project.setName("The Internet Tests");
        project.setBaseUrl("https://the-internet.herokuapp.com");
        project.setFramework("PLAYWRIGHT_TYPESCRIPT");
        project.setWorkspacePath("/home/dev/internet-tests");
        when(repository.findById(5L)).thenReturn(Optional.of(project));

        ProjectInfo info = ProjectInfo.of("external-id", null, null, null).withDatabaseId(5L);
        ProjectContext ctx = resolver.resolve(info);

        assertEquals("external-id", ctx.getProjectId());
        assertEquals("The Internet Tests", ctx.getProjectName());
        assertEquals("https://the-internet.herokuapp.com", ctx.getBaseUrl());
        assertEquals("PLAYWRIGHT_TYPESCRIPT", ctx.getFramework());
        assertEquals("/home/dev/internet-tests", ctx.getWorkspacePath());
    }

    @Test
    void databaseIdReturnsNullWhenNotRegistered() {
        assertNull(resolver.databaseId(ProjectInfo.of("p", null, null, null)));
    }
}
