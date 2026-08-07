package com.qalab.qalabai.config;

import com.qalab.qalabai.service.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Configuration
public class PlaywrightSetupConfig {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightSetupConfig.class);

    @Bean
    public CommandLineRunner ensurePlaywrightReady(
            @Value("${qalab.auto-install-playwright:true}") boolean autoInstall,
            @Value("${qalab.workspaces-dir:./workspaces}") String workspacesDir,
            WorkspaceManager workspaceManager) {
        return args -> {
            if (!autoInstall) {
                log.info("Auto-install of Playwright browsers is disabled (qalab.auto-install-playwright=false).");
                return;
            }

            Path workspacesPath = Paths.get(workspacesDir);
            if (!Files.isDirectory(workspacesPath)) {
                log.info("No workspaces directory found at {}. Skipping Playwright setup.", workspacesDir);
                return;
            }

            try (Stream<Path> entries = Files.list(workspacesPath)) {
                entries.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("project-"))
                        .forEach(p -> workspaceManager.prepareWorkspace(p.toString()));
            } catch (Exception e) {
                log.warn("Failed to scan workspaces for Playwright setup: {}", e.getMessage());
            }
        };
    }
}
