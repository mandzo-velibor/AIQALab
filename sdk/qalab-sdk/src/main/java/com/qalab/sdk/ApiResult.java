package com.qalab.sdk;

/**
 * Result of an API call: the HTTP status code, the raw JSON payload and the
 * operation id echoed by the server (when present).
 */
public record ApiResult(int status, String body, String operationId) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isError() {
        return !isSuccess();
    }
}
