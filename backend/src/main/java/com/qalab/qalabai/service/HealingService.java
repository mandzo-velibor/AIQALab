package com.qalab.qalabai.service;

import com.qalab.qalabai.agent.Task;
import com.qalab.qalabai.agent.healing.SelfHealingAgent;
import com.qalab.qalabai.model.FailureAnalysis;
import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.model.LocatorHistory;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.model.TestExecution;
import com.qalab.qalabai.repository.FailureAnalysisRepository;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.repository.TestExecutionRepository;
import com.qalab.qalabai.service.healing.ElementMatcherService;
import com.qalab.qalabai.service.healing.FailedLocatorExtractor;
import com.qalab.qalabai.service.healing.HealingApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class HealingService {

    private static final Logger log = LoggerFactory.getLogger(HealingService.class);

    private final SelfHealingAgent selfHealingAgent;
    private final FailureAnalysisRepository failureAnalysisRepository;
    private final HealingSuggestionRepository healingSuggestionRepository;
    private final ProjectRepository projectRepository;
    private final LocatorHistoryRepository locatorHistoryRepository;
    private final ElementMatcherService elementMatcherService;
    private final HealingApplier healingApplier;
    private final FailureAnalysisService failureAnalysisService;
    private final TestExecutionRepository testExecutionRepository;

    public HealingService(SelfHealingAgent selfHealingAgent,
                          FailureAnalysisRepository failureAnalysisRepository,
                          HealingSuggestionRepository healingSuggestionRepository,
                          ProjectRepository projectRepository,
                          LocatorHistoryRepository locatorHistoryRepository,
                          ElementMatcherService elementMatcherService,
                          HealingApplier healingApplier,
                          FailureAnalysisService failureAnalysisService,
                          TestExecutionRepository testExecutionRepository) {
        this.selfHealingAgent = selfHealingAgent;
        this.failureAnalysisRepository = failureAnalysisRepository;
        this.healingSuggestionRepository = healingSuggestionRepository;
        this.projectRepository = projectRepository;
        this.locatorHistoryRepository = locatorHistoryRepository;
        this.elementMatcherService = elementMatcherService;
        this.healingApplier = healingApplier;
        this.failureAnalysisService = failureAnalysisService;
        this.testExecutionRepository = testExecutionRepository;
    }

    public HealingSuggestion generateHealingSuggestion(Long executionId) {
        log.info("Generating healing suggestion for execution {}", executionId);

        FailureAnalysis analysis = failureAnalysisRepository.findByExecutionId(executionId)
                .orElseGet(() -> analyzeExecution(executionId));

        Project project = projectRepository.findById(analysis.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found: " + analysis.getProjectId()));

        HealingSuggestion suggestion = generateWithAiAgent(analysis, project);
        if (suggestion == null) {
            log.info("AI agent produced no suggestion, falling back to deterministic matcher");
            suggestion = generateWithElementMatcher(analysis, project);
        }
        if (suggestion == null) {
            throw new RuntimeException("Healing generation failed: no suitable locator found");
        }
        return suggestion;
    }

    private FailureAnalysis analyzeExecution(Long executionId) {
        log.info("No failure analysis found for execution {}, running analysis first", executionId);

        TestExecution execution = testExecutionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

        if (execution.getProjectId() == null) {
            throw new RuntimeException("Execution " + executionId + " has no project, cannot run healing");
        }

        return failureAnalysisService.analyzeExecution(executionId, execution.getProjectId());
    }

    private HealingSuggestion generateWithAiAgent(FailureAnalysis analysis, Project project) {
        String elementName = analysis.getAffectedElement();
        String oldLocator = null;

        LocatorHistory history = locatorHistoryRepository
                .findByProjectIdAndElementNameAndStatus(project.getId(), analysis.getAffectedElement(), "ACTIVE")
                .orElse(null);

        if (history != null) {
            elementName = history.getElementName();
            oldLocator = history.getLocator();
        } else {
            log.warn("No active locator history for element: {}, using locator extracted from the failure",
                    analysis.getAffectedElement());
            oldLocator = extractFailedLocator(analysis.getExecutionId())
                    .or(() -> Optional.ofNullable(analysis.getAffectedElement()).filter(s -> !s.isBlank()))
                    .orElse(null);
        }

        if (oldLocator == null || oldLocator.isBlank()) {
            log.warn("No usable old locator for element: {}", analysis.getAffectedElement());
            return null;
        }

        Task task = new Task(UUID.randomUUID().toString(), "GENERATE_HEALING", project.getBaseUrl());
        task.putContext("failureAnalysisId", analysis.getId());
        task.putContext("executionId", analysis.getExecutionId());
        task.putContext("projectId", project.getId());
        task.putContext("baseUrl", project.getBaseUrl());
        task.putContext("elementName", elementName);
        task.putContext("oldLocator", oldLocator);
        task.putContext("failureSummary", analysis.getSummary());

        var result = selfHealingAgent.execute(task);

        if (!result.isSuccess()) {
            log.warn("AI agent failed: {}", result.getMessage());
            return null;
        }

        HealingSuggestion suggestion = (HealingSuggestion) result.getData().get("suggestion");
        if (suggestion == null || suggestion.getNewLocator() == null || suggestion.getNewLocator().isBlank()) {
            log.warn("AI agent produced no suggestion");
            return null;
        }

        HealingSuggestion saved = healingSuggestionRepository.save(suggestion);
        log.info("Healing suggestion saved with id: {}", saved.getId());

        verifyWithMatcher(saved, project);
        return saved;
    }

    private Optional<String> extractFailedLocator(Long executionId) {
        if (executionId == null) {
            return Optional.empty();
        }
        return testExecutionRepository.findById(executionId)
                .flatMap(exec -> FailedLocatorExtractor.extract(exec.getErrorMessage(), exec.getConsoleLogs()));
    }

    private void verifyWithMatcher(HealingSuggestion suggestion, Project project) {
        if (suggestion.getNewLocator() == null || suggestion.getNewLocator().isBlank()) {
            return;
        }
        List<ElementMatcherService.Candidate> candidates = elementMatcherService.findCandidates(
                project.getBaseUrl(), suggestion.getOldLocator(), suggestion.getElementName());
        if (!candidates.isEmpty()) {
            ElementMatcherService.Candidate best = candidates.get(0);
            if (best.score() >= 0.5) {
                log.info("Matcher confirmed candidate '{}' (score {}) for '{}'",
                        best.locator(), best.score(), suggestion.getElementName());
                suggestion.setNewLocator(best.locator());
                suggestion.setReason(suggestion.getReason() != null
                        ? suggestion.getReason() + " [matcher-confirmed: " + best.name() + "]"
                        : "[matcher-confirmed: " + best.name() + "]");
                healingSuggestionRepository.save(suggestion);
            }
        }
    }

    private HealingSuggestion generateWithElementMatcher(FailureAnalysis analysis, Project project) {
        String brokenLocator = extractFailedLocator(analysis.getExecutionId())
                .orElse(analysis.getAffectedElement());
        List<ElementMatcherService.Candidate> candidates = elementMatcherService.findCandidates(
                project.getBaseUrl(), brokenLocator, analysis.getAffectedElement());

        if (candidates.isEmpty()) {
            return null;
        }
        ElementMatcherService.Candidate best = candidates.get(0);

        HealingSuggestion suggestion = new HealingSuggestion();
        suggestion.setProjectId(project.getId());
        suggestion.setExecutionId(analysis.getExecutionId());
        suggestion.setFailureAnalysisId(analysis.getId());
        suggestion.setElementName(analysis.getAffectedElement());
        suggestion.setOldLocator(brokenLocator != null ? brokenLocator : analysis.getAffectedElement());
        suggestion.setNewLocator(best.locator());
        suggestion.setConfidence((int) (best.score() * 100));
        suggestion.setReason("Deterministic fallback (AI agent failed): matched role " + best.role());
        suggestion.setStatus("PENDING");
        return healingSuggestionRepository.save(suggestion);
    }

    public HealingSuggestion approveSuggestion(Long suggestionId, String approvedBy) {
        log.info("Approving healing suggestion {}", suggestionId);

        HealingSuggestion suggestion = healingSuggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Suggestion not found: " + suggestionId));

        suggestion.setStatus("APPROVED");
        suggestion.setApprovedBy(approvedBy);
        suggestion.setApprovedAt(java.time.LocalDateTime.now());

        return healingSuggestionRepository.save(suggestion);
    }

    public HealingSuggestion rejectSuggestion(Long suggestionId, String rejectedBy) {
        log.info("Rejecting healing suggestion {}", suggestionId);

        HealingSuggestion suggestion = healingSuggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Suggestion not found: " + suggestionId));

        suggestion.setStatus("REJECTED");
        suggestion.setApprovedBy(rejectedBy);
        suggestion.setApprovedAt(java.time.LocalDateTime.now());

        return healingSuggestionRepository.save(suggestion);
    }

    public HealingSuggestion applySuggestion(Long suggestionId, String appliedBy) {
        log.info("Applying healing suggestion {}", suggestionId);
        return healingApplier.apply(suggestionId, appliedBy);
    }
}
