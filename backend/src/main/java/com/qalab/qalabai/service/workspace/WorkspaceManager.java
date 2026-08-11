package com.qalab.qalabai.service.workspace;

import com.qalab.qalabai.agent.ProjectContext;
import com.qalab.qalabai.model.GeneratedTest;
import com.qalab.qalabai.model.Project;
import com.qalab.qalabai.repository.ProjectRepository;
import com.qalab.qalabai.service.git.GitService;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.playwright.PlaywrightTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WorkspaceManager implements WorkspaceProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final ProjectRepository projectRepository;
    private final GitService gitService;
    private final PlaywrightTool playwrightTool;

    @Value("${qalab.workspaces-dir:./workspaces}")
    private String workspacesDir;

    public WorkspaceManager(ProjectRepository projectRepository,
                            GitService gitService,
                            PlaywrightTool playwrightTool) {
        this.projectRepository = projectRepository;
        this.gitService = gitService;
        this.playwrightTool = playwrightTool;
    }

    @Override
    public String getWorkspace(ProjectContext project) {
        if (project.getWorkspacePath() != null && !project.getWorkspacePath().isBlank()) {
            return project.getWorkspacePath();
        }
        if (project.getDatabaseId() != null) {
            Path legacy = Paths.get(workspacesDir, "project-" + project.getDatabaseId());
            return legacy.toAbsolutePath().normalize().toString();
        }
        throw new RuntimeException("No workspace path available for project: "
                + project.getProjectId() + ". Provide a workspacePath to execute tests.");
    }

    @Override
    public void prepareWorkspace(ProjectContext project) {
        String workspace = getWorkspace(project);
        prepareWorkspace(workspace);
    }

    @Override
    public String writeTests(ProjectContext project, List<GeneratedTest> tests) {
        if (tests == null || tests.isEmpty()) {
            return null;
        }
        String workspace = getWorkspace(project);
        Path root = Paths.get(workspace);
        Path testsDir = root.resolve("tests");
        Path pagesDir = root.resolve("pages");

        try {
            Files.createDirectories(testsDir);
            Files.createDirectories(pagesDir);

            Map<String, List<String>> pageObjectVariants = new LinkedHashMap<>();

            for (GeneratedTest test : tests) {
                String fileName = resolveFileName(test);
                if (test.getTestCode() == null || test.getTestCode().isBlank()) {
                    continue;
                }
                String testCode = normalizeCode(test.getTestCode());
                String pageObjectCode = normalizeCode(test.getPageObjectCode());
                String className = extractClassName(pageObjectCode);
                if (className != null) {
                    String testBase = fileName.replaceFirst("\\.spec\\.[tj]s$", "");
                    String uniquePageFile = className + "_" + testBase + ".ts";
                    Files.writeString(pagesDir.resolve(uniquePageFile), pageObjectCode);
                    testCode = rewritePageObjectImport(testCode, className, uniquePageFile);
                    pageObjectVariants.computeIfAbsent(className, k -> new ArrayList<>()).add(pageObjectCode);
                }
                Files.writeString(testsDir.resolve(fileName), testCode);
            }

            for (Map.Entry<String, List<String>> entry : pageObjectVariants.entrySet()) {
                Files.writeString(pagesDir.resolve(entry.getKey() + ".ts"),
                        mergePageObjects(entry.getKey(), entry.getValue()));
            }

            log.info("Wrote {} test files to {}", tests.size(), testsDir);
            return workspace;
        } catch (Exception e) {
            log.error("Failed to write test files to workspace: {}", e.getMessage());
            throw new RuntimeException("Failed to write test files to workspace: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll) {
        return execute(project, testFile, runAll, null);
    }

    @Override
    public Map<String, Object> execute(ProjectContext project, String testFile, boolean runAll, String testType) {
        String workspace = getWorkspace(project);
        ToolContext context = new ToolContext().put("workspacePath", workspace);

        String marker = testTypeMarker(testType);
        if (marker != null && runAll) {
            List<String> matching = findSpecFilesByType(workspace, marker);
            if (matching.isEmpty()) {
                return Map.of(
                        "status", "PASSED",
                        "duration", 0L,
                        "output", "No tests of type '" + marker + "' found in workspace " + workspace
                );
            }
            context.put("testFiles", matching);
        } else {
            if (testFile != null && !testFile.isBlank()) {
                context.put("testFile", testFile);
            }
            context.put("runAll", runAll);
        }
        return toMap(playwrightTool.execute(context));
    }

    /**
     * Deterministic, LLM-free selection of the spec files whose name carries the
     * given type marker (e.g. {@code .ui.}, {@code .e2e.}, {@code .api.}). Paths
     * are returned relative to the workspace so Playwright resolves them from its
     * working directory.
     */
    private List<String> findSpecFilesByType(String workspace, String marker) {
        Path testsDir = Paths.get(workspace, "tests");
        List<String> files = new ArrayList<>();
        if (!Files.exists(testsDir)) {
            return files;
        }
        try (var stream = Files.walk(testsDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches(".*\\." + Pattern.quote(marker) + "\\.spec\\.[tj]s$"))
                    .map(p -> {
                        Path relative = testsDir.getParent().relativize(p);
                        return relative.toString().replace(File.separatorChar, '/');
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list spec files by type '{}' in {}: {}", marker, workspace, e.getMessage());
        }
        return files;
    }

    /** Maps a user-provided test type (ALL/UI/E2E/API) to the deterministic file marker. */
    public static String testTypeMarker(String testType) {
        if (testType == null) {
            return null;
        }
        String lower = testType.trim().toLowerCase();
        if (lower.contains("api")) {
            return "api";
        }
        if (lower.contains("e2e")) {
            return "e2e";
        }
        if (lower.contains("ui")) {
            return "ui";
        }
        return null;
    }

    @Override
    public Map<String, String> collectArtifacts(ProjectContext project) {
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ERROR");
        result.put("error", "Unexpected result from PlaywrightTool");
        return result;
    }

    /**
     * Points the import of the given page object class inside a test file to the
     * test-specific page object file, so the test always resolves the locators
     * it was generated against instead of a shared file that sibling tests may
     * have overwritten. When the import cannot be matched, the code is returned
     * unchanged and the merged shared page object acts as a fallback.
     */
    private String rewritePageObjectImport(String testCode, String className, String uniquePageFile) {
        Pattern pattern = Pattern.compile(
                "(?s)(import\\s*\\{[^}]*\\b" + Pattern.quote(className) + "\\b[^}]*\\}\\s*from\\s*['\"])([^'\"]+)(['\"])");
        Matcher matcher = pattern.matcher(testCode);
        if (matcher.find()) {
            String rewritten = matcher.group(1) + "../pages/" + uniquePageFile + matcher.group(3);
            return testCode.substring(0, matcher.start()) + rewritten + testCode.substring(matcher.end());
        }
        return testCode;
    }

    /**
     * Merges the per-test page object variants that target the same page class
     * into one shared file holding the union of locators and methods, deduplicating
     * by declaration. This guarantees that a test whose import could not be
     * rewritten still resolves every member it references.
     */
    private String mergePageObjects(String className, List<String> variants) {
        Set<String> fields = new LinkedHashSet<>();
        Map<String, String> constructorLines = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        String constructorSignature = null;

        for (String variant : variants) {
            String inner = stripOuterClass(variant);
            for (String segment : splitTopLevelSegments(inner)) {
                String trimmed = segment.strip();
                if (trimmed.startsWith("readonly ") && trimmed.endsWith(";")) {
                    fields.add(trimmed);
                } else if (trimmed.startsWith("constructor")) {
                    int open = trimmed.indexOf('{');
                    if (constructorSignature == null && open > 0) {
                        constructorSignature = trimmed.substring(0, open).strip();
                    }
                    String body = trimmed.substring(trimmed.indexOf('{') + 1, trimmed.lastIndexOf('}'));
                    for (String line : body.split("\n")) {
                        String t = line.strip();
                        if (t.isEmpty()) {
                            continue;
                        }
                        String key = t.startsWith("this.")
                                ? t.substring(0, t.indexOf('=') > 0 ? t.indexOf('=') : t.length())
                                : t;
                        constructorLines.putIfAbsent(key, t);
                    }
                } else {
                    methods.putIfAbsent(methodName(trimmed), trimmed);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("import { Page, Locator } from '@playwright/test';\n\n");
        sb.append("export class ").append(className).append(" {\n");
        for (String field : fields) {
            sb.append("  ").append(field).append("\n");
        }
        sb.append("\n  ")
                .append(constructorSignature != null ? constructorSignature : "constructor(page: Page)")
                .append(" {\n");
        for (String line : constructorLines.values()) {
            sb.append("    ").append(line).append("\n");
        }
        sb.append("  }\n");
        for (String method : methods.values()) {
            sb.append("\n");
            for (String line : method.split("\n")) {
                sb.append("  ").append(line).append("\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String stripOuterClass(String code) {
        int classIdx = code.indexOf("class ");
        if (classIdx < 0) {
            return code;
        }
        int open = code.indexOf('{', classIdx);
        int close = code.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return code;
        }
        return code.substring(open + 1, close);
    }

    private List<String> splitTopLevelSegments(String body) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (String line : body.split("\n")) {
            if (depth == 0 && current.isEmpty() && line.strip().isEmpty()) {
                continue;
            }
            current.append(line).append("\n");
            depth += occurrences(line, '{') - occurrences(line, '}');
            if (depth <= 0) {
                segments.add(current.toString().strip());
                current.setLength(0);
                depth = 0;
            }
        }
        if (!current.isEmpty()) {
            segments.add(current.toString().strip());
        }
        return segments;
    }

    private int occurrences(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    private String methodName(String trimmed) {
        int open = trimmed.indexOf('{');
        String signature = (open > 0 ? trimmed.substring(0, open) : trimmed).strip();
        if (signature.startsWith("async ")) {
            signature = signature.substring("async ".length()).strip();
        }
        int paren = signature.indexOf('(');
        String name = paren > 0 ? signature.substring(0, paren) : signature;
        String[] parts = name.split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1] : name;
    }

    /**
     * Decodes double-encoded line breaks (literal "\n" backslash sequences in a
     * single-line string) back into real newlines so the written file is valid
     * source code. No-op when the code already contains real line breaks.
     */
    private String normalizeCode(String code) {
        if (code == null || code.isEmpty() || code.indexOf('\\') < 0) {
            return code;
        }
        int literalNewlines = countOccurrences(code, "\\n");
        int realNewlines = countOccurrences(code, "\n");
        if (literalNewlines > 0 && realNewlines == 0) {
            return code
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t");
        }
        return code;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String extractClassName(String code) {
        Pattern pattern = Pattern.compile("export\\s+class\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String resolveFileName(GeneratedTest test) {
        String base;
        if (test.getScenarioName() != null && !test.getScenarioName().isBlank()) {
            base = slug(test.getScenarioName());
        } else if (test.getTestFileName() != null && !test.getTestFileName().isBlank()) {
            base = test.getTestFileName().replaceFirst("\\.spec\\.[tj]s$", "").replaceFirst("\\.[a-z]+$", "");
        } else {
            base = "test";
        }
        String marker = testTypeMarker(test.getTestType());
        return marker != null ? base + "." + marker + ".spec.ts" : base + ".spec.ts";
    }

    private static String slug(String raw) {
        String value = raw.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return value.isBlank() ? "test" : value;
    }

    public ProjectContext getProjectContext(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found: " + projectId));

        String workspacePath = project.getWorkspacePath();
        if (workspacePath == null || workspacePath.isBlank()) {
            workspacePath = initializeWorkspace(project);
            project.setWorkspacePath(workspacePath);
            projectRepository.save(project);
        }

        Path normalized = Paths.get(workspacePath);
        if (!normalized.isAbsolute()) {
            normalized = normalized.toAbsolutePath().normalize();
            project.setWorkspacePath(normalized.toString());
            projectRepository.save(project);
        }

        prepareWorkspace(normalized.toString());

        return new ProjectContext(
                project.getId(),
                normalized.toString(),
                project.getBaseUrl(),
                project.getFramework()
        );
    }

    private String initializeWorkspace(Project project) {
        try {
            Path workspacesPath = Paths.get(workspacesDir);
            Files.createDirectories(workspacesPath);

            String projectDirName = "project-" + project.getId();
            Path projectPath = workspacesPath.resolve(projectDirName);

            if (Files.exists(projectPath)) {
                log.info("Workspace already exists: {}", projectPath);
                return projectPath.toString();
            }

            log.info("Initializing workspace for project {}: {}", project.getName(), projectPath);

            if (project.getRepositoryUrl() != null && !project.getRepositoryUrl().isBlank()) {
                gitService.cloneRepository(project.getRepositoryUrl(), projectPath.toString());
            } else {
                Files.createDirectories(projectPath);
                initializeProjectStructure(projectPath);
            }

            return projectPath.toString();

        } catch (Exception e) {
            log.error("Failed to initialize workspace: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize workspace: " + e.getMessage(), e);
        }
    }

    private void initializeProjectStructure(Path projectPath) throws IOException {
        Files.createDirectories(projectPath.resolve("tests"));
        Files.createDirectories(projectPath.resolve("pages"));
        Files.createDirectories(projectPath.resolve("fixtures"));

        String packageJson = """
                {
                  "name": "qa-project",
                  "version": "1.0.0",
                  "scripts": {
                    "test": "playwright test"
                  },
                  "devDependencies": {
                    "@playwright/test": "1.48.0"
                  }
                }
                """;
        Files.writeString(projectPath.resolve("package.json"), packageJson);

        String playwrightConfig = """
                import { defineConfig } from '@playwright/test';
                
                export default defineConfig({
                  testDir: './tests',
                  timeout: 30000,
                  use: {
                    headless: true,
                    screenshot: 'on',
                    video: 'on',
                    trace: 'on',
                  },
                });
                """;
        Files.writeString(projectPath.resolve("playwright.config.ts"), playwrightConfig);

        log.info("Initialized project structure at: {}", projectPath);
    }

    public void validateWorkspace(String workspacePath) {
        Path path = Paths.get(workspacePath);
        if (!Files.exists(path)) {
            throw new RuntimeException("Workspace does not exist: " + workspacePath);
        }
        if (!Files.isDirectory(path)) {
            throw new RuntimeException("Workspace is not a directory: " + workspacePath);
        }
    }

    public void prepareWorkspace(String workspacePath) {
        Path path = Paths.get(workspacePath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                log.warn("Failed to create workspace directory {}: {}", workspacePath, e.getMessage());
            }
        }

        try {
            if (!Files.exists(path.resolve("package.json"))) {
                log.info("No package.json in workspace {}, initializing project structure.", workspacePath);
                initializeProjectStructure(path);
            }

            if (!Files.exists(path.resolve("node_modules"))) {
                log.info("Installing workspace dependencies in {}...", workspacePath);
                runProcess(path.toFile(), new String[]{"npm", "install"}, "npm install");
            }

            if (!Files.exists(path.resolve("node_modules/@playwright/test"))) {
                log.info("@playwright/test missing in workspace {}, installing it...", workspacePath);
                runProcess(path.toFile(), new String[]{"npm", "install", "--no-save", "@playwright/test"},
                        "npm install @playwright/test");
            }

            Path browsersMarker = path.resolve(".playwright-ready");
            if (!Files.exists(browsersMarker)) {
                log.info("Installing Playwright browsers for workspace {}...", workspacePath);
                boolean ok = runProcess(path.toFile(),
                        new String[]{"npx", "playwright", "install", "chromium"},
                        "npx playwright install chromium");
                if (ok) {
                    Files.writeString(browsersMarker, "installed");
                }
            }
        } catch (Exception e) {
            log.warn("Workspace dependency setup failed for {}: {}", workspacePath, e.getMessage());
        }
    }

    private boolean runProcess(File dir, String[] command, String what) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("{} timed out.", what);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("{} failed with exit code {}. Output tail: {}", what, exitCode,
                        output.length() > 800 ? output.substring(output.length() - 800) : output);
                return false;
            }
            log.info("{} completed.", what);
            return true;
        } catch (Exception e) {
            log.warn("{} error: {}", what, e.getMessage());
            return false;
        }
    }
}
