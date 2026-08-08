package com.qalab.qalabai.healing.repository;

import com.qalab.qalabai.healing.model.HealingProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HealingProposalRepository extends JpaRepository<HealingProposal, Long> {

    Optional<HealingProposal> findByProposalId(String proposalId);

    List<HealingProposal> findByRunIdOrderByCreatedAtDesc(String runId);

    List<HealingProposal> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<HealingProposal> findFirstByRunIdOrderByCreatedAtDesc(String runId);
}
