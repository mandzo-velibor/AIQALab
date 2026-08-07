package com.qalab.qalabai.service.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class GitServiceImpl implements GitService {

    private static final Logger log = LoggerFactory.getLogger(GitServiceImpl.class);

    @Override
    public void cloneRepository(String repositoryUrl, String targetPath) {
        log.info("Cloning repository {} to {}", repositoryUrl, targetPath);

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "clone", repositoryUrl, targetPath);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("Git: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Git clone failed with exit code: " + exitCode);
            }

            log.info("Repository cloned successfully");

        } catch (Exception e) {
            log.error("Failed to clone repository: {}", e.getMessage());
            throw new RuntimeException("Failed to clone repository: " + e.getMessage(), e);
        }
    }

    @Override
    public void pullLatest(String workspacePath) {
        log.info("Pulling latest changes in {}", workspacePath);

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "pull");
            pb.directory(Paths.get(workspacePath).toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("Git: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Git pull failed with exit code: " + exitCode);
            }

            log.info("Pull completed successfully");

        } catch (Exception e) {
            log.error("Failed to pull latest: {}", e.getMessage());
            throw new RuntimeException("Failed to pull latest: " + e.getMessage(), e);
        }
    }

    @Override
    public String getStatus(String workspacePath) {
        log.info("Getting git status for {}", workspacePath);

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
            pb.directory(Paths.get(workspacePath).toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Git status failed with exit code: " + exitCode);
            }

            return output.toString();

        } catch (Exception e) {
            log.error("Failed to get git status: {}", e.getMessage());
            throw new RuntimeException("Failed to get git status: " + e.getMessage(), e);
        }
    }

    @Override
    public void createBranch(String workspacePath, String branchName) {
        log.info("Creating branch {} in {}", branchName, workspacePath);

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "checkout", "-b", branchName);
            pb.directory(Paths.get(workspacePath).toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("Git: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Git branch creation failed with exit code: " + exitCode);
            }

            log.info("Branch created successfully");

        } catch (Exception e) {
            log.error("Failed to create branch: {}", e.getMessage());
            throw new RuntimeException("Failed to create branch: " + e.getMessage(), e);
        }
    }
}
