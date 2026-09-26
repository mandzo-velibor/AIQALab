package com.qalab.qalabai.config;

import com.qalab.qalabai.service.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Warms up Playwright for existing workspaces after the application is up.
 *
 * <p>This used to be a {@code CommandLineRunner}, which runs <em>before</em> the
 * application is ready to serve traffic. It shells out to {@code npm install} and
 * {@code npx playwright install chromium}, each with a 600 s timeout, so with N
 * workspaces the app could be unavailable for up to 10xN minutes on boot — and on
 * a slow or throttled cloud volume a single hung npm could consume the whole
 * budget. This is a likely contributor to the "500 while installing Playwright"
 * symptom seen on the Oracle Cloud deployment.</p>
 *
 * <p>It now runs on {@link ApplicationReadyEvent} on a background daemon thread:
 * the port is already accepting requests, and a failure in one workspace neither
 * aborts boot nor blocks the others. Set
 * {@code qalab.auto-install-playwright=false} to skip the warm-up entirely — the
 * recommended setting when browsers are baked into the image.</p>
 */
@Component
public class PlaywrightSetupConfig {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightSetupConfig.class);

    private final ExecutorService warmupExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qalab-playwright-warmup");
                t.setDaemon(true);
                return t;
            });

    private final WorkspaceManager workspaceManager;
    private final boolean autoInstall;
    private final String workspacesDir;

    public PlaywrightSetupConfig(WorkspaceManager workspaceManager,
                                 @Value("${qalab.auto-install-playwright:true}") boolean autoInstall,
                                 @Value("${qalab.workspaces-dir:./workspaces}") String workspacesDir) {
        this.workspaceManager = workspaceManager;
        this.autoInstall = autoInstall;
        this.workspacesDir = workspacesDir;
    }

    /**
     * Kicks off the warm-up once the app is serving. Returns immediately; all work
     * happens on the background executor.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!autoInstall) {
            log.info("Playwright warm-up disabled (qalab.auto-install-playwright=false). "
                    + "Workspaces will be prepared lazily on first use.");
            return;
        }
        warmupExecutor.submit(() -> {
            try {
                int prepared = warmUpWorkspaces();
                log.info("Playwright warm-up finished: {} workspace(s) processed", prepared);
            } catch (Exception e) {
                // Never propagate: the app is already serving and must stay up.
                log.warn("Playwright warm-up failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Prepares every {@code project-*} workspace. Each workspace is isolated: a
     * failure is logged and the scan continues.
     *
     * @return number of workspaces successfully prepared
     */
    int warmUpWorkspaces() {
        return warmUpWorkspaces(workspacesDir, workspaceManager);
    }

    static int warmUpWorkspaces(String dir, WorkspaceManager manager) {
        Path workspacesPath = Paths.get(dir);
        if (!Files.isDirectory(workspacesPath)) {
            log.info("No workspaces directory at {}. Skipping Playwright warm-up.", dir);
            return 0;
        }

        List<Path> projects;
        try (Stream<Path> entries = Files.list(workspacesPath)) {
            projects = entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("project-"))
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("Could not scan {} for Playwright warm-up: {}", dir, e.getMessage());
            return 0;
        }

        if (projects.isEmpty()) {
            log.info("No project workspaces found in {}. Skipping Playwright warm-up.", dir);
            return 0;
        }

        log.info("Warming up Playwright for {} workspace(s) in the background", projects.size());
        int ok = 0;
        for (Path project : projects) {
            long start = System.currentTimeMillis();
            try {
                // prepareWorkspace is itself idempotent (node_modules + .playwright-ready
                // marker checks), so repeat boots are cheap.
                manager.prepareWorkspace(project.toString());
                ok++;
                log.info("Playwright ready for {} in {}ms", project.getFileName(),
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                // One broken workspace must not stop the others or the application.
                log.warn("Playwright warm-up failed for {}: {}", project, e.getMessage());
            }
        }
        return ok;
    }

    /** Exposed for tests and for a future readiness probe. */
    public boolean isWarmupEnabled() {
        return autoInstall;
    }
}
