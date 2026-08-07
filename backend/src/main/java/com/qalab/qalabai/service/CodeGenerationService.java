package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.testgen.TestGeneratorAgent;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorDto;
import com.qalab.qalabai.dto.testgen.GeneratedTestDto;
import com.qalab.qalabai.dto.testgen.TestGenResponse;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.TestPlan;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.LocatorRepository;
import com.qalab.qalabai.repository.TestPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class CodeGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationService.class);

    private final TestGeneratorAgent testGeneratorAgent;
    private final GeneratedTestRepository testRepository;
    private final LocatorRepository locatorRepository;
    private final TestPlanRepository testPlanRepository;
    private final AnalysisCache analysisCache;
    private final ObjectMapper objectMapper;

    public CodeGenerationService(TestGeneratorAgent testGeneratorAgent,
                                 GeneratedTestRepository testRepository,
                                 LocatorRepository locatorRepository,
                                 TestPlanRepository testPlanRepository,
                                 AnalysisCache analysisCache,
                                 ObjectMapper objectMapper) {
        this.testGeneratorAgent = testGeneratorAgent;
        this.testRepository = testRepository;
        this.locatorRepository = locatorRepository;
        this.testPlanRepository = testPlanRepository;
        this.analysisCache = analysisCache;
        this.objectMapper = objectMapper;
    }

    public TestGenResponse generateTests(String url) {
        log.info("Generating tests for URL: {}", url);

        AnalysisResponse analysis = analysisCache.getByUrl(url);
        if (analysis == null) {
            throw new RuntimeException("No analysis found for URL: " + url + ". Please analyze the page first.");
        }

        List<TestPlan> testPlans = testPlanRepository.findByPageUrl(url);
        if (testPlans.isEmpty()) {
            throw new RuntimeException("No test plan found for URL: " + url + ". Please generate a test plan first.");
        }

        TestPlan testPlan = testPlans.get(0);

        String testPlanJson;
        String locatorJson = "[]";
        try {
            testPlanJson = objectMapper.writeValueAsString(testPlan);
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

        Task task = new Task(UUID.randomUUID().toString(), "GENERATE_TESTS", url);
        task.putContext("pageUrl", url);
        task.putContext("testPlanJson", testPlanJson);
        task.putContext("locatorRepositoryJson", locatorJson);

        var result = testGeneratorAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Test generation failed: " + result.getMessage());
        }

        @SuppressWarnings("unchecked")
        List<GeneratedTest> tests = (List<GeneratedTest>) result.getData().get("tests");

        List<GeneratedTestDto> dtos = tests.stream()
                .map(this::toDto)
                .toList();

        return new TestGenResponse(dtos.size(), dtos);
    }

    public List<GeneratedTestDto> getTestsForUrl(String pageUrl) {
        return testRepository.findByPageUrl(pageUrl).stream()
                .map(this::toDto)
                .toList();
    }

    private GeneratedTestDto toDto(GeneratedTest entity) {
        return new GeneratedTestDto(
                entity.getId(),
                entity.getScenarioName(),
                entity.getTestCode(),
                entity.getPageObjectCode()
        );
    }
}
