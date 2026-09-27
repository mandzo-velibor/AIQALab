package com.qalab.qalabai.service.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Optional Allure output, for workspaces that already use Allure.
 *
 * <p>This is deliberately additive and never imposed. The tool does not add
 * {@code allure-playwright} to anybody's {@code package.json}: a QA tool that silently
 * introduces a dependency into the user's project is a QA tool that breaks their
 * {@code npm ci}. If the workspace already has the reporter, the run picks it up; if not,
 * nothing here changes and the bespoke report stands on its own.
 *
 * <p>Two Allure generations exist and they differ in a way that decides this design.
 * Allure Report 2 ({@code allure-commandline}) is Java-based and downloads a JAR, which
 * makes it a poor dependency for a container that may have no JVM on the report path.
 * Allure Report 3 is pure JS and is invoked as {@code npx allure generate}. So the
 * renderer prefers Report 3, falls back to a Report 2 {@code allure} on PATH, and if
 * neither is available it leaves the raw results in place and says which command would
 * render them. Failing to render is not a failed run: the tests already ran and the
 * results are already on disk.
 */
@Service
public class AllureReportService {

    private static final Logger log = LoggerFactory.getLogger(AllureReportService.class);

    /** Where the reporter writes during a run, relative to the workspace. */
    static final String RESULTS_SUBDIR = ".qalab/allure-results";
    private static final String REPORT_DIR_NAME = "allure-report";
    private static final long RENDER_TIMEOUT_SECONDS = 120;

    private final boolean enabled;

    public AllureReportService(@Value("${qalab.allure.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Outcome of publishing Allure output for one run.
     *
     * @param resultsPath  the copied {@code allure-results} directory, if there was one
     * @param reportPath   the rendered report directory, if rendering succeeded
     * @param renderNote   human-readable explanation when the report was not rendered
     * @param available    whether the workspace had {@code allure-playwright} at all
     */
    public record AllureOutcome(String resultsPath, String reportPath, String renderNote,
                                boolean available) {

        public boolean hasReport() {
            return reportPath != null;
        }
    }

    /**
     * Whether the workspace already depends on {@code allure-playwright}.
     *
     * <p>Resolved from disk rather than by reading {@code package.json}, because a declared
     * dependency that failed to install would make the reporter fail mid-run. What matters
     * is whether the package can actually be loaded.
     */
    public boolean isAvailable(Path workspace) {
        if (!enabled || workspace == null) {
            return false;
        }
        return Files.isDirectory(workspace.resolve("node_modules"))
                && hasPackage(workspace, "allure-playwright");
    }

    private boolean hasPackage(Path workspace, String name) {
        Path pkg = workspace.resolve("node_modules").resolve(name).resolve("package.json");
        return Files.isRegularFile(pkg);
    }

    /** Absolute path the reporter should write to, or null when Allure is not in play. */
    public Path resultsDir(Path workspace) {
        return workspace == null ? null : workspace.resolve(RESULTS_SUBDIR);
    }

    /**
     * Moves the run's Allure results into the artifact directory and renders a report.
     *
     * <p>Never throws. The artifact directory is the right home for this because it is the
     * one place the run's evidence is already collected, is already referenced by
     * {@code report.html}, and is already copied by whoever exports a run.
     *
     * @return an outcome describing what happened; {@code available=false} when the
     *         workspace does not use Allure, in which case the caller should do nothing
     */
    public AllureOutcome publish(Path workspace, Path artifactDir) {
        if (workspace == null || artifactDir == null) {
            return new AllureOutcome(null, null, null, false);
        }
        Path source = workspace.resolve(RESULTS_SUBDIR);
        if (!Files.isDirectory(source)) {
            return new AllureOutcome(null, null, null, isAvailable(workspace));
        }

        try {
            Path target = artifactDir.resolve("allure-results");
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                deleteRecursively(target);
            }
            copyRecursively(source, target);
            deleteRecursively(source);
            // Leave the workspace as it was found. The dot-directory exists only to hold
            // results during a run, so an empty one left behind is litter.
            deleteIfEmpty(source.getParent());

            Optional<String> rendered = render(workspace, target, artifactDir.resolve(REPORT_DIR_NAME));
            return new AllureOutcome(
                    target.toAbsolutePath().toString(),
                    rendered.orElse(null),
                    rendered.isPresent() ? null
                            : "Allure results were collected, but no Allure renderer was found. "
                            + "Run `npx allure generate " + target + "` to render them.",
                    true);
        } catch (IOException e) {
            // The run itself succeeded; losing the optional report must not change that.
            log.warn("Could not publish Allure output: {}", e.getMessage());
            return new AllureOutcome(null, null,
                    "Allure results could not be collected: " + e.getMessage(), true);
        }
    }

    /**
     * Renders the results, preferring Allure Report 3 (no JVM) over Report 2 (Java).
     *
     * <p>Package-private and overridable so a test can exercise the "no renderer" path
     * deterministically. Whether an {@code allure} binary happens to be on the machine
     * running the suite is not something a test should depend on — and on a developer box
     * it frequently is.
     */
    Optional<String> render(Path workspace, Path results, Path output) {
        List<String> generateArgs =
                List.of("generate", results.toString(), "-o", output.toString(), "--clean");

        List<List<String>> attempts = new ArrayList<>();
        // Allure Report 3 ships as the `allure` npm package and needs no Java at all,
        // which is the only reason this is viable inside a container. The locally installed
        // binary is preferred over `npx` so a render never reaches for the network: `npx
        // allure` would otherwise download a package mid-report, which fails outright
        // offline and is not something a test run should do unasked.
        Path local = localAllureBinary(workspace);
        if (local != null) {
            List<String> command = new ArrayList<>();
            command.add(local.toAbsolutePath().toString());
            command.addAll(generateArgs);
            attempts.add(command);
        } else {
            List<String> command = new ArrayList<>(List.of("npx", "--no-install", "allure"));
            command.addAll(generateArgs);
            attempts.add(command);
        }
        // Allure Report 2, if the user already has the Java-based CLI on PATH.
        List<String> legacy = new ArrayList<>(List.of("allure"));
        legacy.addAll(generateArgs);
        attempts.add(legacy);

        for (List<String> command : attempts) {
            try {
                Files.createDirectories(output);
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(workspace.toFile());
                pb.redirectErrorStream(true);
                // Do not let a missing renderer leave a stray node process behind.
                pb.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                Process process = pb.start();
                String output_ = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                boolean finished = process.waitFor(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    log.info("Allure render timed out after {}s", RENDER_TIMEOUT_SECONDS);
                    continue;
                }
                if (process.exitValue() == 0 && indexHtmlExists(output)) {
                    log.info("Allure report rendered at {}", output);
                    return Optional.of(output.toAbsolutePath().toString());
                }
                log.info("Allure render via '{}' did not produce a report (exit {}): {}",
                        String.join(" ", command), process.exitValue(), tail(output_));
            } catch (IOException e) {
                // A missing binary lands here, which is the expected case on a clean box.
                log.debug("Allure renderer '{}' unavailable: {}", command.get(0), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** A renderer that exits 0 without writing a report is not a report. */
    private boolean indexHtmlExists(Path output) {
        return Files.isRegularFile(output.resolve("index.html"));
    }

    /** The workspace's own Allure Report 3 binary, or null when it is not installed. */
    private Path localAllureBinary(Path workspace) {
        Path bin = workspace.resolve("node_modules").resolve(".bin").resolve("allure");
        return Files.isExecutable(bin) ? bin : null;
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(trimmed.length() - 200);
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Removes a directory only when it has nothing left in it. */
    private void deleteIfEmpty(Path dir) {
        try {
            if (dir != null && Files.isDirectory(dir)) {
                try (var entries = Files.list(dir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(dir);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Could not remove empty {}: {}", dir, e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Exposed for the report renderer, which shows the path rather than linking it. */
    public static String reportDirName() {
        return REPORT_DIR_NAME;
    }
}
