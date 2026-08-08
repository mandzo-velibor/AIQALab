package com.qalab.qalabai.healing.model;

/**
 * Outcome of deterministic failure classification. Only {@link #LOCATOR_FAILURE}
 * failures qualify for locator self-healing.
 */
public enum FailureClassification {
    LOCATOR_FAILURE,
    ASSERTION_FAILURE,
    TIMEOUT,
    NETWORK_FAILURE,
    APPLICATION_ERROR,
    UNKNOWN
}
