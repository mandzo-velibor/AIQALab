package com.qalab.qalabai.healing.model;

import java.time.LocalDateTime;

/**
 * Raw, per-test execution result used as the input of the self-healing
 * pipeline. Mirrors what Playwright produces for a single test and supports
 * partial information (any field may be null).
 *
 * @param testId        stable test identity, when known
 * @param testName      human-readable test name
 * @param testFile      path of the test source file
 * @param status        PASSED / FAILED / SKIPPED / TIMEOUT / ERROR
 * @param action        the failed action, e.g. "locator.click" or "expect().toHaveURL"
 * @param locator       the locator used by the failed action
 * @param sourceCode    the test source around the failed line
 * @param sourceLine    the source line number of the failure
 * @param error         the error message (Playwright timeout / assertion text)
 * @param stackTrace    the failure stack trace
 * @param consoleLogs   console output captured during the test
 * @param screenshot    screenshot artifact reference
 * @param trace         trace artifact reference
 * @param video         video artifact reference
 * @param currentUrl    page URL at the time of the failure
 * @param pageTitle     page title at the time of the failure
 * @param domSnapshot   DOM snapshot / HTML captured at the time of the failure
 * @param timestamp     execution timestamp
 */
public record ExecutionTestResult(
        String testId,
        String testName,
        String testFile,
        String status,
        String action,
        String locator,
        String sourceCode,
        String sourceLine,
        String error,
        String stackTrace,
        String consoleLogs,
        String screenshot,
        String trace,
        String video,
        String currentUrl,
        String pageTitle,
        String domSnapshot,
        LocalDateTime timestamp
) {
}
