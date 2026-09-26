package com.qalab.qalabai.service.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two defects this fixes, both of which cost a user their evidence.
 *
 * <p><strong>Traces were destroyed.</strong> Every {@code .zip} whose name contained
 * "trace" was copied to the same {@code trace.zip} with {@code REPLACE_EXISTING}, so a
 * run with two failing tests kept one trace and silently discarded the other. A trace is
 * the single most useful artifact for reconstructing a failure.</p>
 *
 * <p><strong>Evidence was not attributable.</strong> Screenshots became
 * {@code screenshot.png} / {@code screenshot-2.png} with nothing recording which test
 * each belonged to, so a report could say three tests failed and show one screenshot.</p>
 */
class ArtifactStorePerTestTest {

    @TempDir
    Path temp;

    private ArtifactStore store() {
        ArtifactStore store = new ArtifactStore();
        ReflectionTestUtils.setField(store, "artifactsDir", temp.resolve("artifacts").toString());
        return store;
    }

    /** Builds a fake Playwright test-results tree with the named files per test. */
    private String workspaceWith(List<TestSpec> specs) throws IOException {
        Path workspace = temp.resolve("workspace");
        for (TestSpec spec : specs) {
            Path testDir = workspace.resolve("test-results").resolve(spec.dir());
            Files.createDirectories(testDir);
            for (String file : spec.files()) {
                Files.writeString(testDir.resolve(file), "content of " + file);
            }
        }
        return workspace.toString();
    }

    private record TestSpec(String dir, List<String> files) {
    }

    private TestArtifacts test(int ordinal, String slug, String title, String status, String workspace) {
        Path testDir = Path.of(workspace).resolve("test-results").resolve(slug);
        return new TestArtifacts(ordinal, slug, "a.spec.ts", title, status,
                List.of(testDir.resolve("test-failed-1.png").toString()),
                List.of(),
                List.of(testDir.resolve("trace.zip").toString()),
                null);
    }

    @Test
    void twoTracesNoLongerOverwriteEachOther() throws IOException {
        String workspace = workspaceWith(List.of(
                new TestSpec("first", List.of("test-failed-1.png", "trace.zip")),
                new TestSpec("second", List.of("test-failed-1.png", "trace.zip"))));

        ArtifactResult result = store().collect(workspace, 1L, "console output", List.of(
                test(0, "first", "First fails", "failed", workspace),
                test(1, "second", "Second fails", "failed", workspace)));

        assertEquals(2, result.getTraceCount(),
                "both traces must survive; one used to overwrite the other");
        List<TestArtifacts> perTest = result.getPerTest();
        assertEquals(2, perTest.size());
        assertNotEquals(perTest.get(0).traces().get(0), perTest.get(1).traces().get(0),
                "the two traces must be different files");

        for (TestArtifacts test : perTest) {
            for (String trace : test.traces()) {
                assertTrue(Files.isRegularFile(
                                temp.resolve("artifacts").resolve("execution-1").resolve(trace)),
                        "trace must exist on disk: " + trace);
            }
        }
    }

    @Test
    void eachTestsScreenshotIsKeptBesideItsOwnTest() throws IOException {
        String workspace = workspaceWith(List.of(
                new TestSpec("first", List.of("test-failed-1.png", "trace.zip")),
                new TestSpec("second", List.of("test-failed-1.png", "trace.zip"))));

        ArtifactResult result = store().collect(workspace, 1L, null, List.of(
                test(0, "first", "First fails", "failed", workspace),
                test(1, "second", "Second fails", "failed", workspace)));

        TestArtifacts first = result.getPerTest().get(0);
        TestArtifacts second = result.getPerTest().get(1);

        assertEquals("First fails", first.title());
        assertEquals("Second fails", second.title());
        assertFalse(first.screenshots().equals(second.screenshots()),
                "identical screenshot names must not collapse into one file");
        assertTrue(first.dir().contains("0-first"), "the directory must name the test: " + first.dir());
        assertTrue(second.dir().contains("1-second"), "the directory must name the test: " + second.dir());
    }

    @Test
    void attachmentPathsAreRelativeSoTheReportSurvivesBeingMoved() throws IOException {
        // report.html sits in the artifact directory; absolute paths would break the
        // first time the folder was emailed, archived or copied.
        String workspace = workspaceWith(List.of(
                new TestSpec("first", List.of("test-failed-1.png", "trace.zip"))));

        ArtifactResult result = store().collect(workspace, 1L, null,
                List.of(test(0, "first", "First fails", "failed", workspace)));

        String screenshot = result.getPerTest().get(0).screenshots().get(0);
        assertFalse(screenshot.startsWith("/"), "must be relative, was: " + screenshot);
        assertTrue(screenshot.startsWith("tests/0-first/"), screenshot);
        // …but it must still resolve against the artifact directory.
        assertTrue(Files.isRegularFile(Path.of(result.getArtifactDir()).resolve(screenshot)));
    }

    @Test
    void theLegacySingleFieldsPointAtTheFirstFailure() throws IOException {
        // Existing consumers read getScreenshot()/getTrace(); they must keep working.
        String workspace = workspaceWith(List.of(
                new TestSpec("first", List.of("test-failed-1.png", "trace.zip")),
                new TestSpec("second", List.of("test-failed-1.png", "trace.zip"))));

        ArtifactResult result = store().collect(workspace, 1L, null, List.of(
                test(0, "first", "Passes", "passed", workspace),
                test(1, "second", "Fails", "failed", workspace)));

        assertNotNull(result.getScreenshot());
        assertTrue(result.getScreenshot().contains("1-second"),
                "the legacy screenshot should be the first failure, was: " + result.getScreenshot());
        assertTrue(Path.of(result.getScreenshot()).isAbsolute(),
                "the legacy field is consumed as an absolute path");
    }

    @Test
    void evidenceIsNotAlsoFlattened() throws IOException {
        // Flattening is what destroyed traces; doing both would duplicate every file.
        String workspace = workspaceWith(List.of(
                new TestSpec("first", List.of("test-failed-1.png", "trace.zip"))));

        ArtifactResult result = store().collect(workspace, 1L, null,
                List.of(test(0, "first", "Fails", "failed", workspace)));

        Path artifactDir = Path.of(result.getArtifactDir());
        assertFalse(Files.exists(artifactDir.resolve("trace.zip")),
                "there must be no flattened trace beside the per-test directories");
        assertTrue(Files.isDirectory(artifactDir.resolve("tests")));
    }

    @Test
    void aTestWithNoAttachmentsIsSimplyOmittedFromTheEvidence() throws IOException {
        String workspace = workspaceWith(List.of(new TestSpec("first", List.of("test-failed-1.png"))));

        ArtifactResult result = store().collect(workspace, 1L, null, List.of(
                test(0, "first", "Fails", "failed", workspace),
                new TestArtifacts(1, "clean", "a.spec.ts", "Passes", "passed",
                        List.of(), List.of(), List.of(), null)));

        assertEquals(1, result.getPerTest().size(), "a test with no evidence adds nothing to copy");
    }

    @Test
    void aMissingSourceFileIsSkippedRatherThanFailingTheCollection() {
        // Playwright can name a file it later discarded.
        ArtifactResult result = store().collect(temp.resolve("nowhere").toString(), 5L, null, List.of(
                new TestArtifacts(0, "gone", "a.spec.ts", "Fails", "failed",
                        List.of("/does/not/exist.png"), List.of(), List.of(), null)));

        assertTrue(result.getPerTest().isEmpty());
        assertNotNull(result.getArtifactDir(), "the run must still get an artifact directory");
    }

    @Test
    void twoFilesWithTheSameNameInOneTestDoNotOverwrite() throws IOException {
        String workspace = workspaceWith(List.of(
                new TestSpec("first", List.of("shot.png"))));
        Path testDir = Path.of(workspace).resolve("test-results").resolve("first");
        Files.copy(testDir.resolve("shot.png"), testDir.resolve("shot-copy.png"));

        ArtifactResult result = store().collect(workspace, 7L, null, List.of(
                new TestArtifacts(0, "first", "a.spec.ts", "Fails", "failed",
                        List.of(testDir.resolve("shot.png").toString(),
                                testDir.resolve("shot-copy.png").toString()),
                        List.of(), List.of(), null)));

        assertEquals(2, result.getPerTest().get(0).screenshots().size(),
                "both copies must survive under distinct names");
    }

    @Test
    void theConsoleLogIsAlwaysWritten() {
        ArtifactResult result = store().collect(null, 9L, "the console output");

        assertNotNull(result.getLog());
        assertTrue(result.getLog().endsWith("console.log"));
    }

    @Test
    void aRunWithNoStructuredResultsStillCollectsSomething() throws IOException {
        // Fallback for runs that predate the JSON reporter.
        String workspace = workspaceWith(List.of(
                new TestSpec("first", List.of("test-failed-1.png", "trace.zip"))));

        ArtifactResult result = store().collect(workspace, 11L, null);

        assertTrue(result.getPerTest().isEmpty(), "no per-test structure is possible");
        assertNotNull(result.getArtifactDir());
    }

    @Test
    void aNullExecutionIdIsHandledRatherThanThrowing() {
        ArtifactResult result = store().collect("/tmp", null, "output");

        assertNotNull(result);
    }
}
