package com.qalab.qalabai.api.v1;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.api.ErrorCode;
import com.qalab.qalabai.api.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void invalidProjectContextMapsTo400() {
        ResponseEntity<ErrorResponse> response = handler.handleApiException(
                ApiException.invalidProjectContext("project is required"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(ErrorCode.INVALID_PROJECT_CONTEXT, response.getBody().error().code());
    }

    @Test
    void projectNotFoundMapsTo404() {
        ResponseEntity<ErrorResponse> response = handler.handleApiException(
                ApiException.projectNotFound("Project databaseId not found: 9"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(ErrorCode.PROJECT_NOT_FOUND, response.getBody().error().code());
    }

    @Test
    void budgetExceededMapsTo429() {
        ResponseEntity<ErrorResponse> response = handler.handleApiException(
                new ApiException(ErrorCode.AI_BUDGET_EXCEEDED, "budget exhausted"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    }

    @Test
    void providerUnavailableMapsTo503() {
        ResponseEntity<ErrorResponse> response = handler.handleApiException(
                new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "provider down"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void genericExceptionMapsTo500() {
        ResponseEntity<ErrorResponse> response = handler.handleGeneric(new IllegalStateException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(ErrorCode.INTERNAL_ERROR, response.getBody().error().code());
    }
}
