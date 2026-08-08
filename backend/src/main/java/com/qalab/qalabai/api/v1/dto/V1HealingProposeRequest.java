package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.healing.model.FailureContext;

/**
 * Request for locator-healing analysis. Either an {@code executionId} of a
 * persisted run or a fully supplied {@link FailureContext} is required.
 */
public record V1HealingProposeRequest(
        ProjectInfo project,
        Long executionId,
        FailureContext failure
) {
}
