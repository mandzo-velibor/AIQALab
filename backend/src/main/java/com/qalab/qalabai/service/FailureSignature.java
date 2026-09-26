package com.qalab.qalabai.service;

import com.qalab.qalabai.model.TestCaseResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Groups a run's failures by signature, so a bug report describes one bug rather than
 * one run.
 *
 * <p>The report used to be generated once per execution from the whole-run console
 * output. Twenty failing tests produced one report titled after the test file, with
 * {@code LOCATOR_FAILURE} and {@code Unknown error} — which is not a bug report, and is
 * why the generated report was not one of the user's known bugs. Grouping by signature
 * fixes both ends: twenty genuinely different failures produce twenty reports, and
 * twenty tests that tripped the same assertion produce one.</p>
 *
 * <p>The signature is built from the spec file plus a <em>normalised</em> error message.
 * Normalisation matters as much as grouping: the same assertion failing twice usually
 * differs in a line number, a duration, a path or a timestamp, and an un-normalised key
 * would report the same bug as new on every run — the exact behaviour the
 * deduplication key exists to prevent.</p>
 */
public final class FailureSignature {

    /**
     * Playwright timings, hex blobs, quoted ids, absolute paths, source positions.
     *
     * <p>Each pattern swallows the connective word in front of the value
     * ({@code at 01:02:03}, {@code at line 12}). Removing only the number would leave
     * the word behind, and "…failed at" and "…failed" would then hash differently — the
     * same bug re-filed on every run, which is the exact failure this class exists to
     * prevent. The asymmetry is silent: nothing errors, the dedup simply never fires.</p>
     */
    private static final String CONNECTIVE = "(?:\\bat|\\bafter|\\bsince|\\bon|\\bin|\\bfrom|\\bwithin)\\s+";
    private static final Pattern VOLATILE = Pattern.compile(
            CONNECTIVE + "\\d{1,2}:\\d{2}(:\\d{2})?(\\.\\d+)?\\b"                  // at 01:02:03.456
                    + "|" + CONNECTIVE + "\\d+\\b"                                        // at 12
                    + "|" + CONNECTIVE + "line\\s+\\d+\\b"                              // at line 12
                    + "|\\bline\\s+\\d+\\b"                                           // line 12
                    + "|\\b\\d+(\\.\\d+)?\\s*(ms|s|sec|secs|seconds|min|mins)\\b"     // 30000 ms
                    + "|0x[0-9a-fA-F]+"                                                    // addresses
                    + "|\\b[0-9a-fA-F]{8,}\\b"                                          // hashes, ids
                    + "|/\\S+"                                                             // absolute paths
                    + "|\\(\\d+\\)"                                                     // call site
                    + "|\\b[A-Za-z]:\\\\\\S+"                                           // windows paths
                    + "|\\d{1,2}:\\d{2}:\\d{2}(\\.\\d+)?"                            // bare timestamps
                    , Pattern.CASE_INSENSITIVE);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private static final List<String> TRAILING_CONNECTIVES =
            List.of(" at ", " after ", " since ", " on ", " in ", " from ", " within ", " line ");

    private FailureSignature() {
    }

    /** @return the grouping key for one failing test. Never null. */
    public static String of(TestCaseResult test) {
        return of(test.getSpecFile(), test.getTestTitle(), test.getErrorMessage());
    }

    /**
     * @param file    the spec the test came from
     * @param title   the test title
     * @param error   the error message, which carries the assertion
     */
    public static String of(String file, String title, String error) {
        String normalisedFile = normalisePath(file);
        String normalisedError = normaliseError(error);
        // The title is deliberately *not* part of the key. Two tests with different
        // names that assert the same thing are the same bug, and including the title
        // would re-report it whenever someone renames a test.
        String basis = normalisedFile + "|" + normalisedError;
        if (basis.equals("|")) {
            // Nothing usable to key on: fall back to the title rather than collapsing
            // every such failure into one bucket.
            basis = normalisedFile + "|" + normaliseText(title);
        }
        return sha256(basis);
    }

    /**
     * Strips the parts of an error that change between runs while keeping the parts
     * that identify the bug. The assertion text, the locator and the element name all
     * survive; the line number, the duration and the timestamp do not.
     */
    static String normaliseError(String error) {
        if (error == null || error.isBlank()) {
            return "";
        }
        // Only the first non-empty line: a stack trace's later frames name different
        // helper files for the same failure.
        String firstMeaningfulLine = null;
        for (String line : error.split("\\R")) {
            if (!line.isBlank()) {
                firstMeaningfulLine = line;
                break;
            }
        }
        if (firstMeaningfulLine == null) {
            return "";
        }
        String stripped = VOLATILE.matcher(firstMeaningfulLine).replaceAll(" ");
        return normaliseText(dropTrailingConnective(stripped));
    }

    /**
     * Removes a connective left stranded at the end after a volatile value was removed:
     * "…failed at line 88" becomes "…failed at", which would not match "…failed".
     *
     * <p>A safety net for the pattern set above rather than a replacement for it — a
     * connective stranded in the middle is harmless, because it is the same on both
     * sides, but one stranded at the end is exactly the asymmetry that silently breaks
     * deduplication.</p>
     */
    private static String dropTrailingConnective(String value) {
        String result = value.strip();
        while (true) {
            String lower = result.toLowerCase(Locale.ROOT);
            boolean trimmed = false;
            for (String connective : TRAILING_CONNECTIVES) {
                if (lower.endsWith(connective)) {
                    result = result.substring(0, result.length() - connective.length()).strip();
                    trimmed = true;
                    break;
                }
            }
            if (!trimmed) {
                return result;
            }
        }
    }

    private static String normaliseText(String value) {
        if (value == null) {
            return "";
        }
        return NON_ALNUM.matcher(WHITESPACE.matcher(value).replaceAll(" ").toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .trim();
    }

    private static String normalisePath(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        // Keep the file name only: an absolute path in the key would make the same spec
        // a different bug on every machine and every checkout.
        String normalised = file.replace('\\', '/');
        int slash = normalised.lastIndexOf('/');
        return (slash >= 0 ? normalised.substring(slash + 1) : normalised).toLowerCase(Locale.ROOT);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String part = Integer.toHexString(0xff & b);
                if (part.length() == 1) {
                    hex.append('0');
                }
                hex.append(part);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * A group of failures that share a signature.
     *
     * @param signature the deduplication key
     * @param tests     every failing test in the group, in run order
     */
    public record Group(String signature, List<TestCaseResult> tests) {

        public TestCaseResult representative() {
            return tests.get(0);
        }

        public int size() {
            return tests.size();
        }

        /** The titles involved, for a report that says how widespread the failure is. */
        public String describeTitles() {
            return String.join(", ", tests.stream()
                    .map(TestCaseResult::getTestTitle)
                    .filter(t -> t != null && !t.isBlank())
                    .distinct()
                    .toList());
        }
    }

    /**
     * Groups a run's results by signature. Only failures are grouped — a passed test is
     * not a bug and must never produce a report.
     *
     * <p>Order follows first appearance, so the report list reads in the order the user
     * would hit the failures.</p>
     */
    public static List<Group> groupFailing(List<TestCaseResult> results) {
        Map<String, List<TestCaseResult>> bySignature = new LinkedHashMap<>();
        if (results != null) {
            for (TestCaseResult test : results) {
                if (test == null || !"failed".equalsIgnoreCase(test.getStatus())) {
                    continue;
                }
                bySignature.computeIfAbsent(of(test), key -> new ArrayList<>()).add(test);
            }
        }
        return bySignature.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }
}
