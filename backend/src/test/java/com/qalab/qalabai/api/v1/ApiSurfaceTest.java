package com.qalab.qalabai.api.v1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of B-033 was one API surface, and that claim is only worth something if
 * something checks it. A single stray {@code @RequestMapping("/api/...")} would
 * otherwise reopen the exact problem this task closed — two contracts to keep in sync,
 * and twice the surface for the security review in B-013 to cover.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSurfaceTest {

    // Qualified: the Actuator registers a second handler mapping of the same type, and an
    // unqualified injection fails with "found 2" the moment Actuator is on the classpath.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noEndpointIsMappedOutsideTheV1Namespace() {
        Set<String> legacy = new TreeSet<>();
        mappedPaths().stream()
                .filter(p -> p.startsWith("/api/") && !p.startsWith("/api/v1"))
                .forEach(legacy::add);

        assertThat(legacy)
                .as("every /api mapping must live under /api/v1; found legacy mappings")
                .isEmpty();
    }

    @Test
    void thePreviouslyLegacyEndpointsNowExistOnV1() {
        // Each of these was on the legacy surface and had no v1 equivalent. If one is
        // renamed or dropped, the dashboard breaks at runtime rather than at build time,
        // so the names are pinned here.
        assertThat(mappedPaths()).contains(
                "/api/v1/projects",
                "/api/v1/projects/{databaseId}",
                "/api/v1/projects/{databaseId}/history",
                "/api/v1/executions",
                "/api/v1/executions/{executionId}/results",
                "/api/v1/test-plans",
                "/api/v1/tests",
                "/api/v1/locators",
                "/api/v1/healing/suggestions",
                "/api/v1/healing/suggestions/{suggestionId}/approve",
                "/api/v1/healing/suggestions/{suggestionId}/reject",
                "/api/v1/healing/suggestions/{suggestionId}/apply",
                "/api/v1/healing/suggestions/analyze/{executionId}"
        );
    }

    @Test
    void bothHealingModelsRemainReachableOnOneSurface() {
        // The proposal track and the suggestion track are different models and cannot be
        // merged without a schema migration. Both must stay reachable under /api/v1, or
        // the dashboard loses the apply button.
        Set<String> paths = mappedPaths();
        assertThat(paths)
                .as("proposal review track")
                .contains("/api/v1/healing/propose", "/api/v1/healing/{proposalId}/accept");
        assertThat(paths)
                .as("suggestion apply track")
                .contains("/api/v1/healing/suggestions/{suggestionId}/apply");
    }

    @Test
    void theRunRequestStillCarriesTestType() {
        // v1 used to hardcode a null testType, which silently disabled the dashboard's
        // e2e/ui/api selector with no error anywhere. Constructing the record with a
        // testType fails to compile if the component is ever removed again.
        var request = new com.qalab.qalabai.api.v1.dto.V1RunRequest(
                null, 42L, false, null, null, null, "e2e");

        assertThat(request.testType())
                .as("the test type must reach the service, not be hardcoded to null")
                .isEqualTo("e2e");
    }

    @Test
    void theLocatorRequestStillCarriesTheInstruction() {
        // The v1 locator request record omitted instruction, so migrating the dashboard
        // would have stopped sending the user's guidance with no error anywhere.
        var request = new com.qalab.qalabai.api.v1.dto.V1LocatorsRequest(
                null, "https://example.test", "focus the login form");
        assertThat(request.instruction())
                .as("the instruction must survive the v1 migration")
                .isEqualTo("focus the login form");
    }

    @Test
    void exploreStillReturnsPerAgentResults() {
        // explore-result.tsx renders a panel from agentResults. v1 originally dropped it,
        // which would have deleted a visible feature during the migration.
        var response = new com.qalab.qalabai.api.v1.dto.V1ExploreResponse(
                "op-1", com.qalab.qalabai.api.OperationStatus.COMPLETED, "p", "https://example.test",
                "t", null, 1, 2, 3, 4, null, null,
                java.util.Map.of("Explorer", java.util.Map.of("success", true)));
        assertThat(response.agentResults())
                .as("the dashboard's per-agent panel depends on this field")
                .containsKey("Explorer");
    }

    private Set<String> mappedPaths() {
        Set<String> paths = new TreeSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            entry.getKey().getPathPatternsCondition().getPatterns()
                    .forEach(pattern -> paths.add(pattern.getPatternString()));
        }
        return paths;
    }
}
