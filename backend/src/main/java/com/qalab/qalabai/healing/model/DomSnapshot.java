package com.qalab.qalabai.healing.model;

/**
 * A trimmed, bounded view of the page relevant to the failed locator. Keeps the
 * AI prompt small and avoids shipping the entire DOM to the provider.
 *
 * @param currentUrl    page URL
 * @param pageTitle     page title
 * @param relevantHtml  extracted elements + attributes (truncated to a budget)
 */
public record DomSnapshot(String currentUrl, String pageTitle, String relevantHtml) {

    public boolean isEmpty() {
        return relevantHtml == null || relevantHtml.isBlank();
    }
}
