package com.qalab.qalabai.healing.service;

import com.qalab.qalabai.healing.model.FailureContext;
import com.qalab.qalabai.healing.model.HealingProposal;
import com.qalab.qalabai.healing.model.LocatorCandidate;

import java.util.List;

/**
 * Result of running the self-healing pipeline for one failed test.
 * {@code proposal} is null when the failure is not a locator failure or when no
 * safe candidate was found — the {@code message} explains why.
 */
public record HealingOutcome(
        FailureContext context,
        FailureClassificationView classification,
        List<LocatorCandidate> candidates,
        HealingProposal proposal,
        boolean healingAttempted,
        String message
) {

    public boolean isProposalCreated() {
        return proposal != null;
    }

    public record FailureClassificationView(String type, double confidence, String reason) {
    }
}
