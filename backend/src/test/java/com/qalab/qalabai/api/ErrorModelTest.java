package com.qalab.qalabai.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ErrorModelTest {

    @Test
    void apiErrorCarriesCodeMessageAndOperationId() {
        ApiError error = ApiError.of(ErrorCode.INVALID_REQUEST, "bad payload", "op-1");
        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
        assertEquals("bad payload", error.message());
        assertEquals("op-1", error.operationId());
    }

    @Test
    void errorResponseEnvelopeMatchesContract() {
        ErrorResponse response = ErrorResponse.of(ApiError.of(ErrorCode.PROJECT_NOT_FOUND, "missing", "op-2"));
        assertEquals(ErrorCode.PROJECT_NOT_FOUND, response.error().code());
        assertEquals("op-2", response.error().operationId());
    }

    @Test
    void apiExceptionFactoriesUseStableCodes() {
        ApiException invalidRequest = ApiException.invalidRequest("nope");
        assertEquals(ErrorCode.INVALID_REQUEST, invalidRequest.getCode());
        assertNull(invalidRequest.getOperationId());

        ApiException badContext = ApiException.invalidProjectContext("project is required");
        assertEquals(ErrorCode.INVALID_PROJECT_CONTEXT, badContext.getCode());

        ApiException notFound = ApiException.projectNotFound("Project databaseId not found: 9");
        assertEquals(ErrorCode.PROJECT_NOT_FOUND, notFound.getCode());

        ApiException internal = ApiException.internal("boom", new RuntimeException());
        assertEquals(ErrorCode.INTERNAL_ERROR, internal.getCode());
    }
}
