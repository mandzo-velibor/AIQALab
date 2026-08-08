package com.qalab.qalabai.service.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Collects artifacts produced by a Playwright run (screenshots, traces, videos,
 * console logs) from the target workspace and copies them into a stable
 * per-execution directory so reports outlive the next test run.
 *
 * <p>Artifacts are copied — the target workspace keeps ownership of its own
 * files; the Core only stores derived copies.</p>
 */
@Service
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    @Value("${qalab.artifacts-dir:./artifacts}")
    private String artifactsDir;

    /**
     * Collects Playwright artifacts from {@code workspace}/test-results into
     * {@code <artifactsDir>/<executionId>/} and writes the console log.
     *
     * @return map with optional keys: screenshot, video, trace, log, artifactDir
     */
    public ArtifactResult collect(String workspace, Long executionId, String consoleLog) {
        ArtifactResult.Builder result = ArtifactResult.builder();
        if (workspace == null || workspace.isBlank()) {
            return result.build();
        }
        try {
            Path targetDir = Paths.get(artifactsDir, "execution-" + executionId);
            Files.createDirectories(targetDir);

            Path testResults = Paths.get(workspace).resolve("test-results");
            if (Files.isDirectory(testResults)) {
                try (Stream<Path> walk = Files.walk(testResults)) {
                    List<Path> files = walk.filter(Files::isRegularFile).toList();
                    files.stream()
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .forEach(p -> copy(p, targetDir, result));
                }
            }

            if (consoleLog != null && !consoleLog.isBlank()) {
                Path logFile = targetDir.resolve("console.log");
                Files.writeString(logFile, consoleLog);
                result.log(logFile.toAbsolutePath().toString());
            }

            result.artifactDir(targetDir.toAbsolutePath().toString());
            log.info("Collected artifacts for execution {} in {}", executionId, targetDir);
        } catch (IOException e) {
            log.warn("Failed to collect artifacts for execution {}: {}", executionId, e.getMessage());
        }
        return result.build();
    }

    private void copy(Path source, Path targetDir, ArtifactResult.Builder result) {
        String name = source.getFileName().toString().toLowerCase();
        String destName = source.getFileName().toString();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            destName = "screenshot" + (result.screenshotCount() == 0 ? "" : "-" + (result.screenshotCount() + 1))
                    + source.getFileName().toString().substring(name.length() - 4);
        } else if (name.endsWith(".zip") && name.contains("trace")) {
            destName = "trace.zip";
        } else if (name.endsWith(".webm") || name.endsWith(".mp4")) {
            destName = "video" + name.substring(name.length() - 5);
        }
        try {
            Files.copy(source, targetDir.resolve(destName),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (destName.startsWith("screenshot")) {
                result.screenshot(targetDir.resolve(destName).toAbsolutePath().toString());
            } else if (destName.equals("trace.zip")) {
                result.trace(targetDir.resolve(destName).toAbsolutePath().toString());
            } else if (destName.startsWith("video")) {
                result.video(targetDir.resolve(destName).toAbsolutePath().toString());
            }
        } catch (IOException e) {
            log.warn("Failed to copy artifact {}: {}", source, e.getMessage());
        }
    }
}
