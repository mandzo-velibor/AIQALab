package com.qalab.qalabai.service.healing;

import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.model.LocatorHistory;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.repository.LocatorHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Applies an approved healing suggestion by promoting the new locator to the
 * active locator history entry and demoting the previous one. The suggestion
 * status is set to APPLIED. No test source files are modified here.
 */
@Service
public class HealingApplier {

    private static final Logger log = LoggerFactory.getLogger(HealingApplier.class);

    private final HealingSuggestionRepository healingSuggestionRepository;
    private final LocatorHistoryRepository locatorHistoryRepository;

    public HealingApplier(HealingSuggestionRepository healingSuggestionRepository,
                          LocatorHistoryRepository locatorHistoryRepository) {
        this.healingSuggestionRepository = healingSuggestionRepository;
        this.locatorHistoryRepository = locatorHistoryRepository;
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

        log.info("Healing suggestion {} applied to element {}", suggestionId, suggestion.getElementName());
        return saved;
    }
}
