package com.qalab.qalabai.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the one Playwright and one Chromium process the whole JVM shares.
 *
 * <p>Every call site used to call {@code Playwright.create()} and
 * {@code chromium().launch()} itself. Launching a browser costs one to two seconds and
 * was paid on <em>every</em> explore, login and locator-heal call, so a run that touched
 * three pages spent several seconds starting browsers it had already started. Sharing one
 * process removes that cost, and it also gives the container flags a single place to live.
 *
 * <p>State is still isolated per call: each caller gets a brand new context and page, so
 * cookies, storage and focus never leak between runs even though the process is shared.
 * A shared browser is a shared <em>process</em>, not shared session state.
 *
 * <p>The browser is launched lazily so importing this class never costs anything, and
 * closed after an idle period so a server that stopped receiving work does not hold a
 * Chromium process (and its ~100 MB) open forever. A crashed or externally closed browser
 * is detected and relaunched rather than failing every subsequent call.
 */
@Component
public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);

    private final int maxConcurrentPages;
    private final long idleTimeoutMs;
    private final boolean headed;
    private final String[] launchArgs;

    private final Semaphore pagePermits;
    private final AtomicInteger activePages = new AtomicInteger();
    private final Object lock = new Object();

    private volatile Playwright playwright;
    private volatile Browser browser;
    private volatile long lastUsedAtMs = System.currentTimeMillis();
    private volatile Thread reaper;

    /**
     * How a Playwright process and its browser are obtained. Injected rather than called
     * inline so the launch-counting behaviour — the whole point of this class — can be
     * asserted in a test without standing up a real Chromium or subclassing Playwright's
     * non-subclassable implementation.
     */
    interface Launcher {
        Playwright newPlaywright();

        Browser launch(Playwright playwright, boolean headed, List<String> args);
    }

    private static final Launcher DEFAULT_LAUNCHER = new Launcher() {
        @Override
        public Playwright newPlaywright() {
            return Playwright.create();
        }

        @Override
        public Browser launch(Playwright playwright, boolean headed, List<String> args) {
            return playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(!headed)
                            .setArgs(args));
        }
    };

    private final Launcher launcher;

    @org.springframework.beans.factory.annotation.Autowired
    public BrowserSessionManager(
            @Value("${qalab.browser.max-concurrent-pages:4}") int maxConcurrentPages,
            @Value("${qalab.browser.idle-timeout-seconds:300}") long idleTimeoutSeconds,
            @Value("${qalab.browser.headless:true}") boolean headless) {
        this(maxConcurrentPages, idleTimeoutSeconds, headless, DEFAULT_LAUNCHER);
    }

    BrowserSessionManager(int maxConcurrentPages, long idleTimeoutSeconds, boolean headless,
                          Launcher launcher) {
        this.maxConcurrentPages = Math.max(1, maxConcurrentPages);
        this.idleTimeoutMs = Math.max(10, idleTimeoutSeconds) * 1000;
        this.headed = !headless;
        this.launcher = launcher;
        this.pagePermits = new Semaphore(this.maxConcurrentPages, true);
        this.launchArgs = resolveLaunchArgs();
    }

    /**
     * Chromium's sandbox needs kernel features that are unavailable to a container running
     * as root, and its shared-memory segment defaults to 64 MB which is smaller than a
     * Chromium renderer's demand — so a containerised run fails at launch with a message
     * that does not mention either cause. Both flags are applied only when they are
     * actually needed: disabling the sandbox on a normal host is a real security loss and
     * should not happen by default.
     */
    private static String[] resolveLaunchArgs() {
        List<String> args = new ArrayList<>();
        boolean root = "root".equals(System.getProperty("user.name"));
        boolean inContainer = System.getenv("KUBERNETES_SERVICE_HOST") != null
                || System.getenv("DOCKER_CONTAINER") != null
                || FilesystemIndicators.containerized();
        if (root) {
            args.add("--no-sandbox");
            args.add("--disable-setuid-sandbox");
        }
        if (root || inContainer) {
            args.add("--disable-dev-shm-usage");
        }
        return args.toArray(new String[0]);
    }

    /**
     * Opens a page on the shared browser, waiting for a free slot when every permit is
     * taken. The caller owns the returned page and must close it — preferably with
     * {@link #closePage(Page)} so the permit is released even if the close throws.
     */
    public Page newPage() throws InterruptedException {
        long waitStarted = System.currentTimeMillis();
        if (!pagePermits.tryAcquire(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for a browser page slot after 60s. All "
                            + maxConcurrentPages + " are still in use, which means pages are "
                            + "being leaked by a caller that never closes them.");
        }
        boolean handedOver = false;
        try {
            Page page = browser().newContext().newPage();
            activePages.incrementAndGet();
            lastUsedAtMs = System.currentTimeMillis();
            handedOver = true;
            return page;
        } finally {
            if (!handedOver) {
                pagePermits.release();
            }
        }
    }

    public void closePage(Page page) {
        try {
            if (page != null) {
                page.context().close();
            }
        } catch (Exception e) {
            // A page whose context is already gone is not an error worth failing a run
            // over; the browser reaper will reclaim the process if it is really dead.
            log.debug("Ignoring error closing page: {}", e.getMessage());
        } finally {
            if (activePages.decrementAndGet() < 0) {
                activePages.set(0);
            }
            lastUsedAtMs = System.currentTimeMillis();
            pagePermits.release();
        }
    }

    /**
     * Returns the shared browser, launching it on first use and relaunching it if a
     * previous process died. Callers must not close it — {@link #close()} is for shutdown.
     */
    Browser browser() {
        Browser current = browser;
        if (current != null && current.isConnected()) {
            return current;
        }
        synchronized (lock) {
            current = browser;
            if (current != null && current.isConnected()) {
                return current;
            }
            if (current != null) {
                log.warn("Shared browser was no longer connected; relaunching");
                closeQuietly(current, playwright);
            }
            long startedAt = System.currentTimeMillis();
            try {
                playwright = launcher.newPlaywright();
                browser = launcher.launch(playwright, headed, List.of(launchArgs));
            } catch (RuntimeException e) {
                playwright = null;
                browser = null;
                throw new IllegalStateException(
                        "Failed to launch Chromium" + (launchArgs.length == 0 ? "" : " with args " + List.of(launchArgs))
                                + ". In a container running as root this usually means the sandbox "
                                + "flags are missing; the resolved args above are the ones that matter.",
                        e);
            }
            startReaper();
            log.info("Shared Chromium launched in {} ms (args: {})",
                    System.currentTimeMillis() - startedAt,
                    launchArgs.length == 0 ? "none" : List.of(launchArgs));
            return browser;
        }
    }

    /**
     * Closes the browser once it has been idle for the configured period, so an
     * occasional run does not pay the launch cost but a server that went quiet does not
     * hold a Chromium process either. Runs on a daemon thread and is a no-op while any
     * page is open.
     */
    private void startReaper() {
        if (reaper != null && reaper.isAlive()) {
            return;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Math.max(5000, idleTimeoutMs / 4));
                    if (activePages.get() == 0
                            && System.currentTimeMillis() - lastUsedAtMs > idleTimeoutMs) {
                        synchronized (lock) {
                            if (activePages.get() == 0) {
                                log.info("Closing idle shared Chromium after {}s of inactivity",
                                        idleTimeoutMs / 1000);
                                closeQuietly(browser, playwright);
                                browser = null;
                                playwright = null;
                                return;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.debug("Browser reaper iteration failed: {}", e.getMessage());
                }
            }
        }, "qalab-browser-reaper");
        t.setDaemon(true);
        reaper = t;
        t.start();
    }

    private void closeQuietly(Browser toClose, Playwright toClosePlaywright) {
        try {
            if (toClose != null) {
                toClose.close();
            }
        } catch (Exception e) {
            log.debug("Ignoring error closing browser: {}", e.getMessage());
        }
        try {
            if (toClosePlaywright != null) {
                toClosePlaywright.close();
            }
        } catch (Exception e) {
            log.debug("Ignoring error closing Playwright: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        log.info("Shutting down shared Chromium");
        Thread t = reaper;
        if (t != null) {
            t.interrupt();
        }
        closeQuietly(browser, playwright);
        browser = null;
        playwright = null;
    }

    /** Exposed for tests and the health endpoint; not a signal to close anything. */
    public boolean isRunning() {
        Browser current = browser;
        return current != null && current.isConnected();
    }

    public int activePageCount() {
        return activePages.get();
    }

    /** Cheap heuristic; a real container check would mean reading cgroup mounts. */
    private static final class FilesystemIndicators {
        static boolean containerized() {
            return new java.io.File("/.dockerenv").exists();
        }
    }
}
