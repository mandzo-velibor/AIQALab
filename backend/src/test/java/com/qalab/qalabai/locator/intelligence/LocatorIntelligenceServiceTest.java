package com.qalab.qalabai.locator.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import com.qalab.qalabai.locator.intelligence.model.LiveEvaluation;
import com.qalab.qalabai.locator.intelligence.model.LocatorIntelligence;
import com.qalab.qalabai.locator.intelligence.model.LocatorObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocatorIntelligenceServiceTest {

    private LocatorLiveEvaluator liveEvaluator;
    private LocatorObservationService observationService;
    private LocatorIntelligenceService service;

    @BeforeEach
    void setUp() {
        liveEvaluator = mock(LocatorLiveEvaluator.class);
        observationService = mock(LocatorObservationService.class);
        service = new LocatorIntelligenceService(
                new LocatorStrategyDetector(),
                new LocatorStabilityAnalyzer(),
                new LocatorSemanticAnalyzer(),
                new LocatorQualityScorer(),
                new LocatorHealthService(),
                new ElementFingerprintService(),
                new LocatorComparator(new LocatorStrategyDetector(), new LocatorSemanticAnalyzer(),
                        new com.qalab.qalabai.service.healing.LocatorSimilarityService(),
                        new ElementFingerprintService()),
                liveEvaluator,
                observationService,
                new ObjectMapper());
    }

    @Test
    void analyzesAndComposesDeterministicFields() {
        ElementIdentity identity = new ElementIdentity("https://x.dev", "button", "button",
                "Log in", "Log in", "login-btn", null, null, null, null, null, "btn",
                new LinkedHashMap<>(), true, true);
        when(liveEvaluator.evaluate("https://x.dev", "getByTestId('login-btn')"))
                .thenReturn(new LiveEvaluation(1, true, true, identity, null));
        when(observationService.historyByFingerprint(any(), any())).thenReturn(List.of());
        when(observationService.survivalRate(any(), any())).thenReturn(1.0);
        when(observationService.observedCount(any(), any())).thenReturn(0);

        LocatorIntelligence result = service.analyze("https://x.dev", "getByTestId('login-btn')", 7L);

        assertEquals("TEST_ID", result.strategy());
        assertEquals(25.0, result.stabilityScore(), 0.001);
        assertEquals(25.0, result.semanticScore(), 0.001);
        assertEquals(25.0, result.uniqueness(), 0.001);
        assertEquals(1, result.matchedElementCount());
        assertTrue(result.visible());
        assertTrue(result.enabled());
        assertEquals(100.0, result.overallScore(), 0.001);
        assertNotNull(result.elementFingerprint());
        assertTrue(result.elementFingerprint().startsWith("fp-"));

        verify(observationService).record(any(LocatorObservation.class));
    }

    @Test
    void unresolvedLocatorScoresZeroAndPersistsFailed() {
        when(liveEvaluator.evaluate("https://x.dev", "locator('#ghost')"))
                .thenReturn(new LiveEvaluation(0, false, false, null, null));
        when(observationService.historyByFingerprint(any(), any())).thenReturn(List.of());
        when(observationService.survivalRate(any(), any())).thenReturn(1.0);
        when(observationService.observedCount(any(), any())).thenReturn(0);

        LocatorIntelligence result = service.analyze("https://x.dev", "locator('#ghost')", 7L);

        assertEquals(0.0, result.uniqueness(), 0.001);
        assertNull(result.elementFingerprint());
        assertEquals("BROKEN", result.health());
        verify(observationService).record(any(LocatorObservation.class));
    }

    @Test
    void browserErrorSkipsPersistence() {
        when(liveEvaluator.evaluate("https://x.dev", "locator('#ghost')"))
                .thenReturn(LiveEvaluation.failed("chromium not installed"));
        when(observationService.historyByFingerprint(any(), any())).thenReturn(List.of());
        when(observationService.survivalRate(any(), any())).thenReturn(1.0);
        when(observationService.observedCount(any(), any())).thenReturn(0);

        LocatorIntelligence result = service.analyze("https://x.dev", "locator('#ghost')", 7L);

        assertTrue(result.uniquenessDetail().contains("unavailable"));
        verify(observationService, never()).record(any(LocatorObservation.class));
    }

    @Test
    void comparisonWithHistoryIsProduced() {
        ElementIdentity identity = new ElementIdentity("https://x.dev", "button", "button",
                "Sign in", "Sign in", "sign-in-btn", null, null, null, null, null, "btn",
                new LinkedHashMap<>(), true, true);
        when(liveEvaluator.evaluate("https://x.dev", "getByTestId('sign-in-btn')"))
                .thenReturn(new LiveEvaluation(1, true, true, identity, null));

        LocatorObservation previous = new LocatorObservation();
        previous.setLocator("getByRole('button', { name: 'Sign in' })");
        previous.setStatus("RESOLVED");
        previous.setElementIdentityJson("{\"tag\":\"button\",\"testId\":\"sign-in-btn\"}");

        when(observationService.latestForFingerprint(any(Long.class), any(String.class)))
                .thenReturn(Optional.of(previous));
        when(observationService.historyByFingerprint(any(), any())).thenReturn(List.of());
        when(observationService.survivalRate(any(), any())).thenReturn(1.0);
        when(observationService.observedCount(any(), any())).thenReturn(0);

        LocatorIntelligence result = service.analyze("https://x.dev", "getByTestId('sign-in-btn')", 7L);

        assertNotNull(result.comparison());
        assertTrue(result.comparison().strategyChanged());
        assertTrue(result.comparison().targetLikelySame());
    }
}
