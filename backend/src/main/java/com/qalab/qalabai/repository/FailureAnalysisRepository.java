package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.FailureAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FailureAnalysisRepository extends JpaRepository<FailureAnalysis, Long> {
    List<FailureAnalysis> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    Optional<FailureAnalysis> findByExecutionId(Long executionId);
}
