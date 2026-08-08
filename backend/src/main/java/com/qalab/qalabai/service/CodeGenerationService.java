package com.qalab.qalabai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.testgen.TestGeneratorAgent;
import com.qalab.qalabai.cache.AnalysisCache;
import com.qalab.qalabai.dto.analysis.AnalysisResponse;
import com.qalab.qalabai.dto.locator.LocatorDto;
import com.qalab.qalabai.dto.testgen.GeneratedFile;
import com.qalab.qalabai.dto.testgen.GeneratedTestDto;
import com.qalab.qalabai.dto.testgen.TestGenResponse;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.TestPlan;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.LocatorRepository;
import com.qalab.qalabai.repository.TestPlanRepository;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;

@Service
public class CodeGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationService.class);

    private final TestGeneratorAgent testGeneratorAgent;
    private final GeneratedTestRepository testRepository;
    private final LocatorRepository locatorRepository;
    private final TestPlanRepository testPlanRepository;
    private final AnalysisCache analysisCache;
    private final TestWorkspaceService testWorkspaceService;
    private final ObjectMapper objectMapper;

    public CodeGenerationService(TestGeneratorAgent testGeneratorAgent,
                                 GeneratedTestRepository testRepository,
                                 LocatorRepository locatorRepository,
                                 TestPlanRepository testPlanRepository,
                                 AnalysisCache analysisCache,
                                 TestWorkspaceService testWorkspaceService,
                                 ObjectMapper objectMapper) {
        this.testGeneratorAgent = testGeneratorAgent;
        this.testRepository = testRepository;
        this.locatorRepository = locatorRepository;
        this.testPlanRepository = testPlanRepository;
        this.analysisCache = analysisCache;
        this.testWorkspaceService = testWorkspaceService;
        this.objectMapper = objectMapper;
    }

    public TestGenResponse generateTests(String url) {
        return generateTests(url, null);
    }

    public TestGenResponse generateTests(String url, Long projectId) {
        log.info("Generating tests for URL: {}", url);

        List<GeneratedTest> tests = runGenerator(url, projectId);

        List<GeneratedTest> saved = testRepository.saveAll(tests);
        log.info("Saved {} tests to database", saved.size());

        if (projectId != null) {
            testWorkspaceService.writeTestFiles(projectId, saved);
        }

        List<GeneratedTestDto> dtos = saved.stream()
                .map(this::toDto)
                .toList();

        return new TestGenResponse(dtos.size(), dtos);
    }

    public List<GeneratedTest> generateTestsEntities(String url, Long projectId) {
        log.info("Generating tests (entities, no persist) for URL: {}", url);
        return runGenerator(url, projectId);
    }

    /**
     * Service-contract path: generates test source and returns it to the caller
     * as files. Nothing is persisted and nothing is written into any workspace;
     * the client decides where to place the files.
     */
    public List<GeneratedFile> generateTestsContent(String url, Long projectId) {
        log.info("Generating test content (service path) for URL: {}", url);

        List<GeneratedTest> tests = runGenerator(url, projectId);

        return tests.stream()
                .map(t -> new GeneratedFile(TestWorkspaceService.resolveFileName(t), t.getTestCode()))
                .toList();
    }

    private List<GeneratedTest> runGenerator(String url, Long projectId) {
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
        String pageContentHtml = analysisCache.getSimplifiedHtmlByUrl(url);
        String postLoginContentHtml = analysisCache.getPostLoginContentByUrl(url);
        AnalysisCache.LoginCredentials credentials = analysisCache.getLoginCredentialsByUrl(url);
        try {
            Map<String, Object> testPlanMap = new HashMap<>();
            testPlanMap.put("id", testPlan.getId());
            testPlanMap.put("pageUrl", testPlan.getPageUrl());
            testPlanMap.put("pageType", testPlan.getPageType());

            List<Map<String, Object>> scenarioMaps = testPlan.getScenarios().stream()
                    .map(s -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", s.getId());
                        m.put("name", s.getName());
                        m.put("type", s.getType());
                        m.put("priority", s.getPriority());
                        m.put("description", s.getDescription());
                        m.put("steps", s.getSteps());
                        m.put("requiredElements", s.getRequiredElements());
                        return m;
                    })
                    .toList();
            testPlanMap.put("scenarios", scenarioMaps);

            testPlanJson = objectMapper.writeValueAsString(testPlanMap);

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
        task.putContext("pageContentHtml", pageContentHtml != null ? pageContentHtml : "");
        task.putContext("postLoginContentHtml", postLoginContentHtml != null ? postLoginContentHtml : "");
        if (credentials != null) {
            task.putContext("loginUsername", credentials.username());
            task.putContext("loginPassword", credentials.password());
        }
        if (projectId != null) {
            task.putContext("projectId", projectId);
        }

        var result = testGeneratorAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Test generation failed: " + result.getMessage());
        }

        @SuppressWarnings("unchecked")
        List<GeneratedTest> tests = (List<GeneratedTest>) result.getData().get("tests");
        return tests;
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
