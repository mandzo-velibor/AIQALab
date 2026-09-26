package com.qalab.qalabai.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BrowserChannel;
import com.qalab.qalabai.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The inline screenshot is the one place base64 is still justified, and only because
 * artifacts are not served over HTTP yet. Unbounded, it is a payload that grows with page
 * height — and it used to be persisted into a varchar(10000) column, which no full-page
 * PNG could fit. These tests pin both halves: the cap, and that an oversized screenshot
 * still leaves a usable file on disk.
 */
class BrowserToolScreenshotTest {

    private BrowserTool toolWith(Path screenshotsDir) throws Exception {
        BrowserSessionManager sessions = mock(BrowserSessionManager.class);
        BrowserTool tool = new BrowserTool(sessions);
        var field = BrowserTool.class.getDeclaredField("screenshotsDir");
        field.setAccessible(true);
        field.set(tool, screenshotsDir.toString());
        return tool;
    }

    private static void wireScreenshotsDir(BrowserTool tool, Path dir) throws Exception {
        var field = BrowserTool.class.getDeclaredField("screenshotsDir");
        field.setAccessible(true);
        field.set(tool, dir.toString());
    }

    /** A file that is mostly incompressible, so its size is not secretly deflated on read. */
    private static Path writeLargeFile(Path dir, int sizeBytes) throws Exception {
        Path p = dir.resolve("shot-" + sizeBytes + ".bin");
        byte[] payload = new byte[sizeBytes];
        new java.util.Random(sizeBytes).nextBytes(payload);
        Files.write(p, payload);
        return p;
    }

    @Test
    void oversizedScreenshotIsNotInlinedButIsStillKeptOnDisk(@TempDir Path dir) throws Exception {
        BrowserTool tool = toolWith(dir);
        Path big = writeLargeFile(dir, 600 * 1024);

        String inline = invokeInline(tool, big);

        assertThat(inline)
                .as("a screenshot past the inline limit must not be returned as base64")
                .isNull();
        assertThat(Files.exists(big))
                .as("the artifact must survive: the report and CLI read the file, not the preview")
                .isTrue();
    }

    @Test
    void screenshotUnderTheLimitIsInlined(@TempDir Path dir) throws Exception {
        BrowserTool tool = toolWith(dir);
        Path small = writeLargeFile(dir, 8 * 1024);

        String inline = invokeInline(tool, small);

        assertThat(inline).isNotNull();
        assertThat(java.util.Base64.getDecoder().decode(inline)).hasSize(8 * 1024);
    }

    @Test
    void aMissingScreenshotFileDegradesToNullRatherThanThrowing(@TempDir Path dir) throws Exception {
        BrowserTool tool = toolWith(dir);
        String inline = invokeInline(tool, dir.resolve("does-not-exist.png"));
        assertThat(inline)
                .as("a missing file must not fail the whole analysis over a preview image")
                .isNull();
    }

    @Test
    void inlineLimitIsBoundedIndependentlyOfPageSize() {
        // Guards the constant itself: raising it re-introduces the oversized-payload bug.
        int limit = inlineLimitBytes();
        assertThat(limit)
                .as("the inline screenshot cap must stay small enough for an API response")
                .isLessThanOrEqualTo(2 * 1024 * 1024);
    }

    private static int inlineLimitBytes() {
        try {
            var f = BrowserTool.class.getDeclaredField("MAX_INLINE_SCREENSHOT_BYTES");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String invokeInline(BrowserTool tool, Path file) throws Exception {
        var m = BrowserTool.class.getDeclaredMethod("inlineScreenshotIfSmallEnough", Path.class);
        m.setAccessible(true);
        return (String) m.invoke(tool, file);
    }
}
