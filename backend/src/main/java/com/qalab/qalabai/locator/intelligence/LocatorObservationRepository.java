package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.LocatorObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for locator observations. Observations are the evidence trail
 * behind the survival rate and health classification.
 */
public interface LocatorObservationRepository extends JpaRepository<LocatorObservation, Long> {

    List<LocatorObservation> findByProjectIdOrderByObservedAtDesc(Long projectId);

    List<LocatorObservation> findByProjectIdAndElementFingerprintOrderByObservedAtDesc(
            Long projectId, String elementFingerprint);

    Optional<LocatorObservation> findFirstByProjectIdAndElementFingerprintOrderByObservedAtDesc(
            Long projectId, String elementFingerprint);

    long countByProjectIdAndElementFingerprint(Long projectId, String elementFingerprint);
}
