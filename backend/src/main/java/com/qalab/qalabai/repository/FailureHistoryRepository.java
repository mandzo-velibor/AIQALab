package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.FailureHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FailureHistoryRepository extends JpaRepository<FailureHistory, Long> {
    List<FailureHistory> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
