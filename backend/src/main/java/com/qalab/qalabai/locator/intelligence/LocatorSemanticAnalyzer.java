package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import com.qalab.qalabai.locator.intelligence.model.SemanticResult;
import org.springframework.stereotype.Component;

/**
 * Deterministic semantic quality scoring. Semantic locators describe intent
 * (role + accessible name, label, test id); presentational selectors (CSS,
 * XPath, placeholder) describe the DOM instead of the user-facing contract.
 */
@Component
public class LocatorSemanticAnalyzer {

    public SemanticResult analyze(String locator, LocatorStrategy strategy, ElementIdentity identity) {
        double score;
        String reason;

        switch (strategy) {
            case TEST_ID -> {
                score = 25.0;
                reason = "Data-testid is a dedicated, stable semantic anchor.";
            }
            case ROLE -> {
                score = 23.0;
                reason = "Role + accessible name expresses the user-facing contract.";
            }
            case LABEL -> {
                score = 21.0;
                reason = "Accessible label maps directly to the element's name.";
            }
            case TEXT -> {
                score = 16.0;
                reason = "Text content is semantic but can change during localization.";
            }
            case NAME -> {
                score = 13.0;
                reason = "Name attribute is stable for form controls but presentation-level.";
            }
            case ID -> {
                score = 11.0;
                reason = "Id is unique but is a DOM detail, not a semantic contract.";
            }
            case PLACEHOLDER -> {
                score = 9.0;
                reason = "Placeholder is presentational and may be removed.";
            }
            case CSS -> {
                score = 6.0;
                reason = "CSS selector describes the DOM structure, not the intent.";
            }
            case XPATH -> {
                score = 2.0;
                reason = "XPath depends on position and hierarchy; no semantics.";
            }
            default -> {
                score = 5.0;
                reason = "Unknown strategy.";
            }
        }

        if (identity != null && identity.testId() != null && !identity.testId().isBlank()
                && strategy != LocatorStrategy.TEST_ID) {
            score = Math.min(25.0, score + 2.0);
            reason = reason + " Element also exposes a data-testid; prefer getByTestId('" + identity.testId() + "').";
        }

        return new SemanticResult(round(score), reason);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
