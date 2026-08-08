package com.qalab.qalabai.service.workspace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of collecting artifacts for an execution. Optional fields are only
 * present when the corresponding artifact was found in the target workspace.
 */
public class ArtifactResult {

    private final String screenshot;
    private final String video;
    private final String trace;
    private final String log;
    private final String artifactDir;
    private final int screenshotCount;

    private ArtifactResult(String screenshot, String video, String trace, String log,
                           String artifactDir, int screenshotCount) {
        this.screenshot = screenshot;
        this.video = video;
        this.trace = trace;
        this.log = log;
        this.artifactDir = artifactDir;
        this.screenshotCount = screenshotCount;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public String getVideo() {
        return video;
    }

    public String getTrace() {
        return trace;
    }

    public String getLog() {
        return log;
    }

    public String getArtifactDir() {
        return artifactDir;
    }

    public int getScreenshotCount() {
        return screenshotCount;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (screenshot != null) map.put("screenshot", screenshot);
        if (video != null) map.put("video", video);
        if (trace != null) map.put("trace", trace);
        if (log != null) map.put("log", log);
        if (artifactDir != null) map.put("artifactDir", artifactDir);
        return map;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String screenshot;
        private String video;
        private String trace;
        private String log;
        private String artifactDir;
        private int screenshotCount;

        public Builder screenshot(String path) {
            this.screenshot = path;
            this.screenshotCount++;
            return this;
        }

        public Builder video(String path) {
            this.video = path;
            return this;
        }

        public Builder trace(String path) {
            this.trace = path;
            return this;
        }

        public Builder log(String path) {
            this.log = path;
            return this;
        }

        public Builder artifactDir(String path) {
            this.artifactDir = path;
            return this;
        }

        public int screenshotCount() {
            return screenshotCount;
        }

        public ArtifactResult build() {
            return new ArtifactResult(screenshot, video, trace, log, artifactDir, screenshotCount);
        }
    }
}
