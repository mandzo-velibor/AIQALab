package com.qalab.qalabai.service.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-030's contract is that Allure is offered, never imposed. Three things therefore have
 * to hold, and none of them is "Allure works":
 *
 * <ol>
 *   <li>a workspace without {@code allure-playwright} behaves exactly as it did before —
 *       no reporter, no directory, no failure;</li>
 *   <li>a workspace that has it gets the reporter added to the <em>companion</em> config,
 *       so the user's own config is still untouched;</li>
 *   <li>publishing is safe to call unconditionally: a run must not fail because an
 *       optional report could not be produced.</li>
 * </ol>
 */
class AllureReportServiceTest {

    private final AllureReportService service = new AllureReportService(true);

    private static void installFakePackage(Path workspace, String name) throws Exception {
        Path pkg = workspace.resolve("node_modules").resolve(name).resolve("package.json");
        Files.createDirectories(pkg.getParent());
        Files.writeString(pkg, "{\"name\":\"" + name + "\",\"version\":\"0.0.0\"}");
    }

    @Test
    void aWorkspaceWithoutAllureIsNotAvailable(@TempDir Path workspace) {
        assertThat(service.isAvailable(workspace))
                .as("most workspaces will not have it, and that must be a normal, quiet state")
                .isFalse();
    }

    @Test
    void aDeclaredDependencyThatDidNotInstallCountsAsUnavailable(@TempDir Path workspace)
            throws Exception {
        // package.json can list a package that is not on disk — a failed install, a pruned
        // node_modules, a cached lockfile. Adding the reporter then would fail mid-run, so
        // presence is decided by the directory rather than by the declaration.
        Files.writeString(workspace.resolve("package.json"),
                "{\"devDependencies\":{\"allure-playwright\":\"^3.0.0\"}}");
        assertThat(service.isAvailable(workspace)).isFalse();
    }

    @Test
    void aWorkspaceWithThePackageIsAvailable(@TempDir Path workspace) throws Exception {
        installFakePackage(workspace, "allure-playwright");
        assertThat(service.isAvailable(workspace)).isTrue();
    }

    @Test
    void theFeatureCanBeSwitchedOffEntirely(@TempDir Path workspace) throws Exception {
        installFakePackage(workspace, "allure-playwright");
        assertThat(new AllureReportService(false).isAvailable(workspace))
                .as("a user who does not want Allure should be able to say so in config")
                .isFalse();
    }

    @Test
    void publishingWithoutResultsIsANoOpAndDoesNotFail(@TempDir Path workspace,
                                                      @TempDir Path artifacts) {
        AllureReportService.AllureOutcome outcome = service.publish(workspace, artifacts);

        // The execution already ran. This must never be able to turn a passing run into a
        // failing one, so the whole call is safe to make on every run.
        assertThat(outcome.resultsPath()).isNull();
        assertThat(outcome.reportPath()).isNull();
    }

    @Test
    void publishingWithoutAnArtifactDirectoryIsSafe(@TempDir Path workspace) {
        assertThat(service.publish(workspace, null).reportPath()).isNull();
        assertThat(service.publish(null, null).available()).isFalse();
    }

    @Test
    void resultsAreMovedIntoTheArtifactDirectoryAndNotLeftInTheWorkspace(@TempDir Path workspace,
                                                                        @TempDir Path artifacts)
            throws Exception {
        // A run's evidence belongs with the rest of that run's evidence, and the workspace
        // should be left as it was found.
        Path results = workspace.resolve(AllureReportService.RESULTS_SUBDIR);
        Files.createDirectories(results);
        Files.writeString(results.resolve("abc-result.json"), "{\"name\":\"should log in\"}");
        Files.createDirectories(results.resolve("attachments"));
        Files.writeString(results.resolve("attachments").resolve("shot.png"), "PNG");

        AllureReportService.AllureOutcome outcome = service.publish(workspace, artifacts);

        assertThat(outcome.available()).isTrue();
        assertThat(outcome.resultsPath())
                .as("the results must survive even when rendering is impossible")
                .isNotNull();
        assertThat(Files.exists(Path.of(outcome.resultsPath()).resolve("abc-result.json")))
                .isTrue();
        assertThat(Files.exists(Path.of(outcome.resultsPath())
                .resolve("attachments").resolve("shot.png")))
                .as("attachments are the screenshots and traces; losing them makes the "
                        + "results far less useful than Allure's own output")
                .isTrue();
        assertThat(Files.exists(results))
                .as("the workspace must not be left littered with a results directory")
                .isFalse();
    }

    /**
     * A service whose renderer is forced to fail, so the "no renderer on this machine" path
     * is exercised whatever happens to be installed on the host. Depending on the real
     * binary would make this test pass or fail depending on the developer.
     */
    private static final class NoRenderer extends AllureReportService {
        NoRenderer() {
            super(true);
        }

        @Override
        java.util.Optional<String> render(java.nio.file.Path workspace, java.nio.file.Path results,
                                          java.nio.file.Path output) {
            return java.util.Optional.empty();
        }
    }

    @Test
    void anUnrenderableResultSetStillExplainsItself(@TempDir Path workspace,
                                                   @TempDir Path artifacts) throws Exception {
        Path results = workspace.resolve(AllureReportService.RESULTS_SUBDIR);
        Files.createDirectories(results);
        Files.writeString(results.resolve("r.json"), "{}");

        AllureReportService.AllureOutcome outcome = new NoRenderer().publish(workspace, artifacts);

        // This is the path a real user hits when they have allure-playwright but not the
        // report generator. Silence there would leave them with a directory and no idea
        // what to do with it.
        assertThat(outcome.renderNote()).isNotNull();
        assertThat(outcome.renderNote()).contains("allure generate");
    }
}
