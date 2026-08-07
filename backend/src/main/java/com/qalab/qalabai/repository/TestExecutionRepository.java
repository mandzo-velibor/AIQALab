package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.TestExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestExecutionRepository extends JpaRepository<TestExecution, Long> {

    List<TestExecution> findByTestFile(String testFile);

    List<TestExecution> findByStatus(String status);

    List<TestExecution> findAllByOrderByCreatedAtDesc();

    List<TestExecution> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
