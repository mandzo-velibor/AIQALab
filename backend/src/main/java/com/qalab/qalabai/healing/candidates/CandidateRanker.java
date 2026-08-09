package com.qalab.qalabai.healing.candidates;

import com.qalab.qalabai.healing.model.LocatorCandidate;
import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.service.healing.LocatorSimilarityService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic candidate ranking applied before any AI evaluation. Strategy
 * weights are configurable via {@code qalab.healing.ranking}.
 */
@Component
@ConfigurationProperties(prefix = "qalab.healing.ranking")
public class CandidateRanker {

    private final LocatorSimilarityService similarityService;

    /** Weights per locator strategy; high = more stable/preferred. */
    private Map<String, Double> strategyWeights = new HashMap<>();

    private double strategyWeightFactor = 0.35;
    private double uniquenessFactor = 0.25;
    private double visibilityFactor = 0.15;
    private double enabledFactor = 0.10;
    private double similarityFactor = 0.15;

    public CandidateRanker(LocatorSimilarityService similarityService) {
        this.similarityService = similarityService;
        this.strategyWeights = defaultStrategyWeights();
    }

    private Map<String, Double> defaultStrategyWeights() {
        Map<String, Double> weights = new HashMap<>();
        weights.put(LocatorStrategy.TEST_ID.name(), 1.0);
        weights.put(LocatorStrategy.ROLE.name(), 0.9);
        weights.put(LocatorStrategy.LABEL.name(), 0.8);
        weights.put(LocatorStrategy.PLACEHOLDER.name(), 0.7);
        weights.put(LocatorStrategy.TEXT.name(), 0.65);
        weights.put(LocatorStrategy.NAME.name(), 0.6);
        weights.put(LocatorStrategy.ID.name(), 0.55);
        weights.put(LocatorStrategy.CSS.name(), 0.5);
        weights.put(LocatorStrategy.XPATH.name(), 0.3);
        return weights;
    }

    public List<LocatorCandidate> rank(List<LocatorCandidate> candidates, String originalLocator) {
        return candidates.stream()
                .map(c -> score(c, originalLocator))
                .sorted(Comparator.comparingDouble(LocatorCandidate::score).reversed())
                .toList();
    }

    private LocatorCandidate score(LocatorCandidate candidate, String originalLocator) {
        double strategyWeight = strategyWeight(candidate.strategy());
        double uniqueness = uniquenessScore(candidate);
        double visibility = candidate.visible() ? 1.0 : 0.0;
        double enabled = candidate.enabled() ? 1.0 : 0.0;
        double similarity = similarityService.similarity(originalLocator, candidate.locator());

        double score = strategyWeightFactor * strategyWeight
                + uniquenessFactor * uniqueness
                + visibilityFactor * visibility
                + enabledFactor * enabled
                + similarityFactor * similarity;
        score = Math.round(score * 100.0) / 100.0;

        String reason = candidate.reason()
                + " [unique=" + candidate.unique()
                + ", visible=" + candidate.visible()
                + ", enabled=" + candidate.enabled()
                + ", matches=" + candidate.matchedElementCount() + "]";
        return new LocatorCandidate(candidate.locator(), candidate.strategy(),
                score, candidate.unique(), candidate.visible(), candidate.enabled(),
                candidate.matchedElementCount(), reason);
    }

    private double strategyWeight(LocatorStrategy strategy) {
        Double weight = strategyWeights.get(strategy.name());
        return weight != null ? weight : 0.5;
    }

    private double uniquenessScore(LocatorCandidate candidate) {
        if (candidate.unique()) {
            return 1.0;
        }
        if (candidate.matchedElementCount() <= 0) {
            return 0.0;
        }
        return Math.max(0.0, 1.0 - (candidate.matchedElementCount() - 1) * 0.2);
    }

    public Map<String, Double> getStrategyWeights() {
        return strategyWeights;
    }

    public void setStrategyWeights(Map<String, Double> strategyWeights) {
        if (strategyWeights != null) {
            this.strategyWeights = strategyWeights;
        }
    }

    public double getStrategyWeightFactor() {
        return strategyWeightFactor;
    }

    public void setStrategyWeightFactor(double strategyWeightFactor) {
        this.strategyWeightFactor = strategyWeightFactor;
    }

    public double getUniquenessFactor() {
        return uniquenessFactor;
    }

    public void setUniquenessFactor(double uniquenessFactor) {
        this.uniquenessFactor = uniquenessFactor;
    }

    public double getVisibilityFactor() {
        return visibilityFactor;
    }

    public void setVisibilityFactor(double visibilityFactor) {
        this.visibilityFactor = visibilityFactor;
    }

    public double getEnabledFactor() {
        return enabledFactor;
    }

    public void setEnabledFactor(double enabledFactor) {
        this.enabledFactor = enabledFactor;
    }

    public double getSimilarityFactor() {
        return similarityFactor;
    }

    public void setSimilarityFactor(double similarityFactor) {
        this.similarityFactor = similarityFactor;
    }
}
