package com.qalab.qalabai.healing.model;

/**
 * Lifecycle of a healing proposal. Sprint 13 only creates proposals in
 * {@link #PROPOSED} state; human review moves it to ACCEPTED / REJECTED.
 * Applied / Failed are reserved for a future automatic-apply sprint.
 */
public enum HealingProposalStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    APPLIED,
    FAILED
}
