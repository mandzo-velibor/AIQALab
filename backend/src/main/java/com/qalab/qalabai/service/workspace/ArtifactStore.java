package com.qalab.qalabai.service.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
import java.util.stream.Stream;

/**
 * Collects artifacts produced by a Playwright run (screenshots, traces, videos)
 * into {@code <artifactsDir>/execution-<id>/} and writes the console log.
 *
 * <p>Evidence is copied <strong>per test</strong>, into {@code tests/<ordinal>-<slug>/}.
 * It used to be flattened into the execution directory with numbered names, which had
 * two consequences worth stating plainly:</p>
 *
 * <ul>
 *   <li>Every trace was copied to the same {@code trace.zip} with
 *       {@code REPLACE_EXISTING}, so a run with two failing tests kept one trace and
 *       <strong>silently destroyed the other</strong>. A trace is the single most
 *       useful artifact for reconstructing a failure.</li>
 *   <li>Nothing recorded which evidence belonged to which test, so no report could
 *       show a user the screenshot for <em>their</em> failure. The run told you three
 *       tests failed and showed one screenshot.</li>
 * </ul>
 *
 * <p>When no structured per-test results are available the old flat behaviour is used,
 * so a run that predates the JSON reporter still collects whatever exists.</p>
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    @Value("${qalab.artifacts-dir:./artifacts}")
    private String artifactsDir;

    private static final String TESTS_DIR = "tests";

    public ArtifactResult collect(String workspace, Long executionId, String consoleOutput) {
        return collect(workspace, executionId, consoleOutput, List.of());
    }

    /**
     * @param perTest one entry per test, carrying the absolute paths of that test's
     *                attachments as reported by Playwright's JSON reporter. Copied into
     *                a per-test subdirectory so the association survives.
     */
    public ArtifactResult collect(String workspace, Long executionId, String consoleOutput,
                                  List<TestArtifacts> perTest) {
        ArtifactResult.Builder result = ArtifactResult.builder();
        if (executionId == null) {
            return result.build();
        }
        Path targetDir = Paths.get(artifactsDir, "execution-" + executionId);
        try {
            Files.createDirectories(targetDir);

            List<TestArtifacts> collected = perTest == null ? List.of() : collectPerTest(perTest, targetDir);
            result.perTest(collected);

            if (!collected.isEmpty()) {
                // Evidence is already placed per test. Do not also flatten it: that is
                // what destroyed traces, and it would duplicate every screenshot.
                seedLegacyFieldsFromFirstFailure(result, collected);
            } else if (workspace != null) {
                collectFlat(workspace, targetDir, result);
            }

            if (consoleOutput != null) {
                Path logFile = targetDir.resolve("console.log");
                Files.writeString(logFile, consoleOutput);
                result.log(logFile.toAbsolutePath().toString());
            }
            result.artifactDir(targetDir.toAbsolutePath().toString());
            log.info("Collected artifacts for execution {} in {} ({} test(s) with evidence)",
                    executionId, targetDir, collected.size());
        } catch (Exception e) {
            log.warn("Failed to collect artifacts for execution {}: {}", executionId, e.getMessage());
        }
        return result.build();
    }

    private List<TestArtifacts> collectPerTest(List<TestArtifacts> perTest, Path targetDir) {
        List<TestArtifacts> collected = new ArrayList<>();
        for (TestArtifacts test : perTest) {
            Path testDir = targetDir.resolve(TESTS_DIR).resolve(test.key());
            List<String> screenshots = copyAll(test.screenshots(), testDir, test.key());
            List<String> videos = copyAll(test.videos(), testDir, test.key());
            List<String> traces = copyAll(test.traces(), testDir, test.key());
            if (screenshots.isEmpty() && videos.isEmpty() && traces.isEmpty()) {
                continue;
            }
            collected.add(new TestArtifacts(test.ordinal(), test.slug(), test.testFile(), test.title(),
                    test.status(), screenshots, videos, traces, testDir.toString()));
        }
        return collected;
    }

    /** @return paths relative to the artifact directory, e.g. {@code tests/0-login/shot.png} */
    private List<String> copyAll(List<String> sources, Path testDir, String testKey) {
        List<String> copied = new ArrayList<>();
        if (sources == null) {
            return copied;
        }
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            Path from = Paths.get(source);
            if (!Files.isRegularFile(from)) {
                // The reporter can reference a file Playwright decided not to keep, and
                // a workspace may have been cleaned up between the run and the report.
                log.debug("Skipping missing artifact {}", from);
                continue;
            }
            try {
                Files.createDirectories(testDir);
                // The original file name is kept: it is what identifies the artifact
                // (test-failed-1.png) and each test has its own directory now, so the
                // names cannot collide.
                Path to = unique(testDir.resolve(from.getFileName().toString()));
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                // Recorded relative to the artifact directory, not absolutely: report.html
                // lives in that directory and has to keep working when the whole folder is
                // moved, emailed or archived. Absolute paths would break on the first move.
                copied.add(TESTS_DIR + "/" + testKey + "/" + to.getFileName());
            } catch (IOException e) {
                log.warn("Failed to copy artifact {}: {}", from, e.getMessage());
            }
        }
        return copied;
    }

    /** Avoids overwriting when one test somehow produced two files of the same name. */
    private Path unique(Path desired) {
        if (!Files.exists(desired)) {
            return desired;
        }
        String name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            Path candidate = desired.resolveSibling(base + "-" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return desired;
    }

    /**
     * Keeps the single-artifact fields populated for existing consumers, pointing at the
     * first failure — the one a user is most likely to open.
     */
    private void seedLegacyFieldsFromFirstFailure(ArtifactResult.Builder result, List<TestArtifacts> collected) {
        TestArtifacts first = collected.stream()
                .filter(t -> "failed".equals(t.status()))
                .findFirst()
                .orElse(collected.get(0));
        if (!first.screenshots().isEmpty()) {
            result.screenshot(resolve(targetDirOf(first), first.screenshots().get(0)));
        }
        if (!first.videos().isEmpty()) {
            result.video(resolve(targetDirOf(first), first.videos().get(0)));
        }
        if (!first.traces().isEmpty()) {
            result.trace(resolve(targetDirOf(first), first.traces().get(0)));
        }
        // The counts describe what was *collected*, not how many times the legacy
        // single-value fields happened to be assigned — otherwise a run with two traces
        // would report one.
        result.screenshotCount(collected.stream().mapToInt(t -> t.screenshots().size()).sum());
        result.videoCount(collected.stream().mapToInt(t -> t.videos().size()).sum());
        result.traceCount(collected.stream().mapToInt(t -> t.traces().size()).sum());
    }

    /** {@code test.dir} is {@code <artifactDir>/tests/<key>}, so the root is two levels up. */
    private static Path targetDirOf(TestArtifacts test) {
        return Path.of(test.dir()).getParent().getParent();
    }

    private static String resolve(Path artifactDir, String relative) {
        try {
            return artifactDir.resolve(relative).toAbsolutePath().toString();
        } catch (RuntimeException e) {
            return relative;
        }
    }

    /** Fallback for runs with no structured results: flatten, newest name wins. */
    private void collectFlat(String workspace, Path targetDir, ArtifactResult.Builder result) {
        Path testResults = Paths.get(workspace).resolve("test-results");
        if (!Files.isDirectory(testResults)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(testResults)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path p : files) {
                copy(p, targetDir, result);
            }
        } catch (IOException e) {
            log.warn("Failed to walk {}: {}", testResults, e.getMessage());
        }
    }

    private void copy(Path source, Path targetDir, ArtifactResult.Builder result) {
        String name = source.getFileName().toString().toLowerCase();
        String destName = source.getFileName().toString();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            destName = "screenshot" + (result.screenshotCount() == 0 ? "" : "-" + (result.screenshotCount() + 1))
                    + source.getFileName().toString().substring(name.length() - 4);
        } else if (name.endsWith(".zip") && name.contains("trace")) {
            // Numbered rather than overwritten: several traces in one run used to
            // collapse into a single file.
            destName = "trace-" + (result.traceCount() + 1) + ".zip";
        } else if (name.endsWith(".webm") || name.endsWith(".mp4")) {
            destName = "video-" + (result.videoCount() + 1) + name.substring(name.length() - 5);
        }
        try {
            Files.createDirectories(targetDir);
            Files.copy(source, targetDir.resolve(destName), StandardCopyOption.REPLACE_EXISTING);
            if (destName.startsWith("screenshot")) {
                result.screenshot(targetDir.resolve(destName).toAbsolutePath().toString());
            } else if (destName.startsWith("trace-")) {
                result.trace(targetDir.resolve(destName).toAbsolutePath().toString());
            } else if (destName.startsWith("video-")) {
                result.video(targetDir.resolve(destName).toAbsolutePath().toString());
            }
        } catch (IOException e) {
            log.warn("Failed to copy artifact {}: {}", source, e.getMessage());
        }
    }

    /** Exposed for the report renderer, which needs the same key shape. */
    public static Map<String, TestArtifacts> indexByKey(List<TestArtifacts> tests) {
        Map<String, TestArtifacts> index = new LinkedHashMap<>();
        for (TestArtifacts test : tests) {
            index.put(test.key(), test);
        }
        return index;
    }
}
