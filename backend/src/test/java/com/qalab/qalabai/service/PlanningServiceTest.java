package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.model.LocatorDefinition;
import com.qalab.qalabai.agent.AgentResult;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.planner.PlannerAgent;
import com.qalab.qalabai.model.TestPlan;
import com.qalab.qalabai.model.TestScenario;
import com.qalab.qalabai.dto.planner.TestPlanResponse;
import com.qalab.qalabai.repository.LocatorRepository;
import com.qalab.qalabai.repository.TestPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan generation sits between analysis and test generation, so a failure here loses the
 * whole run. The branch that matters most is the one that refuses to plan against a page
 * that was never analysed: a plan built from no analysis is a plan built from nothing, and
 * it would be persisted and consumed as though it were grounded.
 */
class PlanningServiceTest {

    private PlannerAgent plannerAgent;
    private AnalysisCache analysisCache;
    private LocatorRepository locatorRepository;
    private TestPlanRepository testPlanRepository;
    private PlanningService service;

    private static final String PLAN_JSON = """
            {"scenarios":[
              {"name":"Login with valid credentials","priority":"HIGH",
               "steps":["open /login","enter user","submit"]},
              {"name":"Login with a bad password","priority":"MEDIUM",
               "steps":["open /login","submit empty form"]}]}
            """;

    @BeforeEach
    void setUp() {
        plannerAgent = mock(PlannerAgent.class);
        analysisCache = mock(AnalysisCache.class);
        locatorRepository = mock(LocatorRepository.class);
        testPlanRepository = mock(TestPlanRepository.class);
        // JPA returns the saved entity with its generated id; a mock returning null makes
        // the service NPE on a line that works fine against a real repository.
        when(testPlanRepository.save(any())).thenAnswer(i -> {
            TestPlan plan = i.getArgument(0);
            plan.setId(1L);
            return plan;
        });
        service = new PlanningService(plannerAgent, testPlanRepository, locatorRepository,
                analysisCache, new ObjectMapper());
    }

    private static AnalysisResponse analysedPage() {
        return new AnalysisResponse("login", "A login form.", 80,
                List.of(), List.of("Sign in"), List.of(), List.of(), List.of(),
                List.of(), List.of(), "/tmp/shot.png");
    }

    private void stubModelResponse() {
        when(plannerAgent.execute(any())).thenReturn(planResult());
    }

    private static AgentResult planResult() {
        TestPlan plan = new TestPlan();
        plan.setPageUrl("https://example.test/login");
        TestScenario first = new TestScenario();
        first.setName("Login with valid credentials");
        first.setPriority("HIGH");
        first.setSteps(List.of("open /login", "enter user", "submit"));
        TestScenario second = new TestScenario();
        second.setName("Login with a bad password");
        second.setPriority("MEDIUM");
        plan.setScenarios(List.of(first, second));

        AgentResult result = AgentResult.success("Planner", "planned");
        result.getData().put("testPlan", plan);
        return result;
    }

    @Test
    void refusesToPlanAPageThatWasNeverAnalysed() {
        when(analysisCache.getByUrl(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.generateTestPlan("https://example.test/login", 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No analysis found")
                .hasMessageContaining("analyze the page first");

        // The guard is only worth having if it stops the run before the model is called.
        verify(plannerAgent, never()).execute(any());
        verify(testPlanRepository, never()).save(any());
    }

    @Test
    void buildsAScenarioPlanFromTheStoredAnalysis() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        when(locatorRepository.findByPageUrl(anyString())).thenReturn(List.of());
        stubModelResponse();

        TestPlanResponse plan = service.generateTestPlan("https://example.test/login", 1L);

        assertThat(plan.scenarios()).hasSize(2);
        assertThat(plan.scenarios().get(0).name())
                .as("the scenario name is what the user reads in the plan UI")
                .isEqualTo("Login with valid credentials");
    }

    @Test
    void locatorsArePassedToTheModelSoItCanGroundThePlan() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        LocatorDefinition email = new LocatorDefinition();
        email.setElementName("email");
        email.setPreferredLocator("getByLabel('Email')");
        email.setStrategy("getByLabel");
        when(locatorRepository.findByPageUrl(anyString())).thenReturn(List.of(email));
        stubModelResponse();

        service.generateTestPlan("https://example.test/login", 1L);

        ArgumentCaptor<Task> sent = ArgumentCaptor.forClass(Task.class);
        verify(plannerAgent).execute(sent.capture());
        assertThat(sent.getValue().getContext().toString())
                .as("a plan written without the known locators invents selectors that will "
                        + "not exist on the page")
                .contains("Email");
    }

    @Test
    void aUserInstructionIsCarriedIntoThePlan() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        when(locatorRepository.findByPageUrl(anyString())).thenReturn(List.of());
        stubModelResponse();

        TestPlanResponse plan = service.generateTestPlan("https://example.test/login", 1L,
                "ignore accessibility and focus on checkout");

        assertThat(plan.instruction())
                .as("the instruction should be echoed back so the UI can show what was asked")
                .contains("checkout");
    }

    @Test
    void aModelResponseThatIsNotAPlanFailsLoudlyRatherThanPersistingNonsense() {
        when(analysisCache.getByUrl(anyString())).thenReturn(analysedPage());
        when(locatorRepository.findByPageUrl(anyString())).thenReturn(List.of());
        when(plannerAgent.execute(any()))
                .thenReturn(AgentResult.failure("Planner", "model refused to answer"));

        assertThatThrownBy(() -> service.generateTestPlan("https://example.test/login", 1L))
                .isInstanceOf(RuntimeException.class);

        // Nothing may be saved: an empty plan persisted is worse than a failed call,
        // because the run continues and generates tests from it.
        verify(testPlanRepository, never()).save(any());
    }
}
