package com.qalab.qalabai.service;

import com.qalab.qalabai.model.TestCaseResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deduplication key is the whole feature: a known bug that comes back as new on every
 * run is how a bug list becomes a list nobody reads.
 *
 * <p>That makes the <em>normalisation</em> the load-bearing part, not the hashing. The
 * same assertion failing twice usually differs in a line number, a duration, a path or a
 * timestamp, and an un-normalised key would report it as new every time — the exact
 * behaviour the key exists to prevent. So the tests below attack the volatile parts
 * specifically, and there is a test that the genuinely distinguishing part survives.
 */
class FailureSignatureTest {

    private TestCaseResult test(String file, String title, String error) {
        TestCaseResult row = new TestCaseResult();
        row.setSpecFile(file);
        row.setTestTitle(title);
        row.setErrorMessage(error);
        row.setStatus("failed");
        return row;
    }

    // ---- normalisation: the same bug must hash the same ----

    @Test
    void aLineNumberDoesNotMakeItANewBug() {
        String a = FailureSignature.of("login.spec.ts", "Login", "Error: expect(x).toBe(y) at line 12");
        String b = FailureSignature.of("login.spec.ts", "Login", "Error: expect(x).toBe(y) at line 47");

        assertEquals(a, b, "the same assertion on a different line is the same bug");
    }

    @Test
    void aDurationDoesNotMakeItANewBug() {
        String a = FailureSignature.of("a.spec.ts", "T", "Error: locator.click: Timeout 30000ms exceeded");
        String b = FailureSignature.of("a.spec.ts", "T", "Error: locator.click: Timeout 45210ms exceeded");

        assertEquals(a, b, "a retry that took longer is still the same bug");
    }

    @Test
    void aPlaywrightTimestampDoesNotMakeItANewBug() {
        String a = FailureSignature.of("a.spec.ts", "T", "Error: call log at 01:02:03.456 - waiting for locator");
        String b = FailureSignature.of("a.spec.ts", "T", "Error: call log at 14:51:09.001 - waiting for locator");

        assertEquals(a, b);
    }

    @Test
    void anAbsolutePathDoesNotMakeItANewBug() {
        // The same spec in two checkouts, on two machines, is the same bug.
        String a = FailureSignature.of("/Users/alice/app/tests/login.spec.ts", "T", "Error: expect(x) failed");
        String b = FailureSignature.of("/opt/ci/build/tests/login.spec.ts", "T", "Error: expect(x) failed");

        assertEquals(a, b);
    }

    @Test
    void aTestRenameDoesNotMakeItANewBug() {
        // The title is deliberately excluded from the key, so renaming a test does not
        // turn a tracked bug into a new one.
        String a = FailureSignature.of("login.spec.ts", "Login with empty password", "Error: expect(x) failed");
        String b = FailureSignature.of("login.spec.ts", "Login with blank password field", "Error: expect(x) failed");

        assertEquals(a, b, "renaming a test must not re-file a known bug");
    }

    @Test
    void stackFramesAfterTheFirstLineAreIgnored() {
        // Later frames name different helper files for the same failure.
        String a = FailureSignature.of("a.spec.ts", "T", "Error: expect(x).toBe(y)\n  at Object.a (a.ts:1)\n  at b (b.ts:2)");
        String b = FailureSignature.of("a.spec.ts", "T", "Error: expect(x).toBe(y)\n  at Object.a (a.ts:99)\n  at c (c.ts:3)");

        assertEquals(a, b);
    }

    // ---- and the distinguishing parts must survive ----

    @Test
    void aDifferentAssertionIsADifferentBug() {
        String a = FailureSignature.of("login.spec.ts", "T", "Error: expect(locator).toBeVisible() failed");
        String b = FailureSignature.of("login.spec.ts", "T", "Error: expect(page).toHaveTitle() failed");

        assertNotEquals(a, b, "different assertions are different bugs");
    }

    @Test
    void aDifferentSpecIsADifferentBug() {
        String a = FailureSignature.of("login.spec.ts", "T", "Error: expect(x) failed");
        String b = FailureSignature.of("checkout.spec.ts", "T", "Error: expect(x) failed");

        assertNotEquals(a, b);
    }

    @Test
    void twoDifferentFailuresInOneSpecStayDistinct() {
        // This is the case the user actually hit: one spec, several genuine bugs.
        String a = FailureSignature.of("login.spec.ts", "T", "Error: expect(#email).toBeVisible() failed");
        String b = FailureSignature.of("login.spec.ts", "T", "Error: expect(#flash).toContain('Invalid') failed");

        assertNotEquals(a, b);
    }

    @Test
    void theSameAssertionInTwoSpecsStaysDistinct() {
        String a = FailureSignature.of("login.spec.ts", "T", "Error: expect(x) failed");
        String b = FailureSignature.of("signup.spec.ts", "T", "Error: expect(x) failed");

        assertNotEquals(a, b);
    }

    @Test
    void aMissingErrorStillProducesAUsableKey() {
        String key = FailureSignature.of("a.spec.ts", "Some title", null);

        assertEquals(64, key.length(), "must still be a sha-256 hex digest");
    }

    @Test
    void testsWithNothingDistinguishingThemDoNotCollapseIntoOne() {
        // No file, no error, only titles: keying on the title is the only thing left that
        // tells them apart, and collapsing them would lose a real failure.
        String a = FailureSignature.of(null, "First failing test", null);
        String b = FailureSignature.of(null, "Second failing test", null);

        assertNotEquals(a, b);
    }

    // ---- grouping ----

    @Test
    void identicalFailuresCollapseIntoOneGroup() {
        List<FailureSignature.Group> groups = FailureSignature.groupFailing(List.of(
                test("login.spec.ts", "Login fails A", "Error: expect(#flash).toBeVisible() failed"),
                test("login.spec.ts", "Login fails B", "Error: expect(#flash).toBeVisible() failed at line 88")));

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).size(), "both failures belong to the same bug");
    }

    @Test
    void distinctFailuresProduceDistinctGroups() {
        List<FailureSignature.Group> groups = FailureSignature.groupFailing(List.of(
                test("login.spec.ts", "A", "Error: expect(#email).toBeVisible() failed"),
                test("login.spec.ts", "B", "Error: expect(#password).toBeVisible() failed"),
                test("login.spec.ts", "C", "Error: expect(page).toHaveTitle() failed")));

        assertEquals(3, groups.size());
    }

    @Test
    void passingAndSkippedTestsNeverProduceAGroup() {
        // A passing test is not a bug and must never become a report.
        TestCaseResult passed = test("a.spec.ts", "Fine", null);
        passed.setStatus("passed");
        TestCaseResult skipped = test("a.spec.ts", "Skipped", null);
        skipped.setStatus("skipped");
        TestCaseResult flakyPass = test("a.spec.ts", "Flaky", null);
        flakyPass.setStatus("passed");

        assertTrue(FailureSignature.groupFailing(List.of(passed, skipped, flakyPass)).isEmpty());
    }

    @Test
    void groupsFollowFirstAppearanceSoTheListReadsInRunOrder() {
        // The third failure shares the first's spec *and* assertion, so it joins that
        // group even though it appeared last. A different spec would be a different
        // bug, which is asserted separately.
        List<FailureSignature.Group> groups = FailureSignature.groupFailing(List.of(
                test("a.spec.ts", "First", "Error: expect(#email).toBeVisible() failed"),
                test("b.spec.ts", "Second", "Error: expect(#flash).toBeVisible() failed"),
                test("a.spec.ts", "Third", "Error: expect(#email).toBeVisible() failed")));

        assertEquals(2, groups.size());
        assertEquals("a.spec.ts", groups.get(0).representative().getSpecFile());
        assertEquals("b.spec.ts", groups.get(1).representative().getSpecFile());
        assertEquals("Third", groups.get(0).tests().get(1).getTestTitle(),
                "the third failure joined the first group rather than starting a new one");
    }

    @Test
    void nullAndEmptyInputsAreHandled() {
        assertTrue(FailureSignature.groupFailing(null).isEmpty());
        assertTrue(FailureSignature.groupFailing(List.of()).isEmpty());
        assertTrue(FailureSignature.groupFailing(java.util.Arrays.asList((TestCaseResult) null)).isEmpty());
    }

    @Test
    void aGroupDescribesHowManyTestsShareTheFailure() {
        List<FailureSignature.Group> groups = FailureSignature.groupFailing(List.of(
                test("a.spec.ts", "Login A", "Error: expect(x) failed"),
                test("a.spec.ts", "Login B", "Error: expect(x) failed")));

        assertEquals(2, groups.get(0).size());
        assertTrue(groups.get(0).describeTitles().contains("Login A"));
        assertTrue(groups.get(0).describeTitles().contains("Login B"));
    }
}
