package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.model.HealingSuggestion;
import com.qalab.qalabai.repository.HealingSuggestionRepository;
import com.qalab.qalabai.service.HealingService;
import com.qalab.qalabai.service.ProjectContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The healing-suggestion lifecycle on the v1 surface: generate, review, apply.
 *
 * <p><strong>Why this exists separately from {@link V1HealingProposalController}.</strong>
 * There are two healing models in the codebase and they are not interchangeable.
 * {@code HealingProposal} (String id) is a review artefact: it records a recommended
 * locator and whether a human accepted it. {@code HealingSuggestion} (Long id) is the one
 * that can actually <em>do</em> something — {@code apply} rewrites the generated test
 * source and page object and promotes the new locator in history.
 *
 * <p>Collapsing the two was tempting and would have deleted a working feature: a proposal
 * carries no {@code elementName}, which the applier needs to supersede the right locator
 * history row, so a proposal-based apply could not do the job. Unifying the models is a
 * schema migration and is deliberately not smuggled into an API-surface task.
 *
 * <p>What this buys is the thing B-033 actually asked for: one URL space, one auth path,
 * one error envelope. Both healing models are now reachable under {@code /api/v1}.
 */
@RestController
@RequestMapping("/api/v1/healing/suggestions")
public class V1HealingSuggestionController extends AbstractV1Controller {

    private static final Logger log = LoggerFactory.getLogger(V1HealingSuggestionController.class);

    private final HealingService healingService;
    private final HealingSuggestionRepository suggestionRepository;

    public V1HealingSuggestionController(ProjectContextResolver contextResolver,
                                          HealingService healingService,
                                          HealingSuggestionRepository suggestionRepository) {
        super(contextResolver);
        this.healingService = healingService;
        this.suggestionRepository = suggestionRepository;
    }

    @GetMapping
    public ResponseEntity<List<HealingSuggestion>> list(
            @RequestParam(required = false) Long projectId) {
        log.info("GET /api/v1/healing/suggestions projectId={}", projectId);
        return ResponseEntity.ok(projectId != null
                ? suggestionRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                : suggestionRepository.findAll());
    }

    @PostMapping("/analyze/{executionId}")
    public ResponseEntity<HealingSuggestion> analyze(@PathVariable Long executionId) {
        log.info("POST /api/v1/healing/suggestions/analyze/{}", executionId);
        return ResponseEntity.ok(healingService.generateHealingSuggestion(executionId));
    }

    @PostMapping("/{suggestionId}/approve")
    public ResponseEntity<HealingSuggestion> approve(
            @PathVariable Long suggestionId,
            @RequestParam(required = false, defaultValue = "user") String approvedBy) {
        log.info("POST /api/v1/healing/suggestions/{}/approve", suggestionId);
        return ResponseEntity.ok(healingService.approveSuggestion(suggestionId, approvedBy));
    }

    @PostMapping("/{suggestionId}/reject")
    public ResponseEntity<HealingSuggestion> reject(
            @PathVariable Long suggestionId,
            @RequestParam(required = false, defaultValue = "user") String rejectedBy) {
        log.info("POST /api/v1/healing/suggestions/{}/reject", suggestionId);
        return ResponseEntity.ok(healingService.rejectSuggestion(suggestionId, rejectedBy));
    }

    /**
     * Applies the healed locator to the generated tests. This is the capability the v1
     * proposal surface has no equivalent for; see the class comment.
     */
    @PostMapping("/{suggestionId}/apply")
    public ResponseEntity<HealingSuggestion> apply(
            @PathVariable Long suggestionId,
            @RequestParam(required = false, defaultValue = "user") String appliedBy) {
        log.info("POST /api/v1/healing/suggestions/{}/apply", suggestionId);
        return ResponseEntity.ok(healingService.applySuggestion(suggestionId, appliedBy));
    }
}
