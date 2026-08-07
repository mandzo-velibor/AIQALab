package com.qalab.qalabai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class QaLabAiApplication {

    private static final Logger log = LoggerFactory.getLogger(QaLabAiApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(QaLabAiApplication.class, args);
        
        startFrontend();
        openBrowser();
    }

    private static void startFrontend() {
        Thread frontendThread = new Thread(() -> {
            try {
                log.info("Starting frontend server...");
                
                File frontendDir = new File(System.getProperty("user.dir")).getParentFile();
                File frontendPath = new File(frontendDir, "frontend");
                
                if (!frontendPath.exists()) {
                    log.warn("Frontend directory not found at: {}", frontendPath.getAbsolutePath());
                    return;
                }
                
                ProcessBuilder pb = new ProcessBuilder("npm", "run", "dev");
                pb.directory(frontendPath);
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

    private static void openBrowser() {
        Thread browserThread = new Thread(() -> {
            try {
                Thread.sleep(3000);
                
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
