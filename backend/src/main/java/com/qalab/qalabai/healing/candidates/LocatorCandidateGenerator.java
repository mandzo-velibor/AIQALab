package com.qalab.qalabai.healing.candidates;

import com.qalab.qalabai.healing.context.DomContextExtractor;
import com.qalab.qalabai.healing.context.HtmlElements;
import com.qalab.qalabai.healing.model.DomSnapshot;
import com.qalab.qalabai.healing.model.LocatorCandidate;
import com.qalab.qalabai.healing.model.LocatorStrategy;
import com.qalab.qalabai.service.healing.LocatorSimilarityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically generates candidate locators from the current DOM. The AI
 * provider is never asked to invent locators from scratch — it only evaluates
 * candidates produced here.
 */
@Component
public class LocatorCandidateGenerator {

    private static final Logger log = LoggerFactory.getLogger(LocatorCandidateGenerator.class);

    private static final Pattern GET_BY_ROLE = Pattern.compile(
            "getByRole\\(\\s*['\"]([a-zA-Z]+)['\"]\\s*,\\s*\\{?\\s*name:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_TEST_ID = Pattern.compile(
            "getByTestId\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_TEXT = Pattern.compile(
            "getByText\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_PLACEHOLDER = Pattern.compile(
            "getByPlaceholder\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_LABEL = Pattern.compile(
            "getByLabel\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern CSS_ID = Pattern.compile("locator\\('\\s*#([^']+)'");
    private static final Pattern CSS_ATTR = Pattern.compile(
            "locator\\('\\s*([a-zA-Z0-9]+)\\[([a-zA-Z0-9_-]+)=\\s*['\"]([^'\"]+)['\"]");

    private final LocatorSimilarityService similarityService;
    private final DomContextExtractor domContextExtractor;

    public LocatorCandidateGenerator(LocatorSimilarityService similarityService,
                                     DomContextExtractor domContextExtractor) {
        this.similarityService = similarityService;
        this.domContextExtractor = domContextExtractor;
    }

    public record OriginalHints(String role, String name, String testId, String placeholder, String text) {
    }

    /**
     * Generates candidates for a failed locator against a DOM snapshot.
     * Candidates carry a strategy and a locator string but no validation data;
     * the validator and ranker fill that in.
     */
    public List<LocatorCandidate> generate(String originalLocator, DomSnapshot snapshot) {
        List<LocatorCandidate> candidates = new ArrayList<>();
        if (snapshot == null || snapshot.isEmpty()) {
            return candidates;
        }
        OriginalHints hints = parseOriginalLocator(originalLocator);
        List<HtmlElements.Element> elements = HtmlElements.scan(snapshot.relevantHtml());
        Map<String, LocatorCandidate> byLocator = new LinkedHashMap<>();

        for (HtmlElements.Element element : elements) {
            addCandidatesForElement(element, hints, byLocator);
        }

        candidates.addAll(byLocator.values());
        log.info("Generated {} locator candidates for '{}'", candidates.size(), originalLocator);
        return candidates;
    }

    private void addCandidatesForElement(HtmlElements.Element element,
                                         OriginalHints hints,
                                         Map<String, LocatorCandidate> byLocator) {
        String testId = firstAttr(element, "data-testid", "data-test", "data-cy");
        if (testId != null && !testId.isBlank()) {
            put(byLocator, locator("getByTestId('" + HtmlElements.escape(testId) + "')"),
                    LocatorStrategy.TEST_ID, "Stable data-testid attribute.");
        }

        String text = element.innerText();
        String role = inferRole(element);
        String accessibleName = text;

        if (hints.role() != null && hints.role().equalsIgnoreCase(role)
                && accessibleName != null && !accessibleName.isBlank()) {
            put(byLocator, locator("getByRole('" + role + "', { name: '" + HtmlElements.escape(accessibleName) + "' })"),
                    LocatorStrategy.ROLE, "Same semantic role; accessible name from current DOM.");
        }
        if (accessibleName != null && !accessibleName.isBlank()) {
            put(byLocator, locator("getByText('" + HtmlElements.escape(accessibleName) + "')"),
                    LocatorStrategy.TEXT, "Stable semantic text content.");
        }

        String placeholder = element.attribute("placeholder");
        if (placeholder != null && !placeholder.isBlank()) {
            put(byLocator, locator("getByPlaceholder('" + HtmlElements.escape(placeholder) + "')"),
                    LocatorStrategy.PLACEHOLDER, "Placeholder text.");
        }

        String type = element.attribute("type");
        String tag = element.tag();
        if ("button".equals(tag) || "input".equals(tag)) {
            if (type != null && !type.isBlank()) {
                put(byLocator, locator("locator('" + tag + "[type=\"" + type + "\"]')"),
                        LocatorStrategy.CSS, "Stable tag + type attribute.");
            }
            if ("button".equals(tag) && accessibleName != null && !accessibleName.isBlank()) {
                put(byLocator, locator("getByRole('button', { name: '" + HtmlElements.escape(accessibleName) + "' })"),
                        LocatorStrategy.ROLE, "Button with accessible name from current DOM.");
            }
        }

        String id = element.attribute("id");
        if (id != null && !id.isBlank()) {
            put(byLocator, locator("locator('#" + HtmlElements.escape(id) + "')"),
                    LocatorStrategy.ID, "Element id.");
        }
        String nameAttr = element.attribute("name");
        if (nameAttr != null && !nameAttr.isBlank()) {
            put(byLocator, locator("locator('[name=\"" + nameAttr + "\"]')"),
                    LocatorStrategy.NAME, "Stable name attribute.");
        }
    }

    private String firstAttr(HtmlElements.Element element, String... names) {
        for (String name : names) {
            if (element.hasAttribute(name)) {
                return element.attribute(name);
            }
        }
        return null;
    }

    private String inferRole(HtmlElements.Element element) {
        String explicit = element.attribute("role");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return switch (element.tag()) {
            case "button" -> "button";
            case "a" -> "link";
            case "input" -> "textbox";
            case "select" -> "combobox";
            case "textarea" -> "textbox";
            case "h1", "h2", "h3", "h4" -> "heading";
            default -> "button";
        };
    }

    private String locator(String value) {
        return value;
    }

    private void put(Map<String, LocatorCandidate> byLocator, String locator,
                     LocatorStrategy strategy, String reason) {
        if (locator == null || locator.isBlank()) {
            return;
        }
        byLocator.putIfAbsent(locator, new LocatorCandidate(locator, strategy, 0.0,
                false, false, false, 0, reason));
    }

    /** Extracts role/name and other hints from a failed Playwright locator. */
    public OriginalHints parseOriginalLocator(String originalLocator) {
        if (originalLocator == null || originalLocator.isBlank()) {
            return new OriginalHints(null, null, null, null, null);
        }
        Matcher role = GET_BY_ROLE.matcher(originalLocator);
        if (role.find()) {
            return new OriginalHints(role.group(1), role.group(2), null, null, null);
        }
        Matcher testId = GET_BY_TEST_ID.matcher(originalLocator);
        if (testId.find()) {
            return new OriginalHints(null, null, testId.group(1), null, null);
        }
        Matcher placeholder = GET_BY_PLACEHOLDER.matcher(originalLocator);
        if (placeholder.find()) {
            return new OriginalHints(null, null, null, placeholder.group(1), null);
        }
        Matcher text = GET_BY_TEXT.matcher(originalLocator);
        if (text.find()) {
            return new OriginalHints(null, null, null, null, text.group(1));
        }
        return new OriginalHints(null, null, null, null, null);
    }
}
