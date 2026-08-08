package com.qalab.qalabai.healing.candidates;

import com.qalab.qalabai.healing.context.HtmlElements;
import com.qalab.qalabai.healing.model.DomSnapshot;
import com.qalab.qalabai.healing.model.LocatorCandidate;
import com.qalab.qalabai.healing.model.LocatorStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates candidate locators against the current page DOM where possible.
 * For each candidate it determines how many elements resolve, whether the
 * element is unique, visible and enabled.
 */
@Component
public class LocatorCandidateValidator {

    private static final Pattern GET_BY_TEST_ID = Pattern.compile("getByTestId\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_ROLE = Pattern.compile("getByRole\\(\\s*['\"]([a-zA-Z]+)['\"]\\s*,\\s*\\{?\\s*name:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_TEXT = Pattern.compile("getByText\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GET_BY_PLACEHOLDER = Pattern.compile("getByPlaceholder\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern CSS_TAG_ATTR = Pattern.compile("locator\\('\\s*([a-zA-Z0-9]+)\\[([a-zA-Z0-9_-]+)=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern CSS_ID = Pattern.compile("locator\\('\\s*#([^']+)'");
    private static final Pattern CSS_NAME_ATTR = Pattern.compile("locator\\('\\s*\\[name=\\s*['\"]([^'\"]+)['\"]");

    public LocatorCandidate validate(LocatorCandidate candidate, DomSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return candidate;
        }
        List<HtmlElements.Element> elements = HtmlElements.scan(snapshot.relevantHtml());
        if (elements.isEmpty()) {
            return candidate;
        }

        Matcher testId = GET_BY_TEST_ID.matcher(candidate.locator());
        if (testId.find()) {
            String expected = testId.group(1);
            return validateMatch(candidate, elements,
                    e -> expected != null && expected.equalsIgnoreCase(firstValue(e, "data-testid", "data-test", "data-cy")));
        }
        Matcher role = GET_BY_ROLE.matcher(candidate.locator());
        if (role.find()) {
            return validateMatch(candidate, elements,
                    e -> roleNameMatches(e, role.group(1), role.group(2)));
        }
        Matcher text = GET_BY_TEXT.matcher(candidate.locator());
        if (text.find()) {
            return validateMatch(candidate, elements,
                    e -> textEquals(e, text.group(1)));
        }
        Matcher placeholder = GET_BY_PLACEHOLDER.matcher(candidate.locator());
        if (placeholder.find()) {
            return validateMatch(candidate, elements,
                    e -> placeholder.group(1).equalsIgnoreCase(e.attribute("placeholder") == null ? "" : e.attribute("placeholder")));
        }
        Matcher tagAttr = CSS_TAG_ATTR.matcher(candidate.locator());
        if (tagAttr.find()) {
            return validateMatch(candidate, elements,
                    e -> e.tag().equalsIgnoreCase(tagAttr.group(1))
                            && tagAttr.group(3).equalsIgnoreCase(e.attribute(tagAttr.group(2)) == null ? "" : e.attribute(tagAttr.group(2))));
        }
        Matcher cssId = CSS_ID.matcher(candidate.locator());
        if (cssId.find()) {
            return validateMatch(candidate, elements,
                    e -> cssId.group(1).equalsIgnoreCase(e.attribute("id") == null ? "" : e.attribute("id")));
        }
        Matcher nameAttr = CSS_NAME_ATTR.matcher(candidate.locator());
        if (nameAttr.find()) {
            return validateMatch(candidate, elements,
                    e -> nameAttr.group(1).equalsIgnoreCase(e.attribute("name") == null ? "" : e.attribute("name")));
        }
        return candidate;
    }

    private interface ElementMatcher {
        boolean matches(HtmlElements.Element element);
    }

    private LocatorCandidate validateMatch(LocatorCandidate candidate,
                                           List<HtmlElements.Element> elements,
                                           ElementMatcher matcher) {
        int count = 0;
        boolean anyVisible = false;
        boolean anyEnabled = false;
        boolean allVisible = true;
        boolean allEnabled = true;
        boolean found = false;
        for (HtmlElements.Element element : elements) {
            if (!matcher.matches(element)) {
                continue;
            }
            found = true;
            count++;
            boolean visible = isVisible(element);
            boolean enabled = isEnabled(element);
            anyVisible = anyVisible || visible;
            anyEnabled = anyEnabled || enabled;
            allVisible = allVisible && visible;
            allEnabled = allEnabled && enabled;
        }
        if (!found) {
            return candidate;
        }
        boolean unique = count == 1;
        boolean visible = unique ? anyVisible : allVisible;
        boolean enabled = unique ? anyEnabled : allEnabled;
        return new LocatorCandidate(
                candidate.locator(), candidate.strategy(), candidate.score(),
                unique, visible, enabled, count, candidate.reason());
    }

    private boolean roleNameMatches(HtmlElements.Element element, String role, String name) {
        String elementRole = element.attribute("role");
        if (elementRole == null || elementRole.isBlank()) {
            elementRole = inferTagRole(element.tag());
        }
        if (!role.equalsIgnoreCase(elementRole)) {
            return false;
        }
        String label = element.attribute("aria-label");
        if (label != null && !label.isBlank()) {
            return normalize(label).equals(normalize(name));
        }
        if (element.innerText() != null && !element.innerText().isBlank()) {
            return normalize(element.innerText()).equals(normalize(name));
        }
        String title = element.attribute("title");
        return title != null && normalize(title).equals(normalize(name));
    }

    private boolean textEquals(HtmlElements.Element element, String text) {
        return element.innerText() != null && normalize(element.innerText()).equals(normalize(text));
    }

    private boolean isVisible(HtmlElements.Element element) {
        String style = element.attribute("style");
        if (element.hasAttribute("hidden")) {
            return false;
        }
        if (style != null && (style.contains("display:none") || style.contains("visibility:hidden"))) {
            return false;
        }
        return true;
    }

    private boolean isEnabled(HtmlElements.Element element) {
        return !element.hasAttribute("disabled");
    }

    private String inferTagRole(String tag) {
        return switch (tag) {
            case "button" -> "button";
            case "a" -> "link";
            case "input" -> "textbox";
            case "textarea" -> "textbox";
            case "select" -> "combobox";
            case "h1", "h2", "h3", "h4" -> "heading";
            default -> tag;
        };
    }

    private String firstValue(HtmlElements.Element element, String... names) {
        for (String name : names) {
            if (element.hasAttribute(name)) {
                return element.attribute(name);
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
