package com.qalab.qalabai.repository;

import com.qalab.qalabai.model.LocatorDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LocatorRepository extends JpaRepository<LocatorDefinition, Long> {

    List<LocatorDefinition> findByPageUrl(String pageUrl);

    List<LocatorDefinition> findByElementType(String elementType);

    List<LocatorDefinition> findByConfidenceGreaterThanEqual(Integer confidence);
}
