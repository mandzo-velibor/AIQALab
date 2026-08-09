package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.healing.model.LocatorStrategy;
import org.springframework.stereotype.Component;

/**
 * Classifies the strategy used by a Playwright locator expression.
 * Deterministic, order matters (most specific first).
 */
@Component
public class LocatorStrategyDetector {

    public LocatorStrategy detect(String locator) {
        if (locator == null || locator.isBlank()) {
            return LocatorStrategy.CSS;
        }
        String lower = locator.toLowerCase();

        if (lower.contains("getbytestid") || lower.contains("data-testid")
                || lower.contains("data-test=") || lower.contains("data-cy")) {
            return LocatorStrategy.TEST_ID;
        }
        if (lower.contains("getbyrole")) {
            return LocatorStrategy.ROLE;
        }
        if (lower.contains("getbylabel")) {
            return LocatorStrategy.LABEL;
        }
        if (lower.contains("getbyplaceholder")) {
            return LocatorStrategy.PLACEHOLDER;
        }
        if (lower.contains("getbytext") || lower.contains("getbyalttext") || lower.contains("getbytitle")) {
            return LocatorStrategy.TEXT;
        }
        if (lower.contains("[name=")) {
            return LocatorStrategy.NAME;
        }
        if (lower.startsWith("xpath") || lower.startsWith("//") || lower.startsWith("/html")
                || lower.contains("xpath=")) {
            return LocatorStrategy.XPATH;
        }
        if (lower.contains("#")) {
            return LocatorStrategy.ID;
        }
        if (lower.contains(".") || lower.contains("[")) {
            return LocatorStrategy.CSS;
        }
        return LocatorStrategy.CSS;
    }
}
