package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.locator.intelligence.model.QualityScore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorQualityScorerTest {

    private final LocatorQualityScorer scorer = new LocatorQualityScorer();

    @Test
    void perfectLocatorScoresOneHundred() {
        QualityScore score = scorer.score(25, 25, 25, 15, 10);
        assertEquals(100.0, score.total(), 0.001);
    }

    @Test
    void scoresAreClampedToDimensions() {
        QualityScore score = scorer.score(30, 30, 30, 20, 12);
        assertEquals(25, score.uniqueness(), 0.001);
        assertEquals(25, score.semantic(), 0.001);
        assertEquals(25, score.stability(), 0.001);
        assertEquals(15, score.maintainability(), 0.001);
        assertEquals(10, score.resilience(), 0.001);
        assertEquals(100.0, score.total(), 0.001);
    }

    @Test
    void totalIsSumOfDimensions() {
        QualityScore score = scorer.score(20, 18, 22, 12, 8);
        assertEquals(80.0, score.total(), 0.001);
    }

    @Test
    void uniquenessFromCount() {
        assertEquals(25.0, scorer.uniquenessFromCount(1), 0.001);
        assertEquals(20.0, scorer.uniquenessFromCount(2), 0.001);
        assertEquals(0.0, scorer.uniquenessFromCount(0), 0.001);
        assertEquals(0.0, scorer.uniquenessFromCount(10), 0.001);
    }

    @Test
    void strategyBasedDimensionsFollowStabilityOrdering() {
        assertTrue(scorer.maintainability(LocatorStrategy.TEST_ID)
                > scorer.maintainability(LocatorStrategy.XPATH));
        assertTrue(scorer.resilience(LocatorStrategy.ROLE)
                > scorer.resilience(LocatorStrategy.CSS));
    }
}
