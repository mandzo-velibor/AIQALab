package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.HealingSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HealingSuggestionRepository extends JpaRepository<HealingSuggestion, Long> {
    List<HealingSuggestion> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<HealingSuggestion> findByExecutionId(Long executionId);
    List<HealingSuggestion> findByStatus(String status);

    void deleteByProjectId(Long projectId);
}
