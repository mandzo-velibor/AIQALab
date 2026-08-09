package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import com.qalab.qalabai.locator.intelligence.model.LocatorDiff;
import com.qalab.qalabai.service.healing.LocatorSimilarityService;
import org.springframework.stereotype.Component;

/**
 * Compares the currently used locator with a historical locator observed for
 * the same element fingerprint. Produces a human-readable diff that explains
 * whether the strategy changed, how semantically similar the locators are and
 * whether they most likely target the same element.
 */
@Component
public class LocatorComparator {

    private final LocatorStrategyDetector strategyDetector;
    private final LocatorSemanticAnalyzer semanticAnalyzer;
    private final LocatorSimilarityService similarityService;
    private final ElementFingerprintService fingerprintService;

    public LocatorComparator(LocatorStrategyDetector strategyDetector,
                             LocatorSemanticAnalyzer semanticAnalyzer,
                             LocatorSimilarityService similarityService,
                             ElementFingerprintService fingerprintService) {
        this.strategyDetector = strategyDetector;
        this.semanticAnalyzer = semanticAnalyzer;
        this.similarityService = similarityService;
        this.fingerprintService = fingerprintService;
    }

    public LocatorDiff compare(String currentLocator, String previousLocator,
                               ElementIdentity currentIdentity, ElementIdentity previousIdentity) {
        LocatorStrategy currentStrategy = strategyDetector.detect(currentLocator);
        LocatorStrategy previousStrategy = strategyDetector.detect(previousLocator);
        boolean strategyChanged = currentStrategy != previousStrategy;

        double semanticSimilarity = similarityService.similarity(currentLocator, previousLocator);
        boolean semanticChanged = semanticSimilarity < 0.6;

        double targetSimilarity;
        boolean targetLikelySame;
        if (currentIdentity != null && previousIdentity != null) {
            targetSimilarity = fingerprintService.matchConfidence(currentIdentity, previousIdentity);
            targetLikelySame = targetSimilarity >= 0.7;
        } else {
            targetSimilarity = semanticSimilarity;
            targetLikelySame = semanticSimilarity >= 0.7;
        }

        String recommendation = buildRecommendation(strategyChanged, targetLikelySame,
                targetSimilarity, currentStrategy, previousStrategy);

        return new LocatorDiff(
                currentLocator, previousLocator,
                strategyChanged,
                currentStrategy.name(), previousStrategy.name(),
                semanticChanged, semanticSimilarity,
                targetLikelySame, targetSimilarity,
                recommendation);
    }

    private String buildRecommendation(boolean strategyChanged, boolean targetLikelySame,
                                       double targetSimilarity, LocatorStrategy current,
                                       LocatorStrategy previous) {
        if (!targetLikelySame) {
            return "Locators most likely target different elements; verify the locator before changing it.";
        }
        if (!strategyChanged) {
            return "Same strategy, only the selector value changed. Follow the historical locator if the current one fails.";
        }
        if (rank(current) > rank(previous)) {
            return "The current strategy (" + current + ") is more stable than the historical one (" + previous + "). Keep it.";
        }
        return "The historical locator uses a more stable strategy (" + previous + " vs " + current
                + "); prefer it when the current locator fails.";
    }

    private int rank(LocatorStrategy strategy) {
        return switch (strategy) {
            case TEST_ID -> 9;
            case ROLE -> 8;
            case LABEL -> 7;
            case TEXT -> 6;
            case NAME -> 5;
            case ID -> 4;
            case PLACEHOLDER -> 3;
            case CSS -> 2;
            case XPATH -> 1;
        };
    }
}
