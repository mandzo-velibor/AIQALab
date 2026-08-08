package com.qalab.qalabai.healing.service;

import com.qalab.qalabai.api.ApiException;
import com.qalab.qalabai.healing.model.HealingProposal;
import com.qalab.qalabai.healing.model.HealingProposalStatus;
import com.qalab.qalabai.healing.repository.HealingProposalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence and lifecycle of healing proposals. Sprint 13 only creates
 * {@code PROPOSED} proposals and lets a human accept or reject them; automatic
 * application is intentionally out of scope.
 */
@Service
public class HealingProposalService {

    private static final Logger log = LoggerFactory.getLogger(HealingProposalService.class);

    private final HealingProposalRepository repository;

    public HealingProposalService(HealingProposalRepository repository) {
        this.repository = repository;
    }

    public HealingProposal create(HealingProposal proposal) {
        if (proposal.getProposalId() == null || proposal.getProposalId().isBlank()) {
            proposal.setProposalId("heal-" + UUID.randomUUID().toString().substring(0, 8));
        }
        proposal.setStatus(HealingProposalStatus.PROPOSED.name());
        proposal.setCreatedAt(LocalDateTime.now());
        HealingProposal saved = repository.save(proposal);
        log.info("Healing proposal {} created for run {} (recommended '{}', confidence {})",
                saved.getProposalId(), saved.getRunId(), saved.getRecommendedLocator(), saved.getConfidence());
        return saved;
    }

    public HealingProposal findByProposalId(String proposalId) {
        return repository.findByProposalId(proposalId)
                .orElseThrow(() -> ApiException.projectNotFound("Healing proposal not found: " + proposalId));
    }

    public List<HealingProposal> findByRunId(String runId) {
        return repository.findByRunIdOrderByCreatedAtDesc(runId);
    }

    public List<HealingProposal> findByProjectId(Long projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public HealingProposal accept(String proposalId) {
        return review(proposalId, HealingProposalStatus.ACCEPTED, "ACCEPTED");
    }

    public HealingProposal reject(String proposalId) {
        return review(proposalId, HealingProposalStatus.REJECTED, "REJECTED");
    }

    public HealingProposal markFailed(String proposalId) {
        return review(proposalId, HealingProposalStatus.FAILED, "FAILED");
    }

    private HealingProposal review(String proposalId, HealingProposalStatus status, String action) {
        HealingProposal proposal = findByProposalId(proposalId);
        proposal.setStatus(status.name());
        proposal.setReviewAction(action);
        proposal.setReviewedAt(LocalDateTime.now());
        return repository.save(proposal);
    }
}
