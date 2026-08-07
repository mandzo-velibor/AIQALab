package com.qalab.qalabai.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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

    @Value("${qalab.screenshots-dir:./screenshots}")
    private String screenshotsDir;

    @Override
    public String getName() {
        return "BrowserTool";
    }

    @Override
    public Object execute(ToolContext context) {
        String url = context.getString("url");
        log.info("BrowserTool executing for URL: {}", url);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();

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
            String screenshotBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(screenshotPath));
            result.put("screenshotPath", screenshotPath.toString());
            result.put("screenshotBase64", screenshotBase64);

            result.put("buttonCount", page.locator("button").count());
            result.put("inputCount", page.locator("input").count());
            result.put("linkCount", page.locator("a").count());
            result.put("formCount", page.locator("form").count());

            log.info("Browser data collection complete");
            browser.close();
            return result;

        } catch (Exception e) {
            log.error("Browser error for URL {}: {}", url, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("url", url);
            return error;
        }
    }

    public String open(String url) {
        log.info("Opening URL: {}", url);
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();
            page.navigate(url);
            page.waitForLoadState();
            log.info("URL opened successfully: {}", url);
            return url;
        } catch (Exception e) {
            log.error("Failed to open URL: {}", e.getMessage());
            throw new RuntimeException("Failed to open URL: " + e.getMessage(), e);
        }
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
}
