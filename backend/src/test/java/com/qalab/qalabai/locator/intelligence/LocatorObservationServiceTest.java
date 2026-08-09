package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.LocatorObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocatorObservationServiceTest {

    private LocatorObservationRepository repository;
    private LocatorObservationService service;

    @BeforeEach
    void setUp() {
        repository = mock(LocatorObservationRepository.class);
        service = new LocatorObservationService(repository);
    }

    @Test
    void recordFillsDefaults() {
        LocatorObservation observation = new LocatorObservation();
        observation.setProjectId(1L);
        observation.setPageUrl("https://x.dev");
        observation.setLocator("getByTestId('login')");
        observation.setStrategy("TEST_ID");
        observation.setElementFingerprint("fp-abc");
        observation.setScore(80.0);
        observation.setStabilityScore(20.0);
        observation.setSemanticScore(25.0);
        observation.setUniqueness(25.0);
        observation.setHealth("HEALTHY");

        when(repository.save(any(LocatorObservation.class))).thenAnswer(inv -> inv.getArgument(0));

        LocatorObservation saved = service.record(observation);

        assertEquals("RESOLVED", saved.getStatus());
        assertNotNull(saved.getObservedAt());
        verify(repository).save(observation);
    }

    @Test
    void historyIsEmptyWithoutProject() {
        assertTrue(service.history(null).isEmpty());
        assertTrue(service.historyByFingerprint(null, "fp-abc").isEmpty());
    }

    @Test
    void survivalRateIsFractionOfResolved() {
        when(repository.findByProjectIdAndElementFingerprintOrderByObservedAtDesc(1L, "fp-abc"))
                .thenReturn(List.of(observation("RESOLVED"), observation("RESOLVED"), observation("FAILED")));

        assertEquals(2.0 / 3.0, service.survivalRate(1L, "fp-abc"), 0.001);
    }

    @Test
    void survivalRateIsOneWithoutHistory() {
        when(repository.findByProjectIdAndElementFingerprintOrderByObservedAtDesc(1L, "fp-abc"))
                .thenReturn(List.of());
        assertEquals(1.0, service.survivalRate(1L, "fp-abc"), 0.001);
    }

    private LocatorObservation observation(String status) {
        LocatorObservation o = new LocatorObservation();
        o.setLocator("getByTestId('login')");
        o.setStatus(status);
        return o;
    }
}
