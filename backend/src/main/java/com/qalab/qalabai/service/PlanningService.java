package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.planner.PlannerAgent;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorDto;
import com.qalab.qalabai.dto.planner.TestPlanResponse;
import com.qalab.qalabai.dto.planner.TestScenarioDto;
import com.qalab.qalabai.model.TestPlan;
import com.qalab.qalabai.repository.LocatorRepository;
import com.qalab.qalabai.repository.TestPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    private final PlannerAgent plannerAgent;
    private final TestPlanRepository testPlanRepository;
    private final LocatorRepository locatorRepository;
    private final AnalysisCache analysisCache;
    private final ObjectMapper objectMapper;

    public PlanningService(PlannerAgent plannerAgent,
                           TestPlanRepository testPlanRepository,
                           LocatorRepository locatorRepository,
                           AnalysisCache analysisCache,
                           ObjectMapper objectMapper) {
        this.plannerAgent = plannerAgent;
        this.testPlanRepository = testPlanRepository;
        this.locatorRepository = locatorRepository;
        this.analysisCache = analysisCache;
        this.objectMapper = objectMapper;
    }

    public TestPlanResponse generateTestPlan(String url) {
        return generateTestPlan(url, null, null);
    }

    public TestPlanResponse generateTestPlan(String url, Long projectId) {
        return generateTestPlan(url, projectId, null);
    }

    public TestPlanResponse generateTestPlan(String url, Long projectId, String instruction) {
        log.info("Generating test plan for URL: {}", url);

        String normalized = com.qalab.qalabai.util.UserInstructions.normalize(instruction);
        if (normalized != null) {
            log.info("Applying user instruction for test plan generation: {}", normalized);
        }

        AnalysisResponse analysis = analysisCache.getByUrl(url);
        if (analysis == null) {
            throw new RuntimeException("No analysis found for URL: " + url + ". Please analyze the page first.");
        }

        String analysisJson;
        String locatorJson = "[]";
        try {
            analysisJson = objectMapper.writeValueAsString(analysis);
            List<LocatorDto> locators = locatorRepository.findByPageUrl(url).stream()
                    .map(l -> new LocatorDto(
                            l.getId(),
                            l.getElementName(),
                            l.getElementType(),
                            l.getPreferredLocator(),
                            l.getFallbackLocators() != null && !l.getFallbackLocators().isBlank()
                                    ? Arrays.asList(l.getFallbackLocators().split(","))
                                    : List.of(),
                            l.getStrategy(),
                            l.getConfidence(),
                            l.getReason()
                    ))
                    .toList();
            locatorJson = objectMapper.writeValueAsString(locators);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize data: " + e.getMessage(), e);
        }

        Task task = new Task(UUID.randomUUID().toString(), "GENERATE_TEST_PLAN", url);
        task.putContext("pageUrl", url);
        task.putContext("pageAnalysisJson", analysisJson);
        task.putContext("locatorRepositoryJson", locatorJson);
        if (projectId != null) {
            task.putContext("projectId", projectId);
        }
        if (normalized != null) {
            task.putContext("instruction", normalized);
        }

        var result = plannerAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Test plan generation failed: " + result.getMessage());
        }

        TestPlan testPlan = (TestPlan) result.getData().get("testPlan");
        TestPlan saved = testPlanRepository.save(testPlan);
        log.info("Saved test plan to database with id: {}", saved.getId());

        List<TestScenarioDto> dtos = saved.getScenarios().stream()
                .map(s -> new TestScenarioDto(
                        s.getId(),
                        s.getName(),
                        s.getType(),
                        s.getPriority(),
                        s.getDescription(),
                        s.getSteps(),
                        s.getRequiredElements()
                ))
                .toList();

        return new TestPlanResponse(dtos.size(), dtos, normalized);
    }

    public List<TestPlanResponse> getTestPlansForUrl(String pageUrl) {
        return testPlanRepository.findByPageUrl(pageUrl).stream()
                .map(tp -> {
                    List<TestScenarioDto> dtos = tp.getScenarios().stream()
                            .map(s -> new TestScenarioDto(
                                    s.getId(),
                                    s.getName(),
                                    s.getType(),
                                    s.getPriority(),
                                    s.getDescription(),
                                    s.getSteps(),
                                    s.getRequiredElements()
                            ))
                            .toList();
                    return new TestPlanResponse(dtos.size(), dtos, null);
                })
                .toList();
    }
}
