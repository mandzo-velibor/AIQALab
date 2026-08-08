package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.ApiError;
import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.api.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Consistent error handling for the /api/v1 boundary. Legacy /api endpoints
 * are unaffected; they keep their own error handling for UI compatibility.
 */
@RestControllerAdvice(basePackages = "com.qalab.qalabai.api.v1")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        log.warn("API error {}: {}", e.getCode(), e.getMessage());
        HttpStatus status = statusFor(e.getCode());
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(ApiError.of(e.getCode(), e.getMessage(), e.getOperationId())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ApiError.invalidRequest(e.getMessage(), null)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        log.error("Unhandled API error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ApiError.of(ErrorCode.INTERNAL_ERROR,
                        e.getMessage() != null ? e.getMessage() : "Unknown error", null)));
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_PROJECT_CONTEXT, ErrorCode.INVALID_PROVIDER,
                    ErrorCode.AI_PROVIDER_NOT_CONFIGURED, ErrorCode.AI_OPERATION_NOT_ALLOWED ->
                    HttpStatus.BAD_REQUEST;
            case ErrorCode.PROJECT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCode.AI_BUDGET_EXCEEDED, ErrorCode.AI_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case ErrorCode.AI_CREDENTIAL_INVALID -> HttpStatus.UNAUTHORIZED;
            case ErrorCode.AI_PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
