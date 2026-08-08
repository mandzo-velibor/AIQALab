package com.qalab.qalabai.api;

/**
 * Error codes returned by the public API.
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INVALID_PROJECT_CONTEXT = "INVALID_PROJECT_CONTEXT";
    public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String AI_BUDGET_EXCEEDED = "AI_BUDGET_EXCEEDED";
    public static final String AI_PROVIDER_UNAVAILABLE = "AI_PROVIDER_UNAVAILABLE";
    public static final String AI_PROVIDER_NOT_CONFIGURED = "AI_PROVIDER_NOT_CONFIGURED";
    public static final String AI_CREDENTIAL_INVALID = "AI_CREDENTIAL_INVALID";
    public static final String AI_OPERATION_NOT_ALLOWED = "AI_OPERATION_NOT_ALLOWED";
    public static final String AI_RATE_LIMITED = "AI_RATE_LIMITED";
    public static final String INVALID_PROVIDER = "INVALID_PROVIDER";
}
