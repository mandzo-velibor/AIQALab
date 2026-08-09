package com.qalab.qalabai.locator.intelligence;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import com.qalab.qalabai.locator.intelligence.model.LiveEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a locator against a live page via Playwright to measure uniqueness,
 * visibility, enabled state and to capture the stable element identity. This is
 * the real-browser verification behind the "uniqueness" dimension of the
 * quality score.
 */
@Component
public class LocatorLiveEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LocatorLiveEvaluator.class);

    private static final Pattern GET_BY_ROLE = Pattern.compile(
            "getByRole\\(\\s*['\"]([a-zA-Z]+)['\"]\\s*(?:,\\s*\\{?\\s*name:\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern GET_BY_ROLE_NO_NAME = Pattern.compile(
            "getByRole\\(\\s*['\"]([a-zA-Z]+)['\"]\\s*\\)");
    private static final Pattern GET_BY_TEST_ID = Pattern.compile(
            "getByTestId\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_LABEL = Pattern.compile(
            "getByLabel\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_PLACEHOLDER = Pattern.compile(
            "getByPlaceholder\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_TEXT = Pattern.compile(
            "getByText\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_TITLE = Pattern.compile(
            "getByTitle\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_ALT_TEXT = Pattern.compile(
            "getByAltText\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern LOCATOR_CSS = Pattern.compile(
            "locator\\(\\s*['\"]([^'\"]+)['\"]");

    public LiveEvaluation evaluate(String url, String locator) {
        if (url == null || url.isBlank()) {
            return LiveEvaluation.failed("URL is required for live analysis.");
        }
        if (locator == null || locator.isBlank()) {
            return LiveEvaluation.failed("Locator is required for live analysis.");
        }
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.navigate(url);
            page.waitForLoadState();

            Locator resolved = resolve(page, locator);
            int count = resolved.count();
            boolean visible = false;
            boolean enabled = false;
            ElementIdentity identity = null;

            if (count > 0) {
                Locator first = resolved.first();
                visible = safe(() -> first.isVisible(), false);
                enabled = safe(() -> first.isEnabled(), false);
                identity = extractIdentity(page, first, url);
            }
            browser.close();
            return new LiveEvaluation(count, visible, enabled, identity, null);
        } catch (Exception e) {
            log.warn("Live evaluation failed for {} on {}: {}", locator, url, e.getMessage());
            return LiveEvaluation.failed(e.getMessage());
        }
    }

    private Locator resolve(Page page, String locator) {
        String trimmed = locator.trim();

        Matcher role = GET_BY_ROLE.matcher(trimmed);
        if (role.find()) {
            String roleName = role.group(1).toUpperCase();
            String name = role.group(2);
            return page.getByRole(ariaRole(roleName), new Page.GetByRoleOptions().setName(name));
        }
        Matcher roleOnly = GET_BY_ROLE_NO_NAME.matcher(trimmed);
        if (roleOnly.find()) {
            return page.getByRole(ariaRole(roleOnly.group(1).toUpperCase()));
        }
        Matcher testId = GET_BY_TEST_ID.matcher(trimmed);
        if (testId.find()) {
            return page.getByTestId(testId.group(1));
        }
        Matcher label = GET_BY_LABEL.matcher(trimmed);
        if (label.find()) {
            return page.getByLabel(label.group(1));
        }
        Matcher placeholder = GET_BY_PLACEHOLDER.matcher(trimmed);
        if (placeholder.find()) {
            return page.getByPlaceholder(placeholder.group(1));
        }
        Matcher text = GET_BY_TEXT.matcher(trimmed);
        if (text.find()) {
            return page.getByText(text.group(1));
        }
        Matcher title = GET_BY_TITLE.matcher(trimmed);
        if (title.find()) {
            return page.getByTitle(title.group(1));
        }
        Matcher alt = GET_BY_ALT_TEXT.matcher(trimmed);
        if (alt.find()) {
            return page.getByAltText(alt.group(1));
        }
        Matcher css = LOCATOR_CSS.matcher(trimmed);
        if (css.find()) {
            return page.locator(css.group(1));
        }
        if (trimmed.startsWith("xpath") || trimmed.startsWith("//") || trimmed.startsWith("/")) {
            return page.locator("xpath=" + trimmed.replaceFirst("^xpath=", ""));
        }
        return page.locator(trimmed);
    }

    private com.microsoft.playwright.options.AriaRole ariaRole(String role) {
        try {
            return com.microsoft.playwright.options.AriaRole.valueOf(role);
        } catch (Exception e) {
            return com.microsoft.playwright.options.AriaRole.BUTTON;
        }
    }

    @SuppressWarnings("unchecked")
    private ElementIdentity extractIdentity(Page page, Locator first, String url) {
        try {
            Object raw = first.evaluate("el => {"
                    + "  const attrs = {};"
                    + "  for (let i = 0; i < el.attributes.length; i++) {"
                    + "    const a = el.attributes[i]; attrs[a.name] = a.value;"
                    + "  }"
                    + "  return {"
                    + "    tag: (el.tagName || '').toLowerCase(),"
                    + "    attrs: attrs,"
                    + "    text: (el.textContent || '').trim().slice(0, 200),"
                    + "    ariaLabel: el.getAttribute('aria-label') || '',"
                    + "    ariaLabelledby: el.getAttribute('aria-labelledby') || ''"
                    + "  };"
                    + "}");
            if (!(raw instanceof Map)) {
                return null;
            }
            Map<String, Object> data = (Map<String, Object>) raw;
            Map<String, String> attrs = (Map<String, String>) data.getOrDefault("attrs", new LinkedHashMap<String, String>());

            String tag = str(data.get("tag"));
            String testId = firstAttr(attrs, "data-testid", "data-test", "data-cy");
            String label = firstAttr(attrs, "aria-label");
            String accessibleName = label != null ? label : str(data.get("ariaLabel"));
            if (accessibleName == null || accessibleName.isBlank()) {
                accessibleName = str(data.get("text"));
            }
            boolean disabled = first.isDisabled();
            return new ElementIdentity(
                    url, tag, attrs.get("role"), accessibleName, str(data.get("text")),
                    testId, label, attrs.get("placeholder"), attrs.get("name"),
                    attrs.get("id"), attrs.get("href"), attrs.get("class"), attrs,
                    first.isVisible(), !disabled);
        } catch (Exception e) {
            log.debug("Identity extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private String firstAttr(Map<String, String> attrs, String... names) {
        for (String name : names) {
            String value = attrs.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String str(Object o) {
        return o != null ? String.valueOf(o) : null;
    }

    private <T> T safe(CheckedSupplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
