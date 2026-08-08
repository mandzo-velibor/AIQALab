package com.qalab.qalabai.api;

/**
 * Envelope for error responses:
 * <pre>{ "error": { "code": "...", "message": "...", "operationId": "..." } }</pre>
 */
public record ErrorResponse(ApiError error) {

    public static ErrorResponse of(ApiError error) {
        return new ErrorResponse(error);
    }
}
