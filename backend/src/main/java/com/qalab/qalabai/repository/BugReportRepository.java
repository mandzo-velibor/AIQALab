package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.BugReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BugReportRepository extends JpaRepository<BugReport, Long> {

    Optional<BugReport> findByReportId(String reportId);

    List<BugReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<BugReport> findByExecutionIdOrderByCreatedAtDesc(Long executionId);

    List<BugReport> findAllByOrderByCreatedAtDesc();
}
