package com.qalab.qalabai.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BrowserChannel;
import com.qalab.qalabai.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The error path of {@code BrowserTool.execute} is what a user meets when a page is down,
 * and it is the path that decides whether the failure is legible. Two properties matter:
 * a navigation failure is reported as an error map rather than thrown (so the caller can
 * show it), and the page slot is released either way — a leak here permanently shrinks the
 * browser's capacity, which is the failure B-028's semaphore exists to prevent.
 */
class BrowserToolErrorPathTest {

    /** A session manager whose newPage succeeds but whose page throws on use. */
    private BrowserTool toolWithFailingPage(RuntimeException failure) throws Exception {
        Page page = mock(Page.class);
        when(page.navigate(any())).thenThrow(failure);
        when(page.context()).thenReturn(mock(BrowserContext.class));

        BrowserContext context = mock(BrowserContext.class);
        when(context.newPage()).thenReturn(page);
        Browser browser = mock(Browser.class);
        when(browser.isConnected()).thenReturn(true);
        when(browser.newContext()).thenReturn(context);

        BrowserSessionManager sessions = mock(BrowserSessionManager.class);
        when(sessions.newPage()).thenReturn(page);

        BrowserTool tool = new BrowserTool(sessions);
        var field = BrowserTool.class.getDeclaredField("screenshotsDir");
        field.setAccessible(true);
        field.set(tool, System.getProperty("java.io.tmpdir"));
        return tool;
    }

    @Test
    void aNavigationFailureIsReturnedAsAnErrorMapRatherThanThrown() throws Exception {
        BrowserTool tool = toolWithFailingPage(
                new RuntimeException("net::ERR_NAME_NOT_RESOLVED"));

        Object result = tool.execute(new ToolContext().put("url", "https://nope.invalid"));

        assertThat(result)
                .as("ExplorerService reads an error key out of this map; throwing instead "
                        + "would surface as an opaque failure with no URL attached")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsKey("error");
        assertThat((String) map.get("error")).contains("ERR_NAME_NOT_RESOLVED");
        assertThat(map.get("url"))
                .as("the failing URL is the one piece of context that makes the error actionable")
                .isEqualTo("https://nope.invalid");
    }

    @Test
    void aFailedRunStillReleasesTheBrowserPageSlot() throws Exception {
        BrowserSessionManager sessions = mock(BrowserSessionManager.class);
        Page page = mock(Page.class);
        when(page.navigate(any())).thenThrow(new RuntimeException("boom"));
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(sessions.newPage()).thenReturn(page);

        BrowserTool tool = new BrowserTool(sessions);
        var field = BrowserTool.class.getDeclaredField("screenshotsDir");
        field.setAccessible(true);
        field.set(tool, System.getProperty("java.io.tmpdir"));

        tool.execute(new ToolContext().put("url", "https://example.test"));

        // The permit must come back on the failure path. A leak here is invisible until the
        // fourth concurrent call blocks for 60s and times out, by which point it looks like
        // an unrelated hang.
        org.mockito.Mockito.verify(sessions).closePage(page);
    }

    @Test
    void aSessionFailureIsAlsoReportedAsAnErrorMap() throws Exception {
        BrowserSessionManager sessions = mock(BrowserSessionManager.class);
        when(sessions.newPage()).thenThrow(new IllegalStateException("no Chromium on this host"));

        BrowserTool tool = new BrowserTool(sessions);
        var field = BrowserTool.class.getDeclaredField("screenshotsDir");
        field.setAccessible(true);
        field.set(tool, System.getProperty("java.io.tmpdir"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                tool.execute(new ToolContext().put("url", "https://example.test"));

        assertThat((String) result.get("error")).contains("no Chromium");
    }

    /**
     * The success path, against a real Chromium. Most of what {@code execute} does is
     * page interaction, so a mocked page proves nothing about it — the counts and the
     * screenshot are the product of talking to a browser, and a stub would return whatever
     * the test author already believed.
     */
    @Test
    void aRealPageYieldsItsTitleElementCountsAndAScreenshot() throws Exception {
        BrowserSessionManager sessions = new BrowserSessionManager(2, 60, true);
        BrowserTool tool = new BrowserTool(sessions);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("b036-browsertool");
        var field = BrowserTool.class.getDeclaredField("screenshotsDir");
        field.setAccessible(true);
        field.set(tool, dir.toString());

        try {
            Object result = tool.execute(new ToolContext().put("url",
                    "data:text/html,<html><head><title>Inventory</title></head><body>"
                            + "<h1>Stock</h1>"
                            + "<button>Add</button><button>Remove</button>"
                            + "<input type=text><input type=password>"
                            + "<a href=/x>One</a><a href=/y>Two</a>"
                            + "<form></form></body></html>"));

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) result;
            assertThat(map).containsEntry("title", "Inventory");
            assertThat(map.get("buttonCount")).as("two buttons were rendered").isEqualTo(2);
            assertThat(map.get("inputCount")).as("two inputs were rendered").isEqualTo(2);
            assertThat(map.get("linkCount")).isEqualTo(2);
            assertThat(map.get("formCount")).isEqualTo(1);
            assertThat((String) map.get("html")).contains("<title>Inventory</title>");

            java.nio.file.Path shot = java.nio.file.Path.of((String) map.get("screenshotPath"));
            assertThat(java.nio.file.Files.exists(shot))
                    .as("the screenshot on disk is the artifact the report links to")
                    .isTrue();
            assertThat(java.nio.file.Files.size(shot)).isGreaterThan(0L);
        } finally {
            sessions.close();
        }
    }
}
