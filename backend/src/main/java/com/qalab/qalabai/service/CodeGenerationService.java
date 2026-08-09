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
import com.qalab.qalabai.model.TestScenario;
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

    private static final Set<String> API_SCENARIO_TYPES = Set.of("security", "validation", "api");

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
        return generateTests(url, null, null, null);
    }

    public TestGenResponse generateTests(String url, Long projectId) {
        return generateTests(url, projectId, null, null);
    }

    public TestGenResponse generateTests(String url, Long projectId, String instruction, String testType) {
        log.info("Generating tests for URL: {} (testType={})", url, testType);

        String normalizedInstruction = com.qalab.qalabai.util.UserInstructions.normalize(instruction);
        String normalizedType = normalizeTestType(testType);
        if (normalizedInstruction != null) {
            log.info("Applying user instruction for test generation: {}", normalizedInstruction);
        }

        List<GeneratedTest> tests = runGenerator(url, projectId, normalizedInstruction, normalizedType);

        List<GeneratedTest> saved = testRepository.saveAll(tests);
        log.info("Saved {} tests to database", saved.size());

        if (projectId != null) {
            testWorkspaceService.writeTestFiles(projectId, saved);
        }

        List<GeneratedTestDto> dtos = saved.stream()
                .map(this::toDto)
                .toList();

        String note = buildNote(normalizedType, normalizedInstruction, dtos.size());

        return new TestGenResponse(dtos.size(), dtos, normalizedInstruction, normalizedType, note);
    }

    public List<GeneratedTest> generateTestsEntities(String url, Long projectId) {
        return generateTestsEntities(url, projectId, null, null);
    }

    public List<GeneratedTest> generateTestsEntities(String url, Long projectId, String instruction, String testType) {
        log.info("Generating tests (entities, no persist) for URL: {}", url);
        String normalizedType = normalizeTestType(testType);
        return runGenerator(url, projectId, com.qalab.qalabai.util.UserInstructions.normalize(instruction), normalizedType);
    }

    /**
     * Service-contract path: generates test source and returns it to the caller
     * as files. Nothing is persisted and nothing is written into any workspace;
     * the client decides where to place the files.
     */
    public List<GeneratedFile> generateTestsContent(String url, Long projectId) {
        return generateTestsContent(url, projectId, null, null);
    }

    public List<GeneratedFile> generateTestsContent(String url, Long projectId, String instruction, String testType) {
        log.info("Generating test content (service path) for URL: {}", url);

        List<GeneratedTest> tests = runGenerator(url, projectId, com.qalab.qalabai.util.UserInstructions.normalize(instruction), normalizeTestType(testType));

        return tests.stream()
                .map(t -> new GeneratedFile(TestWorkspaceService.resolveFileName(t), t.getTestCode()))
                .toList();
    }

    private List<GeneratedTest> runGenerator(String url, Long projectId, String instruction, String testType) {
        AnalysisResponse analysis = analysisCache.getByUrl(url);
        if (analysis == null) {
            throw new RuntimeException("No analysis found for URL: " + url + ". Please analyze the page first.");
        }

        List<TestPlan> testPlans = testPlanRepository.findByPageUrl(url);
        if (testPlans.isEmpty()) {
            throw new RuntimeException("No test plan found for URL: " + url + ". Please generate a test plan first.");
        }

        TestPlan testPlan = testPlans.get(0);

        List<TestScenario> scenarios = testPlan.getScenarios();
        if (testType != null) {
            List<TestScenario> filtered = scenarios.stream()
                    .filter(s -> scenarioMatchesType(s, testType))
                    .toList();
            if (!filtered.isEmpty()) {
                log.info("Filtering {} scenarios by testType={}: kept {}", scenarios.size(), testType, filtered.size());
                scenarios = filtered;
            }
        }

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

            List<Map<String, Object>> scenarioMaps = scenarios.stream()
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
        if (instruction != null) {
            task.putContext("instruction", instruction);
        }
        if (testType != null) {
            task.putContext("testType", testType);
        }

        var result = testGeneratorAgent.execute(task);

        if (!result.isSuccess()) {
            throw new RuntimeException("Test generation failed: " + result.getMessage());
        }

        @SuppressWarnings("unchecked")
        List<GeneratedTest> tests = (List<GeneratedTest>) result.getData().get("tests");
        if (testType != null) {
            for (GeneratedTest t : tests) {
                t.setTestType(testType);
            }
        }
        return tests;
    }

    /**
     * Deterministic scenario-to-test-type mapping. No AI is involved; the mapping
     * is a fixed, documented rule set.
     */
    static boolean scenarioMatchesType(TestScenario scenario, String testType) {
        String type = scenario.getType() == null ? "" : scenario.getType().trim().toLowerCase();
        return switch (testType == null ? "all" : testType.trim().toLowerCase()) {
            case "api" -> API_SCENARIO_TYPES.contains(type) || type.contains("api");
            case "ui", "e2e" -> !type.contains("api");
            default -> true;
        };
    }

    /** Normalizes the structured test type to UI/E2E/API or null for ALL. */
    static String normalizeTestType(String testType) {
        if (testType == null || testType.isBlank()) {
            return null;
        }
        String normalized = testType.trim().toUpperCase();
        if ("ALL".equals(normalized)) {
            return null;
        }
        return switch (normalized) {
            case "UI" -> "ui";
            case "E2E" -> "e2e";
            case "API" -> "api";
            default -> null;
        };
    }

    private String buildNote(String testType, String instruction, int generatedCount) {
        List<String> parts = new ArrayList<>();
        if (testType != null) {
            parts.add("The structured " + testType.toUpperCase() + " filter limited generation to matching scenarios.");
        }
        if (instruction != null) {
            String mentioned = mentionedTestType(instruction);
            if (mentioned != null && testType != null && !testType.equalsIgnoreCase(mentioned)) {
                parts.add("Conflict: the textual instruction mentions " + mentioned.toUpperCase()
                        + " tests but the structured filter is " + testType.toUpperCase()
                        + "; the structured filter wins because it is deterministic.");
            }
        }
        if (generatedCount == 0) {
            parts.add("No tests were generated.");
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private String mentionedTestType(String instruction) {
        String lower = instruction.toLowerCase();
        if (lower.contains("api")) {
            return "api";
        }
        if (lower.contains("e2e")) {
            return "e2e";
        }
        if (lower.contains("ui")) {
            return "ui";
        }
        return null;
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
                entity.getTestType(),
                entity.getTestCode(),
                entity.getPageObjectCode()
        );
    }
}
