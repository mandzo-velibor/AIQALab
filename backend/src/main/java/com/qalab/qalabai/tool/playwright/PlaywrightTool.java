package com.qalab.qalabai.tool.playwright;

import com.qalab.qalabai.tool.Tool;
import com.qalab.qalabai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PlaywrightTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightTool.class);

    @Value("${qalab.tests-dir:./tests}")
    private String testsDir;

    @Override
    public String getName() {
        return "PlaywrightTool";
    }

    @Override
    public Object execute(ToolContext context) {
        String testFile = context.getString("testFile");
        boolean runAll = context.get("runAll") != null && (Boolean) context.get("runAll");

        log.info("PlaywrightTool executing: testFile={}, runAll={}", testFile, runAll);

        try {
            Path testsPath = Paths.get(testsDir);
            if (!Files.exists(testsPath)) {
                Files.createDirectories(testsPath);
            }

            ProcessBuilder pb;
            if (runAll) {
                pb = new ProcessBuilder("npx", "playwright", "test");
            } else if (testFile != null && !testFile.isBlank()) {
                pb = new ProcessBuilder("npx", "playwright", "test", testFile);
            } else {
                return Map.of("error", "No test file specified and runAll is false");
            }

            pb.directory(testsPath.toFile());
            pb.redirectErrorStream(true);

            long startTime = System.currentTimeMillis();
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("Playwright: {}", line);
                }
            }

            boolean completed = process.waitFor(60, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (!completed) {
                process.destroyForcibly();
                return Map.of(
                        "status", "TIMEOUT",
                        "duration", duration,
                        "output", output.toString()
                );
            }

            int exitCode = process.exitValue();
            String status = exitCode == 0 ? "PASSED" : "FAILED";

            Map<String, Object> result = new HashMap<>();
            result.put("status", status);
            result.put("duration", duration);
            result.put("exitCode", exitCode);
            result.put("output", output.toString());

            log.info("Playwright execution completed: status={}, duration={}ms", status, duration);
            return result;

        } catch (Exception e) {
            log.error("Playwright execution failed: {}", e.getMessage());
            return Map.of(
                    "status", "ERROR",
                    "error", e.getMessage()
            );
        }
    }
}
