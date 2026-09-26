package com.qalab.qalabai.service.workspace;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final int videoCount;
    private final int traceCount;
    private final List<TestArtifacts> perTest;

    private ArtifactResult(String screenshot, String video, String trace, String log,
                           String artifactDir, int screenshotCount, int videoCount,
                           int traceCount, List<TestArtifacts> perTest) {
        this.screenshot = screenshot;
        this.video = video;
        this.trace = trace;
        this.log = log;
        this.artifactDir = artifactDir;
        this.screenshotCount = screenshotCount;
        this.videoCount = videoCount;
        this.traceCount = traceCount;
        this.perTest = perTest == null ? List.of() : List.copyOf(perTest);
    }

    /**
     * Per-test evidence, in run order. Empty when the run produced no structured
     * results, which is the signal to degrade rather than to invent.
     */
    public List<TestArtifacts> getPerTest() {
        return perTest;
    }

    public int getVideoCount() {
        return videoCount;
    }

    public int getTraceCount() {
        return traceCount;
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
        if (!perTest.isEmpty()) {
            List<Map<String, Object>> tests = new java.util.ArrayList<>();
            for (TestArtifacts t : perTest) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", t.key());
                one.put("ordinal", t.ordinal());
                one.put("testFile", t.testFile());
                one.put("title", t.title());
                one.put("status", t.status());
                if (!t.screenshots().isEmpty()) one.put("screenshots", t.screenshots());
                if (!t.videos().isEmpty()) one.put("videos", t.videos());
                if (!t.traces().isEmpty()) one.put("traces", t.traces());
                one.put("dir", t.dir());
                tests.add(one);
            }
            map.put("tests", tests);
        }
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
        private int videoCount;
        private int traceCount;
        private List<TestArtifacts> perTest = List.of();

        public Builder screenshot(String path) {
            this.screenshot = path;
            this.screenshotCount++;
            return this;
        }

        public Builder video(String path) {
            this.video = path;
            this.videoCount++;
            return this;
        }

        public Builder trace(String path) {
            this.trace = path;
            this.traceCount++;
            return this;
        }

        public Builder perTest(List<TestArtifacts> perTest) {
            this.perTest = perTest;
            return this;
        }

        public Builder screenshotCount(int count) {
            this.screenshotCount = count;
            return this;
        }

        public Builder videoCount(int count) {
            this.videoCount = count;
            return this;
        }

        public Builder traceCount(int count) {
            this.traceCount = count;
            return this;
        }

        public int videoCount() {
            return videoCount;
        }

        public int traceCount() {
            return traceCount;
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
            return new ArtifactResult(screenshot, video, trace, log, artifactDir, screenshotCount,
                    videoCount, traceCount, perTest);
        }
    }
}
