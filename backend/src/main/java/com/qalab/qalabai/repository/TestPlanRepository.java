package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.TestPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestPlanRepository extends JpaRepository<TestPlan, Long> {

    List<TestPlan> findByPageUrl(String pageUrl);

    List<TestPlan> findByPageType(String pageType);

    void deleteByProjectId(Long projectId);
}
