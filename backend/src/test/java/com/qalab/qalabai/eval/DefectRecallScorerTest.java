package com.qalab.qalabai.eval;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

/**
 * The scoring decision table decides the number every prompt change is judged on, so it
 * is tested exhaustively rather than through one happy path.
 *
 * <p>Two failure modes would be invisible without these: a false positive counted as a
 * detection (which inflates recall while the suite is simply wrong), and a skipped test
 * counted as a miss (which punishes a suite for a reason that is not its quality).</p>
 */
class DefectRecallScorerTest {

    private Map<String, String> statuses(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private Map<String, String> defectOf(String... pairs) {
        return statuses(pairs);
    }

    // ---- the decision table ----

    @Test
    void failingOnDefectAndPassingOnCorrectIsADetection() {
        assertEquals(DefectRecallScorer.Outcome.DETECTED,
                DefectRecallScorer.classify("failed", "passed"));
    }

    @Test
    void passingOnBothIsAMiss() {
        assertEquals(DefectRecallScorer.Outcome.MISSED,
                DefectRecallScorer.classify("passed", "passed"));
    }

    @Test
    void failingOnBothIsAFalsePositive() {
        // The case that would silently inflate recall: the suite objects to a page that
        // is actually correct, so it is not detecting the defect, it is just noisy.
        assertEquals(DefectRecallScorer.Outcome.FALSE_POSITIVE,
                DefectRecallScorer.classify("failed", "failed"));
    }

    @Test
    void aTimeoutCountsAsAFailure() {
        // A defect that makes the page hang is still detected by a test that times out.
        assertEquals(DefectRecallScorer.Outcome.DETECTED,
                DefectRecallScorer.classify("timedOut", "passed"));
    }

    @Test
    void anInterruptionCountsAsAFailure() {
        assertEquals(DefectRecallScorer.Outcome.DETECTED,
                DefectRecallScorer.classify("interrupted", "passed"));
    }

    @Test
    void aSkippedTestIsNotAMiss() {
        // "Did not run" and "ran and found nothing" are opposite findings.
        assertEquals(DefectRecallScorer.Outcome.NOT_RUN,
                DefectRecallScorer.classify("skipped", "passed"));
    }

    @Test
    void aTestMissingFromTheDefectiveRunIsNotRun() {
        assertEquals(DefectRecallScorer.Outcome.NOT_RUN,
                DefectRecallScorer.classify(null, "passed"));
    }

    @Test
    void aTestMissingFromTheCorrectRunIsInconclusiveNotAFalsePositive() {
        // We never saw it pass, so we cannot say the suite is wrong about a good page —
        // we can only say we do not know.
        assertEquals(DefectRecallScorer.Outcome.INCONCLUSIVE,
                DefectRecallScorer.classify("failed", null));
        assertEquals(DefectRecallScorer.Outcome.INCONCLUSIVE,
                DefectRecallScorer.classify("failed", "skipped"));
    }

    // ---- aggregate scoring ----

    @Test
    void recallIsTheShareOfPlantedDefectsFound() {
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses(
                        "t1", "failed",   // missing-label: caught
                        "t2", "failed",   // wrong-role: caught
                        "t3", "passed",   // xss: missed
                        "t4", "passed"),  // no-error-state: missed
                statuses(
                        "t1", "passed", "t2", "passed", "t3", "passed", "t4", "passed"),
                defectOf("t1", "missing-label", "t2", "wrong-role",
                        "t3", "xss", "t4", "no-error-state"),
                null);

        assertEquals(0.5, score.defectRecall(), 0.0001, "two of four defects found");
        assertEquals(List.of("xss", "no-error-state"), score.missedDefects());
    }

    @Test
    void aDefectIsFoundWhenAnyOfItsTestsFindsIt() {
        // A category can be covered by more than one assertion; one hit is enough.
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses("t1", "passed", "t2", "failed"),
                statuses("t1", "passed", "t2", "passed"),
                defectOf("t1", "wrong-role", "t2", "wrong-role"),
                null);

        assertEquals(1.0, score.defectRecall(), 0.0001);
        assertEquals(1, score.defects().get(0).detectionCount());
    }

    @Test
    void aFalsePositiveIsNotCountedAsADetection() {
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses("t1", "failed"),
                statuses("t1", "failed"),
                defectOf("t1", "xss"),
                null);

        assertEquals(0.0, score.defectRecall(), 0.0001,
                "a test that fails on a correct page has not found anything");
        assertEquals(1.0, score.falsePositiveRate(), 0.0001);
        assertEquals(List.of("t1"), score.falsePositiveTests());
    }

    @Test
    void aSkippedTestIsExcludedFromTheFalsePositiveDenominator() {
        // Otherwise a suite that skips half its tests looks clean by not running.
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses("t1", "failed", "t2", "skipped"),
                statuses("t1", "passed", "t2", "skipped"),
                defectOf("t1", "xss", "t2", "other"),
                null);

        assertEquals(1, score.notRun());
        assertEquals(0.0, score.falsePositiveRate(), 0.0001,
                "the skipped test must not dilute the rate");
    }

    @Test
    void flakesAreCountedAndNeverRewarded() {
        // A test that failed once and passed on retry has not demonstrated a detection.
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses("t1", "passed", "t2", "passed"),
                statuses("t1", "passed", "t2", "passed"),
                defectOf("t1", "xss", "t2", "other"),
                Map.of("t1", new int[]{2}));

        assertEquals(1, score.flakyTests());
        assertEquals(0.5, score.flakeRate(), 0.0001);
        assertEquals(0.0, score.defectRecall(), 0.0001,
                "instability must not be mistaken for a detection");
    }

    @Test
    void anEmptyRunScoresZeroRatherThanDividingByZero() {
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                Map.of(), Map.of(), Map.of(), null);

        assertEquals(0.0, score.defectRecall());
        assertEquals(0.0, score.falsePositiveRate());
        assertEquals(0.0, score.flakeRate());
    }

    @Test
    void aTestWithNoDefectMappingIsGroupedRatherThanDropped() {
        // Losing a test silently would understate the denominator and inflate recall.
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses("t1", "failed"),
                statuses("t1", "passed"),
                Map.of(),
                null);

        assertEquals(1, score.defects().size());
        assertEquals("unknown", score.defects().get(0).defectId());
    }

    @Test
    void aPerfectSuiteScoresFullRecallWithNoFalsePositives() {
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses("t1", "failed", "t2", "failed", "t3", "failed"),
                statuses("t1", "passed", "t2", "passed", "t3", "passed"),
                defectOf("t1", "a", "t2", "b", "t3", "c"),
                null);

        assertEquals(1.0, score.defectRecall(), 0.0001);
        assertEquals(0.0, score.falsePositiveRate(), 0.0001);
        assertEquals(0.0, score.flakeRate(), 0.0001);
        assertTrue(score.missedDefects().isEmpty());
    }

    @Test
    void aSuiteThatAssertsNothingScoresZeroRecall() {
        // The failure mode this whole task exists to catch: "tests ran" is not "tests
        // found anything", and a harness that reports the first as the second is worse
        // than no harness, because it manufactures confidence.
        DefectRecallScorer.Score score = DefectRecallScorer.score(
                statuses("t1", "passed", "t2", "passed"),
                statuses("t1", "passed", "t2", "passed"),
                defectOf("t1", "a", "t2", "b"),
                null);

        assertEquals(0.0, score.defectRecall(), 0.0001);
        assertEquals(2, score.totalTests());
        assertEquals(2, score.missedDefects().size());
    }
}
