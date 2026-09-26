package com.qalab.qalabai.tool.playwright;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workflow previously only saw a truncated blob of Playwright's text output, so
 * it could not say which tests failed, could not attach a screenshot to a specific
 * failure, and the CLI had to grep for a bullet character. These tests pin the
 * structured parse.
 */
class PlaywrightResultParserTest {

    @TempDir
    Path dir;

    private void write(String name, String json) throws IOException {
        Files.writeString(dir.resolve(name), json);
    }

    @Test
    void parsesCountsTestsAndAttachments() throws IOException {
        // Shape captured from a real `playwright --reporter=json` run.
        write("r.json", """
                {
                  "config": {},
                  "errors": [],
                  "stats": { "startTime": "2026-09-26T16:01:43.751Z", "duration": 18612.68,
                             "expected": 1, "skipped": 0, "unexpected": 1, "flaky": 0 },
                  "suites": [
                    {
                      "title": "failed-login.spec.ts",
                      "specs": [
                        {
                          "title": "Failed login with invalid credentials",
                          "file": "failed-login.spec.ts",
                          "ok": false,
                          "tests": [
                            { "status": "unexpected",
                              "results": [
                                { "status": "failed", "duration": 7148, "retry": 0,
                                  "error": { "message": "TypeError: loginPage.getFlashMessageText is not a function",
                                             "snippet": "at Object.login" },
                                  "attachments": [
                                    { "name": "screenshot", "contentType": "image/png",
                                      "path": "/ws/test-results/failed/test-failed-1.png" },
                                    { "name": "video", "contentType": "video/webm",
                                      "path": "/ws/test-results/failed/video.webm" },
                                    { "name": "trace", "contentType": "application/zip",
                                      "path": "/ws/test-results/failed/trace.zip" }
                                  ] }
                              ] }
                          ]
                        },
                        {
                          "title": "Successful login",
                          "file": "failed-login.spec.ts",
                          "ok": true,
                          "tests": [
                            { "status": "expected",
                              "results": [ { "status": "passed", "duration": 900, "retry": 0,
                                             "attachments": [] } ] }
                          ]
                        }
                      ],
                      "suites": []
                    }
                  ]
                }
                """);

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("r.json"));

        assertEquals(1, summary.passed());
        assertEquals(1, summary.failed());
        assertEquals(0, summary.skipped());
        assertEquals(2, summary.total());
        assertEquals(18612L, summary.durationMs());
        assertEquals(2, summary.tests().size());

        PlaywrightResultParser.TestCaseResult failed = summary.tests().get(0);
        assertTrue(failed.failed());
        assertEquals("Failed login with invalid credentials", failed.title());
        assertEquals("failed-login.spec.ts", failed.file());
        assertEquals(7148L, failed.durationMs());
        assertEquals(0, failed.retries());
        assertTrue(failed.errorMessage().contains("getFlashMessageText is not a function"),
                failed.errorMessage());
        assertEquals(1, failed.screenshots().size(), "a screenshot must be attached to the failure");
        assertEquals(1, failed.videos().size());
        assertEquals(1, failed.traces().size());

        PlaywrightResultParser.TestCaseResult passed = summary.tests().get(1);
        assertFalse(passed.failed());
        assertNull(passed.errorMessage());
        assertTrue(passed.screenshots().isEmpty());
    }

    @Test
    void countsComeFromTheParsedDetailWhenReporterStatsDisagree() throws IOException {
        // Trust the detail over the header: a partial or stale header must not be able
        // to claim a green run.
        write("r.json", """
                { "stats": { "expected": 99, "unexpected": 0, "skipped": 0, "flaky": 0, "duration": 5 },
                  "suites": [ { "title": "s", "specs": [
                    { "title": "fails", "file": "a.spec.ts", "tests": [
                      { "status": "unexpected", "results": [
                        { "status": "failed", "duration": 1, "retry": 0, "attachments": [] } ] } ] }
                  ], "suites": [] } ] }
                """);

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("r.json"));

        assertEquals(0, summary.passed(), "the parsed detail must win over a bogus header");
        assertEquals(1, summary.failed());
    }

    @Test
    void countsRetriesAndReportsTheFinalAttempt() throws IOException {
        // A flaky test: attempt 1 failed, attempt 2 passed. The reported status must be
        // the final attempt, with the retry counted, not the first failure.
        write("r.json", """
                { "stats": { "expected": 0, "unexpected": 0, "skipped": 0, "flaky": 1, "duration": 3 },
                  "suites": [ { "title": "s", "specs": [
                    { "title": "eventually passes", "file": "a.spec.ts", "tests": [
                      { "status": "flaky", "results": [
                        { "status": "failed", "duration": 10, "retry": 0, "attachments": [] },
                        { "status": "passed", "duration": 12, "retry": 1, "attachments": [] } ] } ] }
                  ], "suites": [] } ] }
                """);

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("r.json"));

        assertEquals(1, summary.flaky());
        assertEquals(1, summary.total());
        PlaywrightResultParser.TestCaseResult t = summary.tests().get(0);
        assertEquals("passed", t.status(), "the final attempt decides the outcome");
        assertEquals(1, t.retries());
        assertEquals(12L, t.durationMs());
    }

    @Test
    void stripsAnsiColourCodesFromErrorMessages() throws IOException {
        write("r.json", """
                { "stats": {"expected":0,"unexpected":1,"skipped":0,"flaky":0,"duration":1},
                  "suites": [ { "title": "s", "specs": [
                    { "title": "t", "file": "a.spec.ts", "tests": [
                      { "status": "unexpected", "results": [
                        { "status": "failed", "duration": 1, "retry": 0,
                          "error": { "message": "\\u001b[2mexpect(locator).toBeVisible()\\u001b[22m failed" },
                          "attachments": [] } ] } ] }
                  ], "suites": [] } ] }
                """);

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("r.json"));

        String message = summary.tests().get(0).errorMessage();
        assertEquals("expect(locator).toBeVisible() failed", message,
                "ANSI colour codes must be stripped so the message is readable in JSON "
                        + "and in the terminal");
        assertEquals(message, PlaywrightResultParser.stripAnsi(message),
                "nothing should be left for the ANSI stripper to remove");
    }

    @Test
    void collectsGlobalErrorsFromASuiteThatNeverRan() throws IOException {
        write("r.json", """
                { "errors": [ { "message": "no tests found" }, { "message": "  " } ],
                  "stats": {"expected":0,"unexpected":0,"skipped":0,"flaky":0,"duration":0},
                  "suites": [] }
                """);

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("r.json"));

        assertEquals(1, summary.globalErrors().size(), "blank messages must be dropped");
        assertEquals("no tests found", summary.globalErrors().get(0));
    }

    @Test
    void handlesNestedSuitesAndFileLevelTitles() throws IOException {
        write("r.json", """
                { "stats": {"expected":1,"unexpected":0,"skipped":0,"flaky":0,"duration":2},
                  "suites": [ { "title": "auth", "suites": [
                      { "title": "login.spec.ts", "specs": [
                        { "title": "login.spec.ts", "file": "tests/login.spec.ts", "tests": [
                          { "status": "expected", "results": [
                            { "status": "passed", "duration": 2, "retry": 0, "attachments": [] } ] } ] }
                      ], "suites": [] } ] } ] }
                """);

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("r.json"));

        assertEquals(1, summary.tests().size());
        // The suite is named after the spec, so the title must not be doubled.
        assertEquals("login.spec.ts", summary.tests().get(0).fullTitle(),
                "a file-level suite name must not be repeated in the title");
    }

    @Test
    void aTestWithNoAttemptsIsReportedAsSkippedRatherThanDropped() throws IOException {
        write("r.json", """
                { "stats": {"expected":0,"unexpected":0,"skipped":1,"flaky":0,"duration":0},
                  "suites": [ { "title": "s", "specs": [
                    { "title": "never ran", "file": "a.spec.ts", "tests": [
                      { "status": "skipped", "results": [] } ] }
                  ], "suites": [] } ] }
                """);

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("r.json"));

        assertEquals(1, summary.skipped());
        assertTrue(summary.tests().get(0).skipped());
    }

    @Test
    void aMissingReportYieldsAnEmptySummaryRatherThanFailingTheRun() {
        PlaywrightResultParser.RunSummary summary =
                PlaywrightResultParser.parse(dir.resolve("does-not-exist.json"));

        assertNotNull(summary);
        assertEquals(0, summary.total());
        assertTrue(summary.tests().isEmpty());
    }

    @Test
    void unparseableJsonYieldsAnEmptySummaryRatherThanThrowing() throws IOException {
        write("bad.json", "{ this is not json");

        PlaywrightResultParser.RunSummary summary = PlaywrightResultParser.parse(dir.resolve("bad.json"));

        assertNotNull(summary);
        assertEquals(0, summary.total());
    }

    @Test
    void aNullPathIsTolerated() {
        assertEquals(0, PlaywrightResultParser.parse(null).total());
    }

    @Test
    void stripAnsiHandlesNullAndPlainText() {
        assertNull(PlaywrightResultParser.stripAnsi(null));
        assertEquals("plain", PlaywrightResultParser.stripAnsi("plain"));
    }
}
