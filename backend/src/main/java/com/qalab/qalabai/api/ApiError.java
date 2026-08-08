package com.qalab.qalabai.api;

/**
 * Consistent error model for the public API.
 *
 * <pre>
 * {
 *   "error": {
 *     "code": "INVALID_REQUEST",
 *     "message": "...",
 *     "operationId": "..."
 *   }
 * }
 * </pre>
 */
public record ApiError(String code, String message, String operationId) {

    public static ApiError of(String code, String message, String operationId) {
        return new ApiError(code, message, operationId);
    }

    public static ApiError invalidRequest(String message, String operationId) {
        return new ApiError(ErrorCode.INVALID_REQUEST, message, operationId);
    }
}
