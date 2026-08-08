package com.qalab.qalabai.api;

/**
 * Base exception for API errors, carrying a stable error code and an optional
 * operation id so responses are consistent and traceable.
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final String operationId;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
        this.operationId = null;
    }

    public ApiException(String code, String message, String operationId) {
        super(message);
        this.code = code;
        this.operationId = operationId;
    }

    public ApiException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.operationId = null;
    }

    public static ApiException invalidRequest(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    public static ApiException invalidProjectContext(String message) {
        return new ApiException(ErrorCode.INVALID_PROJECT_CONTEXT, message);
    }

    public static ApiException projectNotFound(String message) {
        return new ApiException(ErrorCode.PROJECT_NOT_FOUND, message);
    }

    public static ApiException internal(String message, Throwable cause) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, message, cause);
    }

    public static ApiException aiBudgetExceeded(String message) {
        return new ApiException(ErrorCode.AI_BUDGET_EXCEEDED, message);
    }

    public static ApiException aiBudgetExceeded(String message, String operationId) {
        return new ApiException(ErrorCode.AI_BUDGET_EXCEEDED, message, operationId);
    }

    public static ApiException aiProviderUnavailable(String message) {
        return new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, message);
    }

    public static ApiException aiProviderUnavailable(String message, String operationId) {
        return new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, message, operationId);
    }

    public static ApiException aiProviderUnavailable(String message, String operationId, Throwable cause) {
        return new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, message, cause);
    }

    public static ApiException aiProviderNotConfigured(String message) {
        return new ApiException(ErrorCode.AI_PROVIDER_NOT_CONFIGURED, message);
    }

    public static ApiException aiProviderNotConfigured(String message, String operationId) {
        return new ApiException(ErrorCode.AI_PROVIDER_NOT_CONFIGURED, message, operationId);
    }

    public static ApiException aiCredentialInvalid(String message) {
        return new ApiException(ErrorCode.AI_CREDENTIAL_INVALID, message);
    }

    public static ApiException aiCredentialInvalid(String message, String operationId) {
        return new ApiException(ErrorCode.AI_CREDENTIAL_INVALID, message, operationId);
    }

    public static ApiException aiOperationNotAllowed(String message) {
        return new ApiException(ErrorCode.AI_OPERATION_NOT_ALLOWED, message);
    }

    public static ApiException aiRateLimited(String message) {
        return new ApiException(ErrorCode.AI_RATE_LIMITED, message);
    }

    public static ApiException aiRateLimited(String message, String operationId) {
        return new ApiException(ErrorCode.AI_RATE_LIMITED, message, operationId);
    }

    public String getCode() {
        return code;
    }

    public String getOperationId() {
        return operationId;
    }
}
