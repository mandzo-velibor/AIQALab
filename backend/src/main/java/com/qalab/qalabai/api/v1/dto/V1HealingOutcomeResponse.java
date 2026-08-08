package com.qalab.qalabai.api.v1.dto;

import com.qalab.qalabai.healing.service.HealingOutcome;

import java.util.List;

/**
 * Response of the healing analyze endpoint: the failure classification and,
 * when applicable, the generated proposal plus the evaluated candidates.
 */
public record V1HealingOutcomeResponse(
        String operationId,
        String status,
        String classification,
        double classificationConfidence,
        String classificationReason,
        boolean healingAttempted,
        String message,
        List<V1LocatorCandidateDto> candidates,
        V1HealingProposalResponse proposal
) {

    public static V1HealingOutcomeResponse from(String operationId, HealingOutcome outcome) {
        return new V1HealingOutcomeResponse(
                operationId,
                "COMPLETED",
                outcome.classification() != null ? outcome.classification().type() : "UNKNOWN",
                outcome.classification() != null ? outcome.classification().confidence() : 0.0,
                outcome.classification() != null ? outcome.classification().reason() : null,
                outcome.healingAttempted(),
                outcome.message(),
                V1LocatorCandidateDto.fromAll(outcome.candidates()),
                outcome.proposal() != null ? V1HealingProposalResponse.from(outcome.proposal()) : null
        );
    }
}
