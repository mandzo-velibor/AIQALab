package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.HistoricalObservation;
import com.qalab.qalabai.locator.intelligence.model.LocatorObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes locator observations and derives the survival rate used by
 * the health classification.
 */
@Service
public class LocatorObservationService {

    private static final Logger log = LoggerFactory.getLogger(LocatorObservationService.class);

    private final LocatorObservationRepository repository;

    public LocatorObservationService(LocatorObservationRepository repository) {
        this.repository = repository;
    }

    public LocatorObservation record(LocatorObservation observation) {
        if (observation.getObservedAt() == null) {
            observation.setObservedAt(LocalDateTime.now());
        }
        if (observation.getStatus() == null) {
            observation.setStatus("RESOLVED");
        }
        LocatorObservation saved = repository.save(observation);
        log.info("Recorded locator observation {} for fingerprint {} ({}): {}",
                saved.getId(), saved.getElementFingerprint(), saved.getStrategy(), saved.getStatus());
        return saved;
    }

    public List<HistoricalObservation> history(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return repository.findByProjectIdOrderByObservedAtDesc(projectId).stream()
                .map(HistoricalObservation::from)
                .toList();
    }

    public List<HistoricalObservation> historyByFingerprint(Long projectId, String fingerprint) {
        if (projectId == null || fingerprint == null || fingerprint.isBlank()) {
            return List.of();
        }
        return repository.findByProjectIdAndElementFingerprintOrderByObservedAtDesc(projectId, fingerprint).stream()
                .map(HistoricalObservation::from)
                .toList();
    }

    public Optional<LocatorObservation> latestForFingerprint(Long projectId, String fingerprint) {
        if (projectId == null || fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        return repository.findFirstByProjectIdAndElementFingerprintOrderByObservedAtDesc(projectId, fingerprint);
    }

    /**
     * Survival rate in [0,1]: the fraction of observations where the locator
     * resolved. 1.0 when there is no history yet.
     */
    public double survivalRate(Long projectId, String fingerprint) {
        if (projectId == null || fingerprint == null || fingerprint.isBlank()) {
            return 1.0;
        }
        List<LocatorObservation> observations =
                repository.findByProjectIdAndElementFingerprintOrderByObservedAtDesc(projectId, fingerprint);
        if (observations.isEmpty()) {
            return 1.0;
        }
        long resolved = observations.stream().filter(o -> "RESOLVED".equals(o.getStatus())).count();
        return (double) resolved / observations.size();
    }

    public int observedCount(Long projectId, String fingerprint) {
        if (projectId == null || fingerprint == null || fingerprint.isBlank()) {
            return 0;
        }
        return (int) repository.countByProjectIdAndElementFingerprint(projectId, fingerprint);
    }
}
