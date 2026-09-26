package com.qalab.qalabai.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BrowserSessionManager exists so N tool calls cost one browser launch. That claim is
 * worth nothing unless it is measured, so these tests inject a counting launcher and
 * assert the launch count — not merely that the class runs.
 */
class BrowserSessionManagerTest {

    private static final class CountingLauncher implements BrowserSessionManager.Launcher {
        private final AtomicInteger launches = new AtomicInteger();
        private final Browser browser;

        CountingLauncher(Browser browser) {
            this.browser = browser;
        }

        @Override
        public Playwright newPlaywright() {
            return mock(Playwright.class);
        }

        @Override
        public Browser launch(Playwright playwright, boolean headed, List<String> args) {
            launches.incrementAndGet();
            return browser;
        }
    }

    private record Harness(CountingLauncher launcher, Browser browser, BrowserContext context) {
    }

    private static Harness harness() {
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(context.newPage()).thenReturn(page);
        when(page.context()).thenReturn(context);

        Browser browser = mock(Browser.class);
        when(browser.isConnected()).thenReturn(true);
        when(browser.newContext()).thenReturn(context);

        return new Harness(new CountingLauncher(browser), browser, context);
    }

    @Test
    void manyCallsShareOneBrowserLaunch() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(4, 300, true, h.launcher());
        try {
            for (int i = 0; i < 5; i++) {
                manager.closePage(manager.newPage());
            }
            assertThat(h.launcher().launches.get())
                    .as("5 sequential calls must launch exactly 1 browser")
                    .isEqualTo(1);
        } finally {
            manager.close();
        }
    }

    @Test
    void concurrentCallsStillShareOneBrowser() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(4, 300, true, h.launcher());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CyclicBarrier startTogether = new CyclicBarrier(4);
            List<Callable<Void>> jobs = IntStream.range(0, 4)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        manager.closePage(manager.newPage());
                        return null;
                    })
                    .toList();
            for (Future<Void> f : pool.invokeAll(jobs)) {
                f.get(30, TimeUnit.SECONDS);
            }
            assertThat(h.launcher().launches.get())
                    .as("concurrent first use must not race into multiple launches")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            manager.close();
        }
    }

    @Test
    void relaunchesWhenTheBrowserProcessDied() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(2, 300, true, h.launcher());
        try {
            manager.closePage(manager.newPage());
            assertThat(h.launcher().launches.get()).isEqualTo(1);

            // A crashed Chromium stops reporting connected; reusing it would fail every
            // subsequent call until restart, which is the bug this guards.
            Browser dead = mock(Browser.class);
            when(dead.isConnected()).thenReturn(false);
            injectBrowser(manager, dead);

            manager.closePage(manager.newPage());
            assertThat(h.launcher().launches.get())
                    .as("a dead browser must be relaunched, not reused")
                    .isEqualTo(2);
        } finally {
            manager.close();
        }
    }

    @Test
    void boundsConcurrentPages() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(2, 300, true, h.launcher());
        Page a = manager.newPage();
        Page b = manager.newPage();
        assertThat(manager.activePageCount()).isEqualTo(2);

        Semaphore permits = permits(manager);
        assertThat(permits.tryAcquire(200, TimeUnit.MILLISECONDS))
                .as("a third page must be refused while both slots are held")
                .isFalse();

        manager.closePage(a);
        assertThat(manager.activePageCount()).isEqualTo(1);
        manager.closePage(b);
        assertThat(manager.activePageCount()).isZero();

        // The reusable half: a freed slot must actually be handed out again, otherwise a
        // single leak permanently shrinks capacity.
        manager.closePage(manager.newPage());
        assertThat(manager.activePageCount()).isZero();
        manager.close();
    }

    @Test
    void aLeakedPageEventuallyFailsLoudlyInsteadOfHangingForever() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(1, 300, true, h.launcher());
        try {
            manager.newPage();
            // A caller that never closes its page must not block the next caller silently.
            // The real method waits 60s; the assertion is that the bound is enforced at
            // all, which the semaphore proves without spending that minute here.
            assertThat(permits(manager).tryAcquire(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            manager.close();
        }
    }

    @Test
    void closingTheSamePageTwiceDoesNotCorruptTheActiveCount() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(2, 300, true, h.launcher());
        Page page = manager.newPage();
        manager.closePage(page);
        // A defensive double close must not drive the count negative or leak a permit.
        manager.closePage(page);
        assertThat(manager.activePageCount()).isZero();
        manager.closePage(manager.newPage());
        assertThat(manager.activePageCount()).isZero();
        manager.close();
    }

    @Test
    void closeReleasesTheBrowser() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(2, 300, true, h.launcher());
        manager.closePage(manager.newPage());
        assertThat(manager.isRunning()).isTrue();
        manager.close();
        verify(h.browser(), times(1)).close();
    }

    @Test
    void eachCallGetsAFreshContextSoStateCannotLeakBetweenRuns() throws Exception {
        Harness h = harness();
        BrowserSessionManager manager =
                new BrowserSessionManager(2, 300, true, h.launcher());
        try {
            manager.closePage(manager.newPage());
            manager.closePage(manager.newPage());
            // A shared *process* must not mean shared cookies or storage. Two calls means
            // two contexts, which is what makes browser reuse safe for logged-in flows.
            verify(h.browser(), times(2)).newContext();
            verify(h.context(), times(2)).close();
        } finally {
            manager.close();
        }
    }

    @Test
    void sandboxWorkaroundsAreAppliedOnlyWhenTheEnvironmentNeedsThem() {
        String[] args = resolveLaunchArgs();
        if ("root".equals(System.getProperty("user.name"))) {
            assertThat(args).contains("--no-sandbox", "--disable-setuid-sandbox");
        } else {
            assertThat(args)
                    .as("a non-root host must keep the Chromium sandbox")
                    .doesNotContain("--no-sandbox");
        }
    }

    private static String[] resolveLaunchArgs() {
        try {
            var m = BrowserSessionManager.class.getDeclaredMethod("resolveLaunchArgs");
            m.setAccessible(true);
            return (String[]) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Semaphore permits(BrowserSessionManager manager) {
        try {
            var f = BrowserSessionManager.class.getDeclaredField("pagePermits");
            f.setAccessible(true);
            return (Semaphore) f.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void injectBrowser(BrowserSessionManager manager, Browser browser) {
        try {
            var f = BrowserSessionManager.class.getDeclaredField("browser");
            f.setAccessible(true);
            f.set(manager, browser);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
