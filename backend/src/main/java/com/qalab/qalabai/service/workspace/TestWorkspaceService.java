package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.GeneratedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TestWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(TestWorkspaceService.class);

    private final WorkspaceManager workspaceManager;

    public TestWorkspaceService(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public String writeTestFiles(Long projectId, List<GeneratedTest> tests) {
        if (projectId == null || tests == null || tests.isEmpty()) {
            return null;
        }

        ProjectContext context = workspaceManager.getProjectContext(projectId);
        Path root = Paths.get(context.getWorkspacePath());
        Path testsDir = root.resolve("tests");
        Path pagesDir = root.resolve("pages");

        try {
            Files.createDirectories(testsDir);
            Files.createDirectories(pagesDir);

            for (GeneratedTest test : tests) {
                String fileName = resolveFileName(test);
                if (test.getTestCode() != null && !test.getTestCode().isBlank()) {
                    Files.writeString(testsDir.resolve(fileName), test.getTestCode());
                }
                writePageObject(pagesDir, test.getPageObjectCode());
            }

            log.info("Wrote {} test files to {}", tests.size(), testsDir);
            return context.getWorkspacePath();
        } catch (Exception e) {
            log.error("Failed to write test files to workspace: {}", e.getMessage());
            throw new RuntimeException("Failed to write test files to workspace: " + e.getMessage(), e);
        }
    }

    private void writePageObject(Path pagesDir, String pageObjectCode) throws IOException {
        if (pageObjectCode == null || pageObjectCode.isBlank()) {
            return;
        }
        String className = extractClassName(pageObjectCode);
        if (className != null) {
            Files.writeString(pagesDir.resolve(className + ".ts"), pageObjectCode);
        }
    }

    private String extractClassName(String code) {
        Pattern pattern = Pattern.compile("export\\s+class\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String resolveFileName(GeneratedTest test) {
        if (test.getTestFileName() != null && !test.getTestFileName().isBlank()) {
            return test.getTestFileName();
        }
        String base = test.getScenarioName() == null ? "test" : test.getScenarioName().trim();
        String fileName = base
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (fileName.isBlank()) {
            fileName = "test";
        }
        return fileName + ".spec.ts";
    }
}
