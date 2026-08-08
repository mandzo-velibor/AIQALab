package com.qalab.qalabai.healing.classification;

import com.qalab.qalabai.healing.model.ExecutionTestResult;
import com.qalab.qalabai.healing.model.FailureClassification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic failure classification. Never calls AI.
 *
 * <p>Inspects error text, stack trace, action and locator signals to decide
 * whether a failure is (probably) a locator failure — the only kind the
 * self-healing pipeline acts on — or something else (assertion, network,
 * application error, plain timeout, unknown).</p>
 */
@Component
public class FailureClassifier {

    public record Classification(FailureClassification type, double confidence, String reason) {
    }

    private static final List<String> LOCATOR_SIGNALS = List.of(
            "waiting for", "getbyrole", "getbytext", "getbytestid", "getbyplaceholder",
            "getbylabel", "locator(", "page.locator", "locator.click", "locator.fill",
            "locator.check", "locator.press", "strict mode violation", "element not found",
            "no element found", "wasn't found", "could not be found", "resolve",
            "nth(", "intercepts", "expect(locator"
    );

    private static final List<String> ASSERTION_SIGNALS = List.of(
            "expect(", "expected", "expectation", "assertionerror",
            "tobevisible", "tohaveurl", "tobecount", "tocontaintext", "tobechecked",
            "expected value", "received", "matcher"
    );

    private static final List<String> NETWORK_SIGNALS = List.of(
            "net::", "econnrefused", "econnreset", "etimedout", "enotfound",
            "err_name_not_resolved", "failed to fetch", "fetch failed", "request failed",
            "network", "getaddrinfo", "ssl", "502 bad gateway", "504 gateway timeout",
            "err_connection", "connection refused"
    );

    private static final List<String> APPLICATION_ERROR_SIGNALS = List.of(
            "internal server error", "http 500", " 500 ", "application error",
            "unhandled error", "stack overflow", "nullpointerexception", "runtimeexception"
    );

    private static final List<String> TIMEOUT_SIGNALS = List.of(
            "timeout", "timed out", "exceeded"
    );

    public Classification classify(ExecutionTestResult result) {
        String text = searchableText(result);

        if (hasSignal(text, LOCATOR_SIGNALS)) {
            int hits = countSignals(text, LOCATOR_SIGNALS);
            double confidence = Math.min(0.99, 0.72 + hits * 0.07);
            return new Classification(FailureClassification.LOCATOR_FAILURE, round(confidence),
                    "The test failed while resolving/acting on an element using a locator"
                            + (result.locator() != null ? " (" + result.locator() + ")" : "") + ".");
        }
        if (hasSignal(text, ASSERTION_SIGNALS)) {
            double confidence = 0.86;
            return new Classification(FailureClassification.ASSERTION_FAILURE, confidence,
                    "The failure is an assertion mismatch, not a broken locator.");
        }
        if (hasSignal(text, NETWORK_SIGNALS)) {
            return new Classification(FailureClassification.NETWORK_FAILURE, 0.9,
                    "The failure points to a network-level error (connection, DNS, SSL).");
        }
        if (hasSignal(text, APPLICATION_ERROR_SIGNALS)) {
            return new Classification(FailureClassification.APPLICATION_ERROR, 0.8,
                    "The application itself reported an error.");
        }
        if (hasSignal(text, TIMEOUT_SIGNALS)) {
            return new Classification(FailureClassification.TIMEOUT, 0.7,
                    "The failure is a timeout without a clear locator cause.");
        }
        return new Classification(FailureClassification.UNKNOWN, 0.5,
                "Not enough signal to classify the failure. No healing attempted.");
    }

    private String searchableText(ExecutionTestResult result) {
        return join(result.error(), result.stackTrace(), result.action(),
                result.locator(), result.sourceCode(), result.consoleLogs());
    }

    private String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                sb.append(part).append("\n");
            }
        }
        return sb.toString().toLowerCase();
    }

    private boolean hasSignal(String text, List<String> signals) {
        for (String signal : signals) {
            if (text.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private int countSignals(String text, List<String> signals) {
        int count = 0;
        for (String signal : signals) {
            if (text.contains(signal)) {
                count++;
            }
        }
        return count;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
