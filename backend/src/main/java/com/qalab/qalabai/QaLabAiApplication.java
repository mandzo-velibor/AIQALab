package com.qalab.qalabai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class QaLabAiApplication {

    private static final Logger log = LoggerFactory.getLogger(QaLabAiApplication.class);

    public static void main(String[] args) {
        loadEnvFile();
        SpringApplication.run(QaLabAiApplication.class, args);
        
        startFrontend();
        openBrowser();
    }

    private static void loadEnvFile() {
        try {
            File envFile = findEnvFile();
            if (envFile != null && envFile.exists()) {
                log.info("Loading environment variables from: {}", envFile.getAbsolutePath());
                try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        
                        int equalsIndex = line.indexOf('=');
                        if (equalsIndex > 0) {
                            String key = line.substring(0, equalsIndex);
                            String value = line.substring(equalsIndex + 1);
                            if (System.getProperty(key) == null && System.getenv(key) == null) {
                                System.setProperty(key, value);
                                log.debug("Set {} from .env", key);
                            }
                        }
                    }
                }
            } else {
                log.warn(".env file not found. Using system environment variables.");
            }
        } catch (Exception e) {
            log.warn("Failed to load .env file: {}", e.getMessage());
        }
    }

    private static File findEnvFile() {
        File currentDir = new File(System.getProperty("user.dir"));
        
        File envFile = new File(currentDir, ".env");
        if (envFile.exists()) return envFile;
        
        envFile = new File(currentDir.getParentFile(), ".env");
        if (envFile.exists()) return envFile;
        
        if (currentDir.getAbsolutePath().contains("backend")) {
            envFile = new File(currentDir.getParentFile().getParentFile(), ".env");
            if (envFile.exists()) return envFile;
        }
        
        return null;
    }

    private static void startFrontend() {
        Thread frontendThread = new Thread(() -> {
            try {
                log.info("Starting frontend server...");
                
                File frontendDir = findFrontendDir();
                if (frontendDir == null || !frontendDir.exists()) {
                    log.warn("Frontend directory not found. Please start frontend manually with: cd frontend && npm run dev");
                    return;
                }
                
                log.info("Frontend directory: {}", frontendDir.getAbsolutePath());
                
                ProcessBuilder pb = new ProcessBuilder("npm", "run", "dev");
                pb.directory(frontendDir);
                pb.inheritIO();
                
                Process process = pb.start();
                log.info("Frontend server started on http://localhost:3000");
                
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log.info("Shutting down frontend server...");
                    process.destroy();
                    try {
                        if (!process.waitFor(5, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                
                process.waitFor();
                
            } catch (IOException | InterruptedException e) {
                log.error("Failed to start frontend server: {}", e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        
        frontendThread.setDaemon(true);
        frontendThread.start();
    }

    private static File findFrontendDir() {
        File currentDir = new File(System.getProperty("user.dir"));
        
        File frontendDir = new File(currentDir.getParentFile(), "frontend");
        if (frontendDir.exists() && new File(frontendDir, "package.json").exists()) {
            return frontendDir;
        }
        
        frontendDir = new File(currentDir, "frontend");
        if (frontendDir.exists() && new File(frontendDir, "package.json").exists()) {
            return frontendDir;
        }
        
        if (currentDir.getAbsolutePath().contains("backend")) {
            frontendDir = new File(currentDir.getParentFile().getParentFile(), "frontend");
            if (frontendDir.exists() && new File(frontendDir, "package.json").exists()) {
                return frontendDir;
            }
        }
        
        return null;
    }

    private static void openBrowser() {
        Thread browserThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
                
                String url = "http://localhost:3000";
                log.info("Opening browser at: {}", url);
                
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                
                if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", "start", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                
                pb.start();
                
            } catch (Exception e) {
                log.warn("Could not open browser automatically. Please open: http://localhost:3000");
            }
        });
        
        browserThread.setDaemon(true);
        browserThread.start();
    }
}
