package com.qalab.qalabai.api.v1.dto;

/**
 * Request body for {@code POST /api/v1/locators/analyze}.
 *
 * @param url       page URL where the locator should be evaluated live
 * @param locator   Playwright locator expression to analyze
 * @param projectId optional registered project id, used for historical evidence
 */
public record V1LocatorAnalyzeRequest(String url, String locator, Long projectId) {
}
