package com.qalab.qalabai.service.report;

import com.qalab.qalabai.service.workspace.ArtifactStore;
import com.qalab.qalabai.service.workspace.TestArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTML report is the artifact a user actually reads, so its contract is pinned:
 * it must open with no network, show which tests failed, and put the right screenshot
 * next to the right failure.
 */
class HtmlReportRendererTest {

    @TempDir
    Path dir;

    /** A 1x1 PNG — enough to prove real base64 embedding without a fixture file. */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private Path writePng(String name) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, TINY_PNG);
        return file;
    }

    private Map<String, Object> summary(int passed, int failed, int skipped) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("executionId", 42L);
        summary.put("testFile", "login.spec.ts");
        summary.put("status", failed > 0 ? "FAILED" : "PASSED");
        summary.put("createdAt", "2026-09-26T18:00:00");
        summary.put("durationMs", 18_612L);
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        return summary;
    }

    // ---- the self-contained requirement ----

    @Test
    void theReportOpensWithNoNetwork() {
        String html = HtmlReportRenderer.render(summary(1, 0, 0), List.of(), Map.of());

        assertTrue(html.startsWith("<!DOCTYPE html>"), "must be a complete document");
        assertTrue(html.contains("<style>"), "styles must be inline");
        // Any external reference would break the moment the file is emailed or archived.
        assertFalse(html.contains("http://"), "no absolute http references");
        assertFalse(html.contains("https://"), "no absolute https references");
        assertFalse(html.contains("<link"), "no external stylesheet");
        assertFalse(html.contains("<script"), "no scripts at all");
        assertFalse(html.contains("//cdn"), "no CDN");
    }

    @Test
    void theDocumentIsWellFormed() {
        String html = HtmlReportRenderer.render(summary(1, 1, 0), List.of(
                view(0, "a.spec.ts", "Login works", "failed")), Map.of());

        assertEquals(1, countOf(html, "<html"), "one html element");
        assertEquals(1, countOf(html, "</html>"), "one closing html tag");
        assertEquals(1, countOf(html, "<body"), "one body");
        assertEquals(countOf(html, "<section"), countOf(html, "</section>"), "sections must balance");
        assertTrue(html.trim().endsWith("</html>"), "must end with the closing tag");
    }

    // ---- counts and status ----

    @Test
    void theSummaryShowsEveryCount() {
        String html = HtmlReportRenderer.render(summary(7, 4, 1), List.of(), Map.of());

        assertTrue(html.contains(">7<"), "passed count");
        assertTrue(html.contains(">4<"), "failed count");
        assertTrue(html.contains("Pass rate"), "pass rate must be present");
        // 7 of 12 is 58%, and a wrong percentage here would be worse than none.
        assertTrue(html.contains("58%"), html.substring(0, 2000));
        assertTrue(html.contains("18.6 s"), "duration must be human readable");
    }

    @Test
    void aFailingRunIsVisuallyDistinctFromAPassingOne() {
        String failing = HtmlReportRenderer.render(summary(1, 1, 0), List.of(), Map.of());
        String passing = HtmlReportRenderer.render(summary(2, 0, 0), List.of(), Map.of());

        assertTrue(failing.contains("card fail"), "a failing run must be marked");
        assertFalse(passing.contains("card fail"), "a clean run must not be marked as failing");
    }

    @Test
    void anEmptyRunDoesNotReportAHundredPercent() {
        // Dividing by zero here would claim a perfect pass rate for a run that never
        // executed a test.
        String html = HtmlReportRenderer.render(summary(0, 0, 0), List.of(), Map.of());

        // Scoped to the rendered value: the stylesheet legitimately contains
        // "width:100%", which a bare substring check would trip over.
        assertFalse(html.contains(">100%<"), "no tests must not read as a perfect run");
        assertFalse(html.contains("Pass rate"), "pass rate is meaningless with no tests");
    }

    // ---- per-test rows ----

    @Test
    void everyTestIsListedWithItsStatusAndDuration() {
        String html = HtmlReportRenderer.render(summary(1, 1, 1), List.of(
                view(0, "a.spec.ts", "Login with empty password", "failed"),
                view(1, "a.spec.ts", "Login succeeds", "passed"),
                view(2, "b.spec.ts", "Search is skipped", "skipped")), Map.of());

        assertTrue(html.contains("Login with empty password"));
        assertTrue(html.contains("Login succeeds"));
        assertTrue(html.contains("Search is skipped"));
        assertTrue(html.contains("badge failed"));
        assertTrue(html.contains("badge passed"));
        assertTrue(html.contains("badge skipped"));
        assertTrue(html.contains("7.1 s"), "a failing test's duration must be shown");
    }

    @Test
    void theErrorMessageIsShownCollapsedRatherThanDumped() {
        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "Login", "failed", 1000L, 0,
                        "Error: expect(locator).toBeVisible() failed\n  at Object.login (a.ts:31:24)",
                        "at Object.login", List.of(), List.of(), List.of())), Map.of());

        assertTrue(html.contains("<details"), "the error must be collapsed by default");
        assertTrue(html.contains("expect(locator).toBeVisible() failed"),
                "the first line must be visible without expanding");
        assertTrue(html.contains("a.ts:31:24"), "the full message must be available");
    }

    @Test
    void retriesAreSurfacedBecauseAFlakeIsNotAFailure() {
        String html = HtmlReportRenderer.render(summary(1, 0, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "Eventually passes", "passed",
                        900L, 2, null, null, List.of(), List.of(), List.of())), Map.of());

        assertTrue(html.contains(">2<"), "the retry count must be visible");
    }

    @Test
    void aTestWithNoRetriesShowsADashRatherThanAZero() {
        String html = HtmlReportRenderer.render(summary(1, 0, 0), List.of(
                view(0, "a.spec.ts", "Fine", "passed")), Map.of());

        assertTrue(html.contains("—"), "an absent retry count reads better than 0");
    }

    // ---- evidence ----

    @Test
    void screenshotsAreEmbeddedSoTheFileIsSelfContained() throws IOException {
        writePng("tests/0-login/test-failed-1.png");
        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "Login", "failed", 1000L, 0,
                        "boom", null,
                        List.of("tests/0-login/test-failed-1.png"), List.of(), List.of())),
                Map.of(), dir);

        assertTrue(html.contains("data:image/png;base64,"), "the screenshot must be inlined");
        assertTrue(html.contains("<img src=\"data:image/png;base64,"), "and used as the image source");
        // The link must point at the file, not repeat the payload: a data-URI href would
        // embed every screenshot twice and double the report size for nothing.
        assertTrue(html.contains("href=\"tests/0-login/test-failed-1.png\""),
                "the link should open the full-size file");
        assertEquals(1, countOf(html, "data:image/png;base64,"),
                "a screenshot must be embedded exactly once");
    }

    @Test
    void eachFailingTestGetsItsOwnScreenshot() throws IOException {
        writePng("tests/0-first/test-failed-1.png");
        writePng("tests/1-second/test-failed-1.png");

        String html = HtmlReportRenderer.render(summary(0, 2, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "First fails", "failed", 1000L, 0,
                        "boom", null, List.of("tests/0-first/test-failed-1.png"), List.of(), List.of()),
                new HtmlReportRenderer.TestCaseView(1, "a.spec.ts", "Second fails", "failed", 2000L, 0,
                        "boom", null, List.of("tests/1-second/test-failed-1.png"), List.of(), List.of())),
                Map.of(), dir);

        // Two distinct images, which is the acceptance criterion: the run must not show
        // one screenshot for two different failures.
        assertEquals(2, countOf(html, "<img src=\"data:image/png;base64,"),
                "each failing test needs its own screenshot");
        assertTrue(html.contains("Screenshot of First fails"));
        assertTrue(html.contains("Screenshot of Second fails"));
    }

    @Test
    void videosAndTracesAreLinkedNotEmbedded() {
        // They are routinely tens of megabytes; base64 would inflate them by a third and
        // produce an HTML file no mail client will open.
        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "Login", "failed", 1000L, 0,
                        "boom", null, List.of(),
                        List.of("tests/0-login/video.webm"),
                        List.of("tests/0-login/trace.zip"))), Map.of(), dir);

        assertTrue(html.contains("href=\"tests/0-login/video.webm\""), "video must be linked");
        assertTrue(html.contains("href=\"tests/0-login/trace.zip\""), "trace must be linked");
        assertFalse(html.contains("data:video"), "a video must never be inlined");
        assertFalse(html.contains("data:application/zip"), "a trace must never be inlined");
    }

    @Test
    void aMissingScreenshotDegradesToALinkRatherThanBreakingTheReport() {
        // The reporter can name a file Playwright later discarded, and a workspace may be
        // cleaned up between the run and the report.
        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "Login", "failed", 1000L, 0,
                        "boom", null, List.of("tests/0-login/gone.png"), List.of(), List.of())),
                Map.of(), dir);

        assertTrue(html.contains(">Login<"), "the row must still render");
        assertTrue(html.contains("href=\"tests/0-login/gone.png\""), "and offer a link");
        assertFalse(html.contains("data:image"), "nothing should be embedded");
    }

    @Test
    void anUnreadableOrOversizedScreenshotIsNotInlined() throws IOException {
        // Beyond the size cap the file is linked instead: inlining a 40 MB screenshot
        // would make the report unopenable.
        Path big = dir.resolve("tests/0-login/big.png");
        Files.createDirectories(big.getParent());
        Files.write(big, new byte[(int) HtmlReportRenderer.MAX_EMBEDDED_SCREENSHOT_BYTES + 1]);

        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "Login", "failed", 1000L, 0,
                        "boom", null, List.of("tests/0-login/big.png"), List.of(), List.of())),
                Map.of(), dir);

        assertFalse(html.contains("data:image"), "an oversized screenshot must be linked, not inlined");
        assertTrue(html.contains("href=\"tests/0-login/big.png\""));
    }

    @Test
    void aNonImageIsNeverEmbedded() throws IOException {
        // A .txt that happens to sit where a screenshot should be must not become a
        // broken <img>.
        Path text = dir.resolve("tests/0-login/notes.txt");
        Files.createDirectories(text.getParent());
        Files.writeString(text, "just notes");

        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "Login", "failed", 1000L, 0,
                        "boom", null, List.of("tests/0-login/notes.txt"), List.of(), List.of())),
                Map.of(), dir);

        assertFalse(html.contains("<img"), "a text file must not become an image");
    }

    @Test
    void aTestWithNoEvidenceSaysSoRatherThanShowingAnEmptyCell() {
        String html = HtmlReportRenderer.render(summary(1, 0, 0), List.of(
                view(0, "a.spec.ts", "Fine", "passed")), Map.of());

        assertTrue(html.contains("class=\"none\""), "an empty cell must be explicit");
    }

    // ---- graceful degradation ----

    @Test
    void aRunWithNoPerTestResultsSaysSoInsteadOfLookingGreen() {
        // The important case: rendering an empty green table would read as a passing run.
        String html = HtmlReportRenderer.render(summary(0, 0, 0), List.of(), Map.of());

        assertTrue(html.contains("no per-test results"),
                "the report must state that results are missing");
        assertFalse(html.contains("badge passed"), "nothing may be shown as passing");
    }

    @Test
    void aReportWithNoTestPlanOrHealingStillRenders() {
        String html = HtmlReportRenderer.render(summary(1, 0, 0), List.of(), Map.of());

        assertTrue(html.contains("</html>"));
        assertFalse(html.contains("null"), "no null should leak into the output");
    }

    @Test
    void extrasAreRenderedWhenPresent() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("testPlan", "# Plan\n\n| Scenario | Type |");
        extras.put("failureAnalysis", Map.of("classification", "ASSERTION_FAILURE"));

        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(), extras);

        assertTrue(html.contains("Test Plan"));
        assertTrue(html.contains("ASSERTION_FAILURE"));
        assertTrue(html.contains("Failure Analysis"));
    }

    // ---- escaping: titles are model-generated text and can contain anything ----

    @Test
    void markupInATestTitleCannotBreakTheDocument() {
        String hostile = "Login </td></tr><script>alert(1)</script> \"quoted\" & 'single'";

        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                view(0, "a.spec.ts", hostile, "failed")), Map.of());

        assertFalse(html.contains("<script>"), "a title must never inject a script");
        assertTrue(html.contains("&lt;script&gt;"), "it must be escaped instead");
        assertTrue(html.contains("&amp;"), "ampersands must be escaped");
        assertTrue(html.contains("&quot;"), "quotes must be escaped so attributes hold");
        assertTrue(html.trim().endsWith("</html>"), "the document must still be intact");
    }

    @Test
    void markupInAnErrorMessageCannotBreakTheDocument() {
        String html = HtmlReportRenderer.render(summary(0, 1, 0), List.of(
                new HtmlReportRenderer.TestCaseView(0, "a.spec.ts", "T", "failed", 1L, 0,
                        "expected </pre></section> to stay open", null, List.of(), List.of(), List.of())),
                Map.of());

        assertEquals(countOf(html, "<section"), countOf(html, "</section>"), "sections must still balance");
        assertTrue(html.contains("&lt;/pre&gt;"), "the payload must be escaped");
    }

    // ---- helpers ----

    @Test
    void durationFormattingIsReadableAtEveryScale() {
        assertEquals("—", HtmlReportRenderer.formatDuration(null));
        assertEquals("0 ms", HtmlReportRenderer.formatDuration(0L));
        assertEquals("999 ms", HtmlReportRenderer.formatDuration(999L));
        assertEquals("1 s", HtmlReportRenderer.formatDuration(1000L));
        assertEquals("18.6 s", HtmlReportRenderer.formatDuration(18_612L));
        assertEquals("1m 5.5s", HtmlReportRenderer.formatDuration(65_500L));
    }

    @Test
    void theArtifactIndexIsKeyedTheSameWayTheRendererExpects() {
        TestArtifacts first = new TestArtifacts(0, "login", "a.spec.ts", "Login", "failed",
                List.of("tests/0-login/a.png"), List.of(), List.of(), "x");
        TestArtifacts second = new TestArtifacts(1, "search", "a.spec.ts", "Search", "passed",
                List.of(), List.of(), List.of(), "y");

        Map<String, TestArtifacts> index = ArtifactStore.indexByKey(List.of(first, second));

        assertEquals(2, index.size());
        assertEquals("Login", index.get("0-login").title());
        assertEquals("Search", index.get("1-search").title());
    }

    @Test
    void anEvidenceFreeTestIsNotCountedAsHavingEvidence() {
        assertFalse(new TestArtifacts(0, "t", "a", "T", "passed",
                List.of(), List.of(), List.of(), "d").hasEvidence());
        assertTrue(new TestArtifacts(0, "t", "a", "T", "failed",
                List.of("x.png"), List.of(), List.of(), "d").hasEvidence());
    }

    @Test
    void nothingLeaksBetweenRenders() {
        // Cheap guard against static state creeping into the renderer.
        List<HtmlReportRenderer.TestCaseView> shared = new ArrayList<>();
        shared.add(view(0, "a.spec.ts", "First", "failed"));

        String one = HtmlReportRenderer.render(summary(0, 1, 0), shared, Map.of());
        String two = HtmlReportRenderer.render(summary(1, 0, 0), List.of(), Map.of());

        assertTrue(one.contains("First"));
        assertFalse(two.contains("First"), "a later render must not see an earlier test");
    }

    private HtmlReportRenderer.TestCaseView view(int ordinal, String file, String title, String status) {
        return new HtmlReportRenderer.TestCaseView(ordinal, file, title, status, 7_148L, 0,
                "failed".equals(status) ? "Error: expect(locator).toBeVisible() failed" : null,
                null, List.of(), List.of(), List.of());
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
