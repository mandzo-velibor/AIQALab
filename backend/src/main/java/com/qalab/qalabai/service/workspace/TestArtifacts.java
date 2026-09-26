package com.qalab.qalabai.service.workspace;

import java.util.List;

/**
 * One test's evidence, copied into the artifact directory with the association intact.
 *
 * <p>This exists because the previous collection flattened everything: every screenshot
 * became {@code screenshot.png} / {@code screenshot-2.png} and <em>every trace became the
 * same {@code trace.zip}</em>, copied with {@code REPLACE_EXISTING}. A run with two
 * failing tests therefore kept one trace and silently destroyed the other — and lost the
 * link between evidence and test entirely, so a report could not say which screenshot
 * belonged to which failure.</p>
 *
 * @param ordinal  position in the run, so the directory name sorts the way the tests ran
 * @param slug     filesystem-safe short form of the test title
 * @param testFile the spec the test came from
 * @param title    the test's title as the model wrote it
 * @param status   passed / failed / skipped
 * @param screenshots absolute paths, in the artifact directory
 * @param videos      absolute paths, in the artifact directory
 * @param traces      absolute paths, in the artifact directory
 * @param dir         the directory holding this test's evidence
 */
public record TestArtifacts(
        int ordinal,
        String slug,
        String testFile,
        String title,
        String status,
        List<String> screenshots,
        List<String> videos,
        List<String> traces,
        String dir
) {

    public boolean hasEvidence() {
        return !screenshots.isEmpty() || !videos.isEmpty() || !traces.isEmpty();
    }

    public String key() {
        return ordinal + "-" + slug;
    }
}
