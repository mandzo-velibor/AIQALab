package com.qalab.qalabai.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance criterion for B-028 is "N explore calls launch 1 browser, not N" and
 * "Chromium launches successfully". Both are claims about a real browser process, so they
 * are checked against one here — the mocked suite proves the bookkeeping, this proves the
 * process.
 *
 * <p>Skipped when no browser is installed, because a missing download is an environment
 * problem, not a failure of this class. The count is taken from a wrapping launcher, which
 * is the same seam the unit test uses, so the two agree on what "a launch" means.
 */
class BrowserSessionManagerRealBrowserTest {

    private static final String PAGE = "data:text/html,<html><head><title>Reuse</title></head>"
            + "<body><h1>hi</h1><button>Go</button><input type=text></body></html>";

    @BeforeEach
    void requireBrowser() {
        Assumptions.assumeTrue(browserInstalled(),
                "no Playwright browser installed; run `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args='install chromium'`");
    }

    private static boolean browserInstalled() {
        try {
            Playwright p = Playwright.create();
            try {
                p.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)).close();
                return true;
            } finally {
                p.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void threeRealCallsLaunchOneBrowser() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        BrowserSessionManager.Launcher counting = new BrowserSessionManager.Launcher() {
            @Override
            public Playwright newPlaywright() {
                return Playwright.create();
            }

            @Override
            public Browser launch(Playwright playwright, boolean headed, List<String> args) {
                launches.incrementAndGet();
                return playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(!headed).setArgs(args));
            }
        };

        BrowserSessionManager manager = new BrowserSessionManager(4, 300, true, counting);
        try {
            for (int i = 0; i < 3; i++) {
                Page page = manager.newPage();
                try {
                    page.navigate(PAGE);
                    assertThat(page.title()).isEqualTo("Reuse");
                } finally {
                    manager.closePage(page);
                }
            }
            assertThat(launches.get())
                    .as("3 real calls must reuse 1 Chromium process")
                    .isEqualTo(1);
            assertThat(manager.activePageCount())
                    .as("every page must be released, or the cap would shrink over a run")
                    .isZero();
        } finally {
            manager.close();
        }
    }

    @Test
    void anOversizedRealScreenshotIsNotInlinedButIsWrittenToDisk() throws Exception {
        Path dir = Files.createTempDirectory("b028-real");
        BrowserSessionManager manager = new BrowserSessionManager(2, 300, true,
                new BrowserSessionManager.Launcher() {
                    @Override
                    public Playwright newPlaywright() {
                        return Playwright.create();
                    }

                    @Override
                    public Browser launch(Playwright playwright, boolean headed, List<String> args) {
                        return playwright.chromium().launch(
                                new BrowserType.LaunchOptions().setHeadless(!headed).setArgs(args));
                    }
                });
        BrowserTool tool = new BrowserTool(manager);
        var f = BrowserTool.class.getDeclaredField("screenshotsDir");
        f.setAccessible(true);
        f.set(tool, dir.toString());

        try {
            Page page = manager.newPage();
            try {
                // A very tall page: the full-page PNG is far past any sensible inline cap,
                // which is the case the old code turned into a multi-megabyte API payload.
                StringBuilder tall = new StringBuilder(
                        "data:text/html,<html><head><title>Tall</title></head><body>");
                for (int i = 0; i < 400; i++) {
                    tall.append("<div style='height:40px'>row ").append(i).append("</div>");
                }
                tall.append("</body></html>");
                page.navigate(tall.toString());

                var m = BrowserTool.class.getDeclaredMethod(
                        "saveScreenshot", Page.class);
                m.setAccessible(true);
                Path saved = (Path) m.invoke(tool, page);

                assertThat(Files.exists(saved)).isTrue();
                assertThat(Files.size(saved))
                        .as("a 400-row page produces a screenshot well past the inline cap")
                        .isGreaterThan(512L * 1024);

                var inline = BrowserTool.class.getDeclaredMethod(
                        "inlineScreenshotIfSmallEnough", Path.class);
                inline.setAccessible(true);
                assertThat((String) inline.invoke(tool, saved))
                        .as("the artifact stays on disk; only the preview is dropped")
                        .isNull();
            } finally {
                manager.closePage(page);
            }
        } finally {
            manager.close();
        }
    }
}
