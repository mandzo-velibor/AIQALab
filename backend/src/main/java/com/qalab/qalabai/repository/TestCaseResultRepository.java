package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.TestCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseResultRepository extends JpaRepository<TestCaseResult, Long> {

    List<TestCaseResult> findByExecutionIdOrderByOrdinalPositionAsc(Long executionId);

    List<TestCaseResult> findByExecutionIdAndStatusOrderByOrdinalPositionAsc(Long executionId, String status);

    void deleteByExecutionId(Long executionId);
}
