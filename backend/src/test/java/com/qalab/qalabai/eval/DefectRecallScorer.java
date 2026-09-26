package com.qalab.qalabai.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns two Playwright runs into a defect-recall score.
 *
 * <p>The measurement rests on one idea: <strong>one defect per fixture, with a correct
 * twin</strong>. Running a suite twice — once against the defective pages, once against
 * their correct twins — separates the three outcomes that matter and cannot be told apart
 * otherwise:</p>
 *
 * <ul>
 *   <li>fails on defective, passes on correct → the suite <strong>caught the defect</strong></li>
 *   <li>fails on both → a <strong>false positive</strong>: the suite is wrong about a
 *       working page, and a suite that cries wolf is worse than one that says nothing</li>
 *   <li>passes on both → the defect was <strong>missed</strong></li>
 * </ul>
 *
 * <p>Scoring only "did the tests run" measures nothing: a suite that asserts nothing
 * passes everywhere. Recall against planted, documented defects is the only number here
 * that says whether the tool is worth using.</p>
 *
 * <p>Flakes are counted separately and never counted as detections. A test that failed
 * once and passed on retry is not evidence the tool found a bug, and letting it count
 * would make the score flattered by instability.</p>
 */
public final class DefectRecallScorer {

    /** How a single oracle test performed across the two runs. */
    public enum Outcome {
        /** Failed on the defective page and passed on the correct twin. */
        DETECTED,
        /** Passed on both: the defect was not found. */
        MISSED,
        /** Failed on the correct twin: the suite is wrong about a working page. */
        FALSE_POSITIVE,
        /** Failed on the defective page but never passed on the correct twin. */
        INCONCLUSIVE,
        /** The run never executed this test, so nothing can be concluded. */
        NOT_RUN
    }

    public record TestOutcome(String testId, String defectId, Outcome outcome, int retries) {

        public boolean countsAsDetection() {
            return outcome == Outcome.DETECTED;
        }
    }

    public record DefectScore(String defectId, List<TestOutcome> tests) {

        /** A defect counts as found when at least one of its oracle tests detected it. */
        public boolean detected() {
            return tests.stream().anyMatch(TestOutcome::countsAsDetection);
        }

        public int detectionCount() {
            return (int) tests.stream().filter(TestOutcome::countsAsDetection).count();
        }
    }

    public record Score(
            List<DefectScore> defects,
            int totalTests,
            int detectedTests,
            int falsePositives,
            int missedTests,
            int inconclusive,
            int notRun,
            int flakyTests
    ) {

        /**
         * Share of planted defects that at least one test found. This is the headline
         * number and the one a prompt change is judged on.
         */
        public double defectRecall() {
            long planted = defects.stream().filter(d -> !d.tests().isEmpty()).count();
            if (planted == 0) {
                return 0.0;
            }
            return (double) defects.stream().filter(DefectScore::detected).count() / planted;
        }

        /** Share of tests that objected to a page that is actually correct. */
        public double falsePositiveRate() {
            int decided = totalTests - notRun;
            if (decided == 0) {
                return 0.0;
            }
            return (double) falsePositives / decided;
        }

        /** Tests that needed a retry. Reported, never rewarded. */
        public double flakeRate() {
            return totalTests == 0 ? 0.0 : (double) flakyTests / totalTests;
        }

        public List<String> missedDefects() {
            return defects.stream()
                    .filter(d -> !d.tests().isEmpty())
                    .filter(d -> !d.detected())
                    .map(DefectScore::defectId)
                    .toList();
        }

        public List<String> falsePositiveTests() {
            return defects.stream()
                    .flatMap(d -> d.tests().stream())
                    .filter(t -> t.outcome() == Outcome.FALSE_POSITIVE)
                    .map(TestOutcome::testId)
                    .toList();
        }
    }

    private DefectRecallScorer() {
    }

    /**
     * @param defectiveStatuses test id → status from the run against the defective pages
     * @param correctStatuses   test id → status from the run against the correct twins
     * @param defectByTest      test id → planted defect id
     * @param retriesByTest     test id → retries the run needed
     */
    public static Score score(Map<String, String> defectiveStatuses,
                              Map<String, String> correctStatuses,
                              Map<String, String> defectByTest,
                              Map<String, int[]> retriesByTest) {
        Map<String, List<TestOutcome>> byDefect = new LinkedHashMap<>();
        int falsePositives = 0;
        int detected = 0;
        int missed = 0;
        int inconclusive = 0;
        int notRun = 0;
        int flaky = 0;
        int total = 0;

        for (Map.Entry<String, String> entry : defectiveStatuses.entrySet()) {
            String testId = entry.getKey();
            String defectId = defectByTest.getOrDefault(testId, "unknown");
            String defective = entry.getValue();
            String correct = correctStatuses.get(testId);
            int[] retries = retriesByTest == null ? null : retriesByTest.get(testId);
            int retryCount = retries == null ? 0 : retries[0];

            Outcome outcome = classify(defective, correct);
            byDefect.computeIfAbsent(defectId, key -> new ArrayList<>())
                    .add(new TestOutcome(testId, defectId, outcome, retryCount));

            total++;
            if (retryCount > 0) {
                flaky++;
            }
            switch (outcome) {
                case DETECTED -> detected++;
                case MISSED -> missed++;
                case FALSE_POSITIVE -> falsePositives++;
                case INCONCLUSIVE -> inconclusive++;
                case NOT_RUN -> notRun++;
            }
        }

        List<DefectScore> defects = byDefect.entrySet().stream()
                .map(e -> new DefectScore(e.getKey(), List.copyOf(e.getValue())))
                .toList();

        return new Score(defects, total, detected, falsePositives, missed, inconclusive, notRun, flaky);
    }

    /**
     * The decision table, isolated because it is the whole measurement.
     *
     * <p>{@code NOT_RUN} is kept distinct from {@code MISSED} on purpose. "The suite did
     * not execute this test" and "the suite executed it and found nothing" are opposite
     * findings, and collapsing them would let a suite that silently skipped its hardest
     * tests score as a clean run.</p>
     */
    static Outcome classify(String defectiveStatus, String correctStatus) {
        if (defectiveStatus == null) {
            return Outcome.NOT_RUN;
        }
        if (defectiveStatus.equals("skipped")) {
            return Outcome.NOT_RUN;
        }
        if (correctStatus == null || correctStatus.equals("skipped")) {
            return Outcome.INCONCLUSIVE;
        }
        boolean failedOnDefect = defectiveStatus.equals("failed")
                || defectiveStatus.equals("timedOut")
                || defectiveStatus.equals("interrupted");
        boolean passedOnCorrect = correctStatus.equals("passed");
        boolean failedOnCorrect = correctStatus.equals("failed")
                || correctStatus.equals("timedOut")
                || correctStatus.equals("interrupted");

        if (failedOnDefect && passedOnCorrect) {
            return Outcome.DETECTED;
        }
        if (failedOnDefect && failedOnCorrect) {
            return Outcome.FALSE_POSITIVE;
        }
        if (!failedOnDefect && passedOnCorrect) {
            return Outcome.MISSED;
        }
        return Outcome.INCONCLUSIVE;
    }
}
