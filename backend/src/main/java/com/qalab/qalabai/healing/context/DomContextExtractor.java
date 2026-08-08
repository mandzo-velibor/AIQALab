package com.qalab.qalabai.healing.context;

import com.qalab.qalabai.healing.model.DomSnapshot;
import com.qalab.qalabai.tool.ToolContext;
import com.qalab.qalabai.tool.browser.BrowserTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts a small, bounded page context (URL, title, relevant elements with
 * their locator-relevant attributes) from a DOM snapshot or from a live page.
 *
 * <p>The full DOM is never sent to the AI provider — only the extracted
 * {@link DomSnapshot} is.</p>
 */
@Component
public class DomContextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DomContextExtractor.class);

    private static final int DEFAULT_MAX_CHARS = 6000;

    private final BrowserTool browserTool;

    public DomContextExtractor(BrowserTool browserTool) {
        this.browserTool = browserTool;
    }

    /** Extracts a bounded context from a raw HTML snapshot. */
    public DomSnapshot extractFromHtml(String html, String currentUrl, String pageTitle) {
        return new DomSnapshot(currentUrl, pageTitle, extract(html, DEFAULT_MAX_CHARS));
    }

    /** Extracts a bounded context from a live page via the Browser Tool. */
    public DomSnapshot extractLive(String url) {
        if (url == null || url.isBlank()) {
            return new DomSnapshot(null, null, "");
        }
        try {
            ToolContext context = new ToolContext().put("url", url);
            Object result = browserTool.execute(context);
            if (result instanceof Map<?, ?> map) {
                String html = (String) map.get("html");
                String title = (String) map.get("title");
                String currentUrl = (String) map.get("url");
                return new DomSnapshot(currentUrl != null ? currentUrl : url,
                        title, extract(html, DEFAULT_MAX_CHARS));
            }
        } catch (Exception e) {
            log.warn("Live DOM extraction failed for {}: {}", url, e.getMessage());
        }
        return new DomSnapshot(url, null, "");
    }

    /**
     * Renders relevant elements (interactive + headings) with locator-relevant
     * attributes, truncated to {@code maxChars}. Deterministic and small.
     */
    public String extract(String html, int maxChars) {
        if (html == null || html.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (HtmlElements.Element element : HtmlElements.scan(html)) {
            sb.append(render(element)).append("\n");
            if (sb.length() > maxChars) {
                sb.append("...(truncated)");
                break;
            }
        }
        return sb.toString();
    }

    private String render(HtmlElements.Element element) {
        Map<String, String> attrs = new LinkedHashMap<>();
        String[] names = {
                "id", "name", "role", "aria-label", "aria-labelledby", "data-testid",
                "data-test", "data-cy", "class", "placeholder", "type", "href", "title"
        };
        for (String name : names) {
            if (element.hasAttribute(name)) {
                attrs.put(name, element.attribute(name));
            }
        }
        if (element.hasAttribute("disabled")) {
            attrs.put("disabled", "");
        }
        if (element.hasAttribute("hidden")) {
            attrs.put("hidden", "");
        }

        StringBuilder sb = new StringBuilder("<").append(element.tag());
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            sb.append(' ').append(e.getKey());
            if (!e.getValue().isEmpty()) {
                sb.append("=\"").append(shorten(e.getValue(), 80)).append("\"");
            }
        }
        sb.append('>');
        if (element.innerText() != null && !element.innerText().isBlank()) {
            sb.append(shorten(element.innerText(), 120));
        }
        sb.append("</").append(element.tag()).append(">");
        return sb.toString();
    }

    private String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.length() > max ? collapsed.substring(0, max) + "..." : collapsed;
    }
}
