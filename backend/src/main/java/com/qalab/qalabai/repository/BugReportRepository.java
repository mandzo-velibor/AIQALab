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

    /**
     * Has this project already filed this failure? Always project-scoped: a shared
     * signature across accounts would leak one account's bugs into another's list.
     * A null project falls back to an unfiled report, so a run with no project is never
     * silently deduped against someone else's.
     */
    Optional<BugReport> findByProjectIdAndDedupKey(Long projectId, String dedupKey);

    List<BugReport> findByProjectIdAndDedupKeyIsNotNullOrderByOccurrencesDesc(Long projectId);
}
