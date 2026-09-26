package com.qalab.qalabai.api.v1.dto;

import java.time.LocalDateTime;

/**
 * Acknowledgement that an asynchronous workflow was accepted.
 *
 * <p>Returned with {@code 202 Accepted}. The client polls {@link #statusUrl} for the
 * result; {@code /progress} remains available for the live stage.</p>
 */
public record V1WorkflowAccepted(
        String operationId,
        String status,
        String statusUrl,
        String message
) {
}
