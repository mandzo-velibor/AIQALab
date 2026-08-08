package com.qalab.sdk;

/**
 * Thrown when the SDK cannot reach the API or the request fails.
 */
public class QalabSdkException extends RuntimeException {

    public QalabSdkException(String message) {
        super(message);
    }

    public QalabSdkException(String message, Throwable cause) {
        super(message, cause);
    }
}
