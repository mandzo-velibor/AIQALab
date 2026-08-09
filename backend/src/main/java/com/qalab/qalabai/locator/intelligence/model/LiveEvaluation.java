package com.qalab.qalabai.locator.intelligence.model;

/**
 * Result of resolving a locator against a live page via Playwright.
 *
 * @param count    how many elements the locator resolved to
 * @param visible  whether the (first) matched element was visible
 * @param enabled  whether the (first) matched element was enabled
 * @param identity stable element identity captured from the DOM, or null when
 *                 the locator did not resolve
 * @param error    browser/evaluation error message, or null on success
 */
public record LiveEvaluation(
        int count,
        boolean visible,
        boolean enabled,
        ElementIdentity identity,
        String error
) {

    public static LiveEvaluation failed(String error) {
        return new LiveEvaluation(0, false, false, null, error);
    }
}
