package com.qalab.qalabai.service.healing;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the Playwright locator expression that actually failed from an
 * execution's error message / console logs. The failure analyst only returns a
 * friendly element name (e.g. "Login Button"); the healing pipeline needs the
 * precise locator (e.g. getByRole('button', { name: 'Log in' })) to generate a
 * replacement that can be applied back into the test source.
 */
public final class FailedLocatorExtractor {

    private static final Pattern LOCATOR_PATTERN = Pattern.compile(
            "(?:page\\.)?(getByRole|getByLabel|getByText|getByPlaceholder|getByTestId|"
                    + "getByAltText|getByTitle|locator)\\([^)]*\\)",
            Pattern.CASE_INSENSITIVE);

    private FailedLocatorExtractor() {
    }

    public static Optional<String> extract(String errorMessage, String consoleLogs) {
        StringBuilder haystack = new StringBuilder();
        if (errorMessage != null) {
            haystack.append(errorMessage).append('\n');
        }
        if (consoleLogs != null) {
            haystack.append(consoleLogs).append('\n');
        }
        if (haystack.isEmpty()) {
            return Optional.empty();
        }

        Matcher matcher = LOCATOR_PATTERN.matcher(haystack);
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            String bare = candidate.startsWith("page.") ? candidate.substring("page.".length()) : candidate;
            if (bare.length() > 4) {
                return Optional.of(bare);
            }
        }
        return Optional.empty();
    }
}
