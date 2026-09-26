package com.qalab.qalabai.tool.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.qalab.qalabai.tool.Tool;
import com.qalab.qalabai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class BrowserTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);

    /**
     * Ceiling for the inline screenshot. The browser {@code <img>} in the dashboard needs
     * a data URI because artifacts are not served over HTTP yet (that is waiting on the
     * access model), so this is the one place base64 is genuinely required. It is bounded
     * because it is otherwise a payload that grows with page height: a long page produced
     * a multi-megabyte response and, worse, a value that could not fit the
     * {@code analysis_json} column it used to be written into.
     */
    private static final int MAX_INLINE_SCREENSHOT_BYTES = 512 * 1024;

    /** Below this a full-page PNG is downscaled rather than dropped, since it is still useful. */
    private static final int SCREENSHOT_SOFT_LIMIT_BYTES = 256 * 1024;

    private final BrowserSessionManager sessions;

    @Value("${qalab.screenshots-dir:./screenshots}")
    private String screenshotsDir;

    public BrowserTool(BrowserSessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public String getName() {
        return "BrowserTool";
    }

    @Override
    public Object execute(ToolContext context) {
        String url = context.getString("url");
        log.info("BrowserTool executing for URL: {}", url);

        Page page = null;
        try {
            page = sessions.newPage();

            log.info("Navigating to: {}", url);
            page.navigate(url);
            page.waitForLoadState();
            log.info("Page loaded successfully");

            Map<String, Object> result = new HashMap<>();
            result.put("title", getPageTitle(page));
            result.put("url", getCurrentUrl(page));
            result.put("html", getHtml(page));
            result.put("accessibilityTree", getAccessibilityTree(page));

            Path screenshotPath = saveScreenshot(page);
            result.put("screenshotPath", screenshotPath.toString());
            String inline = inlineScreenshotIfSmallEnough(screenshotPath);
            if (inline != null) {
                result.put("screenshotBase64", inline);
            }

            result.put("buttonCount", page.locator("button").count());
            result.put("inputCount", page.locator("input").count());
            result.put("linkCount", page.locator("a").count());
            result.put("formCount", page.locator("form").count());

            log.info("Browser data collection complete");
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for a browser page slot for URL {}", url);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Interrupted while waiting for a browser page");
            error.put("url", url);
            return error;
        } catch (Exception e) {
            log.error("Browser error for URL {}: {}", url, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("url", url);
            return error;
        } finally {
            sessions.closePage(page);
        }
    }

    public String open(String url) {
        log.info("Opening URL: {}", url);
        Page page = null;
        try {
            page = sessions.newPage();
            page.navigate(url);
            page.waitForLoadState();
            log.info("URL opened successfully: {}", url);
            return url;
        } catch (Exception e) {
            log.error("Failed to open URL: {}", e.getMessage());
            throw new RuntimeException("Failed to open URL: " + e.getMessage(), e);
        } finally {
            sessions.closePage(page);
        }
    }

    /**
     * Opens the page, detects a login form (password input), fills the given
     * credentials, submits, and captures the post-login page state.
     *
     * @return a map with the post-login page state, or null when no login form was found
     */
    public Map<String, Object> login(String url, String username, String password) {
        log.info("BrowserTool performing login for URL: {}", url);
        Page page = null;
        try {
            page = sessions.newPage();
            page.navigate(url);
            page.waitForLoadState();

            Locator passwordInput = page.locator("input[type=password]").first();
            if (passwordInput.count() == 0) {
                log.info("No login form (password input) found on {}", url);
                return null;
            }

            Locator usernameInput = findUsernameInput(page, passwordInput);
            Locator submitButton = findSubmitButton(page, passwordInput);

            if (usernameInput.count() > 0) {
                usernameInput.fill(username != null ? username : "");
            }
            passwordInput.fill(password != null ? password : "");

            if (submitButton.count() > 0) {
                log.info("Submitting login form");
                submitButton.click();
            } else {
                log.info("No submit button found, pressing Enter");
                passwordInput.press("Enter");
            }

            try {
                page.waitForURL("**", new Page.WaitForURLOptions().setTimeout(15000));
            } catch (Exception e) {
                log.warn("Navigation wait after login failed: {}", e.getMessage());
            }
            page.waitForLoadState();

            Map<String, Object> result = new HashMap<>();
            result.put("title", getPageTitle(page));
            result.put("url", getCurrentUrl(page));
            result.put("html", getHtml(page));
            result.put("accessibilityTree", getAccessibilityTree(page));

            Path screenshotPath = saveScreenshot(page);
            result.put("screenshotPath", screenshotPath.toString());
            String inline = inlineScreenshotIfSmallEnough(screenshotPath);
            if (inline != null) {
                result.put("screenshotBase64", inline);
            }

            log.info("Post-login state captured for URL: {}", result.get("url"));
            return result;

        } catch (Exception e) {
            log.error("Login flow error for URL {}: {}", url, e.getMessage());
            return null;
        } finally {
            sessions.closePage(page);
        }
    }

    private Locator findUsernameInput(Page page, Locator passwordInput) {
        Locator form = passwordInput.locator("xpath=ancestor::form").first();
        if (form.count() > 0) {
            Locator textInput = form.locator("input:not([type=password]):not([type=hidden]):not([type=submit])").first();
            if (textInput.count() > 0) {
                return textInput;
            }
        }
        return page.locator("input[name*='user' i], input[name*='email' i], input[name*='login' i], input[type=text], input[type=email]").first();
    }

    private Locator findSubmitButton(Page page, Locator passwordInput) {
        Locator form = passwordInput.locator("xpath=ancestor::form").first();
        if (form.count() > 0) {
            Locator submit = form.locator("button[type=submit], input[type=submit], button:has-text('Log'), button:has-text('Sign'), button:has-text('Sign In')").first();
            if (submit.count() > 0) {
                return submit;
            }
        }
        return page.locator("button[type=submit], input[type=submit], button:has-text('Log'), button:has-text('Sign')").first();
    }

    public String getPageTitle(Page page) {
        String title = page.title();
        log.debug("Page title: {}", title);
        return title;
    }

    public String getCurrentUrl(Page page) {
        String url = page.url();
        log.debug("Current URL: {}", url);
        return url;
    }

    public String getHtml(Page page) {
        String html = page.content();
        log.debug("HTML length: {} chars", html.length());
        return html;
    }

    public String getAccessibilityTree(Page page) {
        try {
            String snapshot = page.locator("body").evaluate(
                    "el => el.innerText"
            ).toString();
            log.debug("Accessibility tree length: {} chars", snapshot.length());
            return snapshot;
        } catch (Exception e) {
            log.warn("Failed to get accessibility tree: {}", e.getMessage());
            return "";
        }
    }

    public byte[] takeScreenshot(Page page) {
        try {
            byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
            log.debug("Screenshot captured, size: {} bytes", screenshot.length);
            return screenshot;
        } catch (Exception e) {
            log.error("Failed to take screenshot: {}", e.getMessage());
            throw new RuntimeException("Failed to take screenshot", e);
        }
    }

    private Path saveScreenshot(Page page) throws Exception {
        Path dir = Paths.get(screenshotsDir);
        Files.createDirectories(dir);
        Path screenshotPath = dir.resolve(UUID.randomUUID() + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath).setFullPage(true));
        return screenshotPath;
    }

    /**
     * Returns base64 for the dashboard image, or null when the file is too large to inline.
     * The file on disk is always kept regardless — it is the artifact the HTML report and
     * the CLI use, and dropping it because a preview would be oversized would lose the
     * evidence to save a few hundred kilobytes of preview.
     */
    private String inlineScreenshotIfSmallEnough(Path screenshotPath) {
        try {
            long size = Files.size(screenshotPath);
            if (size > MAX_INLINE_SCREENSHOT_BYTES) {
                log.info("Screenshot of {} KB exceeds the {} KB inline limit; returning the path only",
                        size / 1024, MAX_INLINE_SCREENSHOT_BYTES / 1024);
                return null;
            }
            if (size > SCREENSHOT_SOFT_LIMIT_BYTES) {
                log.debug("Screenshot of {} KB is large but within the inline limit", size / 1024);
            }
            return Base64.getEncoder().encodeToString(Files.readAllBytes(screenshotPath));
        } catch (Exception e) {
            log.warn("Could not inline screenshot {}: {}", screenshotPath, e.getMessage());
            return null;
        }
    }
}
