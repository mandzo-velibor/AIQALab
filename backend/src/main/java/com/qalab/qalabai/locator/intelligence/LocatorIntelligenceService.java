package com.qalab.qalabai.locator.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import com.qalab.qalabai.locator.intelligence.model.HistoricalObservation;
import com.qalab.qalabai.locator.intelligence.model.LiveEvaluation;
import com.qalab.qalabai.locator.intelligence.model.LocatorDiff;
import com.qalab.qalabai.locator.intelligence.model.LocatorHealth;
import com.qalab.qalabai.locator.intelligence.model.LocatorIntelligence;
import com.qalab.qalabai.locator.intelligence.model.LocatorObservation;
import com.qalab.qalabai.locator.intelligence.model.QualityScore;
import com.qalab.qalabai.locator.intelligence.model.SemanticResult;
import com.qalab.qalabai.locator.intelligence.model.StabilityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates a locator intelligence analysis (Sprint 14):
 *
 * <pre>
 * STRATEGY → STABILITY → LIVE UNIQUENESS → SEMANTIC → QUALITY → FINGERPRINT
 * → HISTORY/SURVIVAL → HEALTH → COMPARISON → OBSERVATION
 * </pre>
 *
 * <p>Everything downstream of the live evaluation is deterministic and
 * explainable. The result is persisted as an {@link LocatorObservation} to
 * build the historical evidence trail.</p>
 */
@Service
public class LocatorIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(LocatorIntelligenceService.class);

    private final LocatorStrategyDetector strategyDetector;
    private final LocatorStabilityAnalyzer stabilityAnalyzer;
    private final LocatorSemanticAnalyzer semanticAnalyzer;
    private final LocatorQualityScorer qualityScorer;
    private final LocatorHealthService healthService;
    private final ElementFingerprintService fingerprintService;
    private final LocatorComparator comparator;
    private final LocatorLiveEvaluator liveEvaluator;
    private final LocatorObservationService observationService;
    private final ObjectMapper objectMapper;

    public LocatorIntelligenceService(LocatorStrategyDetector strategyDetector,
                                      LocatorStabilityAnalyzer stabilityAnalyzer,
                                      LocatorSemanticAnalyzer semanticAnalyzer,
                                      LocatorQualityScorer qualityScorer,
                                      LocatorHealthService healthService,
                                      ElementFingerprintService fingerprintService,
                                      LocatorComparator comparator,
                                      LocatorLiveEvaluator liveEvaluator,
                                      LocatorObservationService observationService,
                                      ObjectMapper objectMapper) {
        this.strategyDetector = strategyDetector;
        this.stabilityAnalyzer = stabilityAnalyzer;
        this.semanticAnalyzer = semanticAnalyzer;
        this.qualityScorer = qualityScorer;
        this.healthService = healthService;
        this.fingerprintService = fingerprintService;
        this.comparator = comparator;
        this.liveEvaluator = liveEvaluator;
        this.observationService = observationService;
        this.objectMapper = objectMapper;
    }

    public LocatorIntelligence analyze(String url, String locator, Long projectId) {
        LocatorStrategy strategy = strategyDetector.detect(locator);
        StabilityResult stability = stabilityAnalyzer.analyze(locator);
        LiveEvaluation live = liveEvaluator.evaluate(url, locator);
        ElementIdentity identity = live.identity();

        SemanticResult semantic = semanticAnalyzer.analyze(locator, strategy, identity);
        double uniqueness = qualityScorer.uniquenessFromCount(live.count());
        double maintainability = qualityScorer.maintainability(strategy);
        double resilience = qualityScorer.resilience(strategy);
        QualityScore quality = qualityScorer.score(uniqueness, semantic.score(), stability.score(),
                maintainability, resilience);

        String fingerprint = identity != null ? fingerprintService.fingerprint(identity) : null;
        String uniquenessDetail = uniquenessDetail(live);

        List<HistoricalObservation> history = fingerprint != null
                ? observationService.historyByFingerprint(projectId, fingerprint)
                : List.of();
        double survivalRate = observationService.survivalRate(projectId, fingerprint);
        int observedCount = observationService.observedCount(projectId, fingerprint);

        LocatorHealth health = healthService.classify(stability.score(), survivalRate, observedCount, live.count() > 0);
        String healthReason = healthService.reason(health, stability.score(), survivalRate, observedCount, live.count() > 0);

        LocatorDiff comparison = buildComparison(projectId, locator, identity, fingerprint);

        if (live.error() == null) {
            persistObservation(url, locator, strategy, fingerprint, live, stability, semantic,
                    uniqueness, quality, health, projectId, identity);
        }

        return new LocatorIntelligence(
                url, locator, strategy.name(),
                stability.score(), stability.level().name(), stability.reasons(),
                semantic.score(), semantic.reason(),
                uniqueness, uniquenessDetail,
                live.visible(), live.enabled(), live.count(),
                maintainability, resilience, quality.total(),
                health.name(), healthReason,
                survivalRate, observedCount,
                fingerprint, comparison, history);
    }

    private LocatorDiff buildComparison(Long projectId, String locator, ElementIdentity identity, String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        Optional<LocatorObservation> previous = observationService.latestForFingerprint(projectId, fingerprint);
        if (previous.isEmpty() || previous.get().getLocator().equals(locator)) {
            return null;
        }
        ElementIdentity previousIdentity = deserializeIdentity(previous.get().getElementIdentityJson());
        return comparator.compare(locator, previous.get().getLocator(), identity, previousIdentity);
    }

    private void persistObservation(String url, String locator, LocatorStrategy strategy,
                                    String fingerprint, LiveEvaluation live,
                                    StabilityResult stability, SemanticResult semantic,
                                    double uniqueness, QualityScore quality, LocatorHealth health,
                                    Long projectId, ElementIdentity identity) {
        LocatorObservation observation = new LocatorObservation();
        observation.setProjectId(projectId);
        observation.setPageUrl(url);
        observation.setLocator(locator);
        observation.setStrategy(strategy.name());
        observation.setElementFingerprint(fingerprint);
        observation.setScore(quality.total());
        observation.setStabilityScore(stability.score());
        observation.setSemanticScore(semantic.score());
        observation.setUniqueness(uniqueness);
        observation.setHealth(health.name());
        observation.setStatus(live.count() > 0 ? "RESOLVED" : "FAILED");
        observation.setObservedAt(LocalDateTime.now());
        observation.setElementIdentityJson(serializeIdentity(identity));
        try {
            observationService.record(observation);
        } catch (Exception e) {
            log.warn("Failed to persist locator observation for '{}': {}", locator, e.getMessage());
        }
    }

    private String uniquenessDetail(LiveEvaluation live) {
        if (live.error() != null) {
            return "Live analysis unavailable: " + live.error();
        }
        if (live.count() <= 0) {
            return "The locator did not resolve to any element on the page.";
        }
        if (live.count() == 1) {
            return "Resolves to exactly one element.";
        }
        return "Resolves to " + live.count() + " elements - the locator is not unique.";
    }

    private String serializeIdentity(ElementIdentity identity) {
        if (identity == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (Exception e) {
            log.debug("Identity serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private ElementIdentity deserializeIdentity(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ElementIdentity.class);
        } catch (Exception e) {
            log.debug("Identity deserialization failed: {}", e.getMessage());
            return null;
        }
    }
}
