package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.GeneratedTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GeneratedTestRepository extends JpaRepository<GeneratedTest, Long> {

    List<GeneratedTest> findByPageUrl(String pageUrl);

    void deleteByProjectId(Long projectId);
}
