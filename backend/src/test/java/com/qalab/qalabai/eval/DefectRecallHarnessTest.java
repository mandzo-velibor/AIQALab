package com.qalab.qalabai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the golden eval suite and fails the build on a regression against the committed
 * baseline.
 *
 * <p>This is the "does the product still find bugs?" check. The product's claim is defect
 * detection, and until now nothing measured it — so a prompt change that quietly made
 * the generator worse looked exactly like a prompt change that improved a summary.</p>
 *
 * <p>Two runs are needed, because one cannot distinguish a detection from a false
 * positive: the suite is executed against the defective pages and again against their
 * correct twins. See {@link DefectRecallScorer} for the decision table.</p>
 *
 * <p>Skipped, not failed, when Playwright or its browsers are absent — a machine without
 * them cannot answer the question, and reporting that as a regression would be a lie.
 * CI installs them so the check is real there.</p>
 */
@EnabledIf("harnessAvailable")
class DefectRecallHarnessTest {

    private static final Path REPO = Paths.get("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = REPO.resolve("evals/fixtures");
    private static final Path GOLDEN = REPO.resolve("evals/golden");
    private static final Path BASELINE = REPO.resolve("evals/baseline.json");
    private static final Path LAST_REPORT = REPO.resolve("evals/last-run.json");

    private final ObjectMapper mapper = new ObjectMapper();

    static boolean harnessAvailable() {
        // The runner package must resolve from the suite's own directory. `mvn verify` in
        // CI does not install node_modules there, and a harness that fails on a missing
        // dev dependency would take the whole backend build down with it.
        return Files.isDirectory(GOLDEN)
                && Files.isDirectory(FIXTURES)
                && browsersInstalled()
                && Files.isDirectory(GOLDEN.resolve("node_modules/@playwright"));
    }

    private static boolean browsersInstalled() {
        String configured = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (configured != null && !configured.isBlank() && Files.isDirectory(Paths.get(configured))) {
            return true;
        }
        String home = System.getProperty("user.home");
        return Files.isDirectory(Paths.get(home, ".cache", "ms-playwright"))
                || Files.isDirectory(Paths.get(home, "Library", "Caches", "ms-playwright"));
    }

    @Test
    void theGoldenSuiteCatchesEveryPlantedDefect() throws Exception {
        RunResult defective = runSuite("defective");
        RunResult correct = runSuite("correct");
        assertCorpusIntact();
        Map<String, String> onDefective = defective.statuses();
        Map<String, String> onCorrect = correct.statuses();
        Map<String, int[]> retries = defective.retries();

        Map<String, String> defectByTest = new LinkedHashMap<>();
        for (String testId : onDefective.keySet()) {
            defectByTest.put(testId, defectIdOf(testId));
        }

        DefectRecallScorer.Score score = DefectRecallScorer.score(
                onDefective, onCorrect, defectByTest, retries);

        writeReport(score, onDefective, onCorrect);

        System.out.printf(
                "%n=== DEFECT RECALL ===%nrecall %.1f%% (%d/%d defects) · %d tests · "
                        + "false positives %.1f%% · flakes %.1f%%%n",
                score.defectRecall() * 100,
                (int) (score.defectRecall() * score.defects().size()),
                score.defects().size(), score.totalTests(),
                score.falsePositiveRate() * 100, score.flakeRate() * 100);
        for (DefectRecallScorer.DefectScore defect : score.defects()) {
            System.out.printf("  %-24s %s (%d/%d oracle tests)%n",
                    defect.defectId(), defect.detected() ? "CAUGHT " : "MISSED ",
                    defect.detectionCount(), defect.tests().size());
        }
        if (!score.falsePositiveTests().isEmpty()) {
            System.out.println("  false positives: " + score.falsePositiveTests());
        }

        // The instrument's own health, asserted before the score means anything. An
        // oracle test that fails against a *correct* page is a broken oracle, and scoring
        // on top of it would produce confident nonsense.
        assertTrue(score.falsePositiveTests().isEmpty(),
                "the golden suite objects to correct pages, so the instrument is broken: "
                        + score.falsePositiveTests());
        assertEquals(0, score.notRun(), "every oracle test must run in both passes");
        assertEquals(0, score.inconclusive(),
                "every oracle test needs a result from both runs to be conclusive");

        // The denominator has to be the planted defects, not the tests. A harness that
        // scored 7/7 over seven accidental "defects" would report perfect recall while
        // measuring nothing, which is worse than no harness at all.
        assertEquals(5, score.defects().size(),
                "the score must be grouped by planted defect, one per fixture: " + score.defects());
        assertEquals(7, score.totalTests(), "the golden suite has seven oracle tests");

        // The committed baseline. This is the gate: a prompt or model change that lowers
        // recall fails here rather than being discovered by a user.
        if (Files.exists(BASELINE)) {
            JsonNode baseline = mapper.readTree(Files.readString(BASELINE));
            double expected = baseline.path("defectRecall").asDouble();
            assertTrue(score.defectRecall() >= expected,
                    String.format("defect recall regressed: %.3f is below the committed baseline %.3f.%n"
                                    + "Missed: %s%nIf the change is an intended improvement, raise the "
                                    + "baseline in evals/baseline.json deliberately.",
                            score.defectRecall(), expected, score.missedDefects()));
        } else {
            fail("No baseline at " + BASELINE + ". Run the harness once and commit the result, "
                    + "otherwise there is nothing to regress against.");
        }
    }

    /**
     * Executes the golden suite and returns test id → status.
     *
     * <p>The correct run swaps each defective page for its twin, runs, and puts the
     * defective pages back in a finally block — a harness that leaves the corpus
     * mutated would quietly weaken every later run.</p>
     */
    /** What one pass of the golden suite produced. */
    private record RunResult(Map<String, String> statuses, Map<String, int[]> retries) {
    }

    private RunResult runSuite(String variant) throws Exception {
        Path report = Files.createTempFile("qalab-eval-" + variant, ".json");
        // The originals are copied aside and put back. An earlier version deleted the
        // swapped file in its finally block, which quietly destroyed the defective
        // corpus: every later run then measured the *correct* pages, reported 0% recall
        // with 100% false positives, and still exited as a measurement rather than an
        // error. An instrument that destroys its own input and keeps reporting is worse
        // than one that crashes.
        List<Path> backups = new ArrayList<>();
        try {
            if ("correct".equals(variant)) {
                try (Stream<Path> dirs = Files.list(FIXTURES)) {
                    for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                        Path correct = dir.resolve("correct.html");
                        Path defective = dir.resolve("app.html");
                        if (!Files.exists(correct) || !Files.exists(defective)) {
                            continue;
                        }
                        Path backup = Files.createTempFile("qalab-eval-defect", ".html");
                        Files.copy(defective, backup, StandardCopyOption.REPLACE_EXISTING);
                        backups.add(defective);
                        backupFor.put(defective, backup);
                        Files.copy(correct, defective, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            runPlaywright(report);
            JsonNode parsed = mapper.readTree(Files.readString(report));
            Map<String, String> statuses = new LinkedHashMap<>();
            Map<String, int[]> retries = new LinkedHashMap<>();
            collect(parsed, statuses, retries);
            return new RunResult(statuses, retries);
        } finally {
            for (Path defective : backups) {
                Files.copy(backupFor.get(defective), defective, StandardCopyOption.REPLACE_EXISTING);
            }
            for (Path backup : backupFor.values()) {
                Files.deleteIfExists(backup);
            }
            backupFor.clear();
            Files.deleteIfExists(report);
        }
    }

    private final Map<Path, Path> backupFor = new LinkedHashMap<>();

    /**
     * Asserts the corpus is intact and every fixture still differs from its twin.
     *
     * <p>Called after both runs. A defective page identical to its correct twin means
     * the planted defect is gone, and the recall figure would be meaningless — so this
     * fails loudly instead of reporting a number.</p>
     */
    private void assertCorpusIntact() throws IOException {
        try (Stream<Path> dirs = Files.list(FIXTURES)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path defective = dir.resolve("app.html");
                Path correct = dir.resolve("correct.html");
                assertTrue(Files.exists(defective),
                        "the defective page is missing from " + dir.getFileName()
                                + "; the harness must restore what it swaps");
                assertTrue(Files.exists(correct),
                        "the correct twin is missing from " + dir.getFileName());
                assertTrue(!java.util.Arrays.equals(Files.readAllBytes(defective), Files.readAllBytes(correct)),
                        dir.getFileName() + " has no planted defect: app.html and correct.html "
                                + "are identical, so nothing can be detected there");
            }
        }
    }

    private void runPlaywright(Path report) throws Exception {
        List<String> command = List.of("npx", "playwright", "test");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(GOLDEN.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("QALAB_EVAL_JSON", report.toAbsolutePath().toString());
        Path log = Files.createTempFile("qalab-eval-log", ".txt");
        builder.redirectOutput(log.toFile());

        Process process = builder.start();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("The golden suite did not finish in 5 minutes:\n" + Files.readString(log));
        }
        String output = Files.readString(log);
        Files.deleteIfExists(log);

        // A browser that cannot launch makes every test "fail", which the scorer reads
        // as 100% false positives. That is technically consistent and completely
        // misleading: the corpus is fine, the machine is not. Caught here so the message
        // says which.
        if (output.contains("Executable doesn't exist")
                || output.contains("Looks like Playwright Test or Playwright was just installed")) {
            fail("The Playwright browser could not be launched, so every test failed and the\n"
                    + "score would be meaningless. Install the browser this version needs:\n"
                    + "    cd evals/golden && npx playwright install chromium\n"
                    + "Runner said:\n" + output);
        }
        if (!Files.exists(report)) {
            fail("The golden suite produced no JSON report:\n" + output);
        }
    }

    /**
     * Flattens the reporter tree, keying each test as {@code <describe>/<test title>}.
     *
     * <p>The suite that directly contains a spec is the defect id, because the golden
     * suite names each describe after the defect it covers. Two earlier versions got this
     * wrong in opposite directions — one threaded the parent's name down and produced one
     * "defect" per test, the other used the file name — and both reported a flawless
     * 100% recall over the wrong denominator. A recall number is only as meaningful as
     * its grouping, so the grouping is asserted below rather than assumed.</p>
     */
    private void collect(JsonNode suite,
                         Map<String, String> statuses, Map<String, int[]> retries) {
        String ownTitle = suite.path("title").asText("");
        for (JsonNode spec : suite.path("specs")) {
            // The suite that holds the spec IS the describe, and the golden suite names
            // each one after the defect it covers. Always this suite's own title: using
            // the parent's produced the file name as the "defect".
            String id = ownTitle + "/" + spec.path("title").asText();
            for (JsonNode test : spec.path("tests")) {
                JsonNode results = test.path("results");
                if (results.isEmpty()) {
                    statuses.put(id, "skipped");
                    retries.put(id, new int[]{0});
                    continue;
                }
                statuses.put(id, results.get(results.size() - 1).path("status").asText("unknown"));
                retries.put(id, new int[]{results.size() - 1});
            }
        }
        for (JsonNode child : suite.path("suites")) {
            collect(child, statuses, retries);
        }
    }

    private String defectIdOf(String testId) {
        int slash = testId.indexOf('/');
        return slash > 0 ? testId.substring(0, slash) : testId;
    }

    private void writeReport(DefectRecallScorer.Score score,
                             Map<String, String> onDefective,
                             Map<String, String> onCorrect) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("defectRecall", score.defectRecall());
        report.put("falsePositiveRate", score.falsePositiveRate());
        report.put("flakeRate", score.flakeRate());
        report.put("totalTests", score.totalTests());
        report.put("missedDefects", score.missedDefects());
        report.put("falsePositiveTests", score.falsePositiveTests());
        List<Map<String, Object>> perDefect = new ArrayList<>();
        for (DefectRecallScorer.DefectScore defect : score.defects()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("defectId", defect.defectId());
            one.put("detected", defect.detected());
            one.put("detectionCount", defect.detectionCount());
            one.put("testCount", defect.tests().size());
            perDefect.add(one);
        }
        report.put("defects", perDefect);
        Files.writeString(LAST_REPORT,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
