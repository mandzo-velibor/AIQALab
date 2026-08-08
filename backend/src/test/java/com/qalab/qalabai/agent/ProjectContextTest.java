package com.qalab.qalabai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectContextTest {

    @Test
    void legacyConstructorPreservesNumericId() {
        ProjectContext ctx = new ProjectContext(42L, "/tmp/ws", "https://example.com", "PLAYWRIGHT");
        assertEquals("42", ctx.getProjectId());
        assertEquals(42L, ctx.getDatabaseId());
    }

    @Test
    void numericStringProjectIdIsParsableAsDatabaseId() {
        ProjectContext ctx = new ProjectContext();
        ctx.setProjectId("7");
        assertEquals(7L, ctx.getDatabaseId());
    }

    @Test
    void nonNumericProjectIdCannotBeUsedAsDatabaseId() {
        ProjectContext ctx = new ProjectContext();
        ctx.setProjectId("my-internet-tests");
        assertNull(ctx.getDatabaseId());
    }

    @Test
    void missingProjectIdYieldsNullDatabaseId() {
        ProjectContext ctx = new ProjectContext();
        assertNull(ctx.getDatabaseId());
        assertThrows(IllegalArgumentException.class, ctx::requireProjectId);
    }

    @Test
    void blankProjectIdRejectedByRequireProjectId() {
        ProjectContext ctx = new ProjectContext();
        ctx.setProjectId("   ");
        assertThrows(IllegalArgumentException.class, ctx::requireProjectId);
    }

    @Test
    void blankBaseUrlRejectedByRequireBaseUrl() {
        ProjectContext ctx = new ProjectContext();
        ctx.setProjectId("p1");
        assertThrows(IllegalArgumentException.class, ctx::requireBaseUrl);
    }
}
