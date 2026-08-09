package com.qalab.qalabai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.locator.LocatorAgent;
import com.qalab.qalabai.agent.planner.PlannerAgent;
import com.qalab.qalabai.agent.testgen.TestGeneratorAgent;
import com.qalab.qalabai.ai.gateway.AiGateway;
import com.qalab.qalabai.ai.gateway.AiProviderType;
import com.qalab.qalabai.ai.gateway.AiRequest;
import com.qalab.qalabai.ai.gateway.AiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies Sprint 14.6 user-control behaviour: the user instruction is appended
 * to the user prompt as a clearly separated USER INSTRUCTIONS section, and is
 * omitted entirely when no instruction was provided.
 */
class AgentInstructionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiGateway aiGateway;
    private ArgumentCaptor<AiRequest> captor;

    @BeforeEach
    void setUp() {
        aiGateway = mock(AiGateway.class);
        captor = ArgumentCaptor.forClass(AiRequest.class);
    }

    private void stubResponse(String content) {
        when(aiGateway.complete(any(), any())).thenReturn(
                new AiResponse(content, AiProviderType.AIQALAB, "test-model", 10, 10, false, BigDecimal.ZERO, "op-1"));
    }

    private Task task(String instruction) {
        Task task = new Task("task-1", "OP", "https://the-internet.herokuapp.com/login");
        task.putContext("pageUrl", "https://the-internet.herokuapp.com/login");
        task.putContext("pageAnalysisJson", "{\"pageType\":\"LOGIN\"}");
        task.putContext("locatorRepositoryJson", "[]");
        task.putContext("testPlanJson", "{\"scenarios\":[]}");
        task.putContext("pageContentHtml", "<html><body><h1>Login</h1></body></html>");
        task.putContext("postLoginContentHtml", "");
        task.putContext("loginUsername", "tomsmith");
        task.putContext("loginPassword", "SuperSecretPassword!");
        if (instruction != null) {
            task.putContext("instruction", instruction);
        }
        return task;
    }

    @Test
    void locatorAgentAppendsInstructionToUserPrompt() throws Exception {
        stubResponse("{\"locators\":[]}");
        new LocatorAgent(aiGateway, objectMapper).execute(task("focus on the login form"));

        AiRequest request = captureRequest();
        assertTrue(request.getUserPrompt().contains("USER INSTRUCTIONS"));
        assertTrue(request.getUserPrompt().contains("focus on the login form"));
    }

    @Test
    void locatorAgentOmitsInstructionWhenAbsent() throws Exception {
        stubResponse("{\"locators\":[]}");
        new LocatorAgent(aiGateway, objectMapper).execute(task(null));

        assertFalse(captureRequest().getUserPrompt().contains("USER INSTRUCTIONS"));
    }

    @Test
    void plannerAgentAppendsInstructionToUserPrompt() throws Exception {
        stubResponse("{\"pageType\":\"Login\",\"scenarios\":[]}");
        new PlannerAgent(aiGateway, objectMapper).execute(task("exclude security scenarios"));

        AiRequest request = captureRequest();
        assertTrue(request.getUserPrompt().contains("USER INSTRUCTIONS"));
        assertTrue(request.getUserPrompt().contains("exclude security scenarios"));
    }

    @Test
    void testGeneratorAgentAppendsInstructionAndTestTypeConstraint() throws Exception {
        stubResponse("{\"tests\":[]}");
        Task task = task("write a negative login test");
        task.putContext("testType", "ui");
        new TestGeneratorAgent(aiGateway, objectMapper).execute(task);

        AiRequest request = captureRequest();
        assertTrue(request.getUserPrompt().contains("USER INSTRUCTIONS"));
        assertTrue(request.getUserPrompt().contains("write a negative login test"));
        assertTrue(request.getUserPrompt().contains("STRUCTURED TEST TYPE CONSTRAINT"));
        assertTrue(request.getUserPrompt().contains("Generate only UI tests"));
    }

    @Test
    void testGeneratorAgentOmitsConstraintForAll() throws Exception {
        stubResponse("{\"tests\":[]}");
        new TestGeneratorAgent(aiGateway, objectMapper).execute(task(null));

        assertFalse(captureRequest().getUserPrompt().contains("USER INSTRUCTIONS"));
        assertFalse(captureRequest().getUserPrompt().contains("STRUCTURED TEST TYPE CONSTRAINT"));
    }

    private AiRequest captureRequest() {
        verify(aiGateway).complete(captor.capture(), any());
        return captor.getValue();
    }
}
