package com.qalab.qalabai.service.healing;

import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.model.LocatorHistory;
import com.qalab.qalabai.repository.GeneratedTestRepository;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
import com.qalab.qalabai.service.workspace.TestWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies an approved healing suggestion by promoting the new locator to the
 * active locator history entry, demoting the previous one, and - crucially -
 * rewriting the persisted test source ({@link GeneratedTest#getTestCode()} and
 * page object) plus the on-disk workspace file, so the next execution actually
 * uses the healed locator. The suggestion status is set to APPLIED.
 */
@Service
public class HealingApplier {

    private static final Logger log = LoggerFactory.getLogger(HealingApplier.class);

    private final HealingSuggestionRepository healingSuggestionRepository;
    private final LocatorHistoryRepository locatorHistoryRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final TestWorkspaceService testWorkspaceService;

    public HealingApplier(HealingSuggestionRepository healingSuggestionRepository,
                          LocatorHistoryRepository locatorHistoryRepository,
                          GeneratedTestRepository generatedTestRepository,
                          TestWorkspaceService testWorkspaceService) {
        this.healingSuggestionRepository = healingSuggestionRepository;
        this.locatorHistoryRepository = locatorHistoryRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.testWorkspaceService = testWorkspaceService;
    }

    public HealingSuggestion apply(Long suggestionId, String appliedBy) {
        HealingSuggestion suggestion = healingSuggestionRepository.findById(suggestionId)
                .orElseThrow(() -> new RuntimeException("Suggestion not found: " + suggestionId));

        if (!"APPROVED".equals(suggestion.getStatus())) {
            throw new RuntimeException("Only APPROVED suggestions can be applied. Current status: " + suggestion.getStatus());
        }

        locatorHistoryRepository.findByProjectIdAndElementNameAndStatus(
                        suggestion.getProjectId(), suggestion.getElementName(), "ACTIVE")
                .ifPresent(old -> {
                    old.setStatus("REPLACED");
                    locatorHistoryRepository.save(old);
                });

        LocatorHistory active = new LocatorHistory();
        active.setProjectId(suggestion.getProjectId());
        active.setElementName(suggestion.getElementName());
        active.setLocator(suggestion.getNewLocator());
        active.setStrategy("getByRole");
        active.setConfidence(suggestion.getConfidence());
        active.setStatus("ACTIVE");
        active.setLastSuccessfulExecution(LocalDateTime.now());
        locatorHistoryRepository.save(active);

        suggestion.setStatus("APPLIED");
        suggestion.setApprovedBy(appliedBy);
        suggestion.setApprovedAt(LocalDateTime.now());
        HealingSuggestion saved = healingSuggestionRepository.save(suggestion);

        applyToTestSources(suggestion);

        log.info("Healing suggestion {} applied to element {}", suggestionId, suggestion.getElementName());
        return saved;
    }

    /**
     * Rewrites the persisted {@link GeneratedTest} source (and page object) that
     * references the old locator so the healed locator is what Playwright runs
     * next time, then re-syncs the workspace file.
     */
    private void applyToTestSources(HealingSuggestion suggestion) {
        if (suggestion.getProjectId() == null
                || suggestion.getOldLocator() == null || suggestion.getOldLocator().isBlank()
                || suggestion.getNewLocator() == null || suggestion.getNewLocator().isBlank()) {
            log.warn("Skipping test source update: missing project/old/new locator");
            return;
        }

        String oldExpr = stripPagePrefix(suggestion.getOldLocator());
        String newExpr = stripPagePrefix(suggestion.getNewLocator());

        List<GeneratedTest> updated = new ArrayList<>();
        for (GeneratedTest test : generatedTestRepository.findByProjectId(suggestion.getProjectId())) {
            boolean changed = false;

            String code = test.getTestCode();
            if (code != null && code.contains(oldExpr)) {
                test.setTestCode(code.replace(oldExpr, newExpr));
                changed = true;
            }

            String pageObject = test.getPageObjectCode();
            if (pageObject != null && pageObject.contains(oldExpr)) {
                test.setPageObjectCode(pageObject.replace(oldExpr, newExpr));
                changed = true;
            }

            if (changed) {
                generatedTestRepository.save(test);
                updated.add(test);
            }
        }

        if (updated.isEmpty()) {
            log.warn("No generated test contains old locator '{}'; test source left unchanged", suggestion.getOldLocator());
            return;
        }

        try {
            testWorkspaceService.writeTestFiles(suggestion.getProjectId(), updated);
            log.info("Rewrote {} test file(s) with healed locator '{}'", updated.size(), suggestion.getNewLocator());
        } catch (Exception e) {
            log.warn("Failed to re-sync workspace after applying healing suggestion: {}", e.getMessage());
        }
    }

    private String stripPagePrefix(String locator) {
        String trimmed = locator.trim();
        return trimmed.startsWith("page.") ? trimmed.substring("page.".length()) : trimmed;
    }
}
