package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.LocatorHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocatorHistoryRepository extends JpaRepository<LocatorHistory, Long> {
    List<LocatorHistory> findByProjectId(Long projectId);
    Optional<LocatorHistory> findByProjectIdAndElementNameAndStatus(Long projectId, String elementName, String status);

    void deleteByProjectId(Long projectId);
}
