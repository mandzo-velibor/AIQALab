package com.qalab.qalabai.healing.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, dependency-free HTML element scanner used for deterministic
 * candidate generation and validation. Not a full HTML parser — good enough for
 * the locator-healing pipeline and fully unit-testable.
 */
public final class HtmlElements {

    private static final Pattern TAG_PATTERN = Pattern.compile("<(input|button|a|select|textarea|label|form|h1|h2|h3|h4|span|div)(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_PATTERN = Pattern.compile("([a-zA-Z][a-zA-Z0-9:_-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    private HtmlElements() {
    }

    public record Element(String tag, Map<String, String> attributes, String innerText, String raw) {
        public String attribute(String name) {
            return attributes.get(name.toLowerCase());
        }

        public boolean hasAttribute(String name) {
            return attributes.containsKey(name.toLowerCase());
        }
    }

    /** Scans the raw HTML and returns the relevant interactive/structural elements. */
    public static List<Element> scan(String html) {
        List<Element> elements = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return elements;
        }

        Matcher matcher = TAG_PATTERN.matcher(html);
        int lastMatchEnd = 0;
        List<int[]> tagPositions = new ArrayList<>();
        while (matcher.find()) {
            tagPositions.add(new int[]{matcher.start(), matcher.end()});
        }

        for (int i = 0; i < tagPositions.size(); i++) {
            int[] pos = tagPositions.get(i);
            String rawTag = html.substring(pos[0], pos[1]);
            String tag = extractTagName(rawTag);

            String endTag = "</" + tag + ">";
            int end = html.indexOf(endTag, pos[1]);
            String raw;
            if (end > 0) {
                raw = html.substring(pos[0], end + endTag.length());
            } else {
                int next = (i + 1 < tagPositions.size()) ? tagPositions.get(i + 1)[0] : html.length();
                raw = html.substring(pos[0], Math.min(next, pos[0] + 600));
            }

            Map<String, String> attributes = parseAttributes(rawTag);
            String innerText = extractInnerText(raw);
            elements.add(new Element(tag, attributes, innerText, raw));
        }
        return elements;
    }

    private static String extractTagName(String rawTag) {
        Matcher m = Pattern.compile("^<([a-zA-Z0-9]+)").matcher(rawTag);
        return m.find() ? m.group(1).toLowerCase() : "element";
    }

    private static Map<String, String> parseAttributes(String tagHtml) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTR_PATTERN.matcher(tagHtml);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase();
            String value = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            attributes.put(name, value == null ? "" : value);
        }
        if (tagHtml.toLowerCase().contains(" disabled")) {
            attributes.put("disabled", "");
        }
        if (tagHtml.toLowerCase().contains(" hidden")) {
            attributes.put("hidden", "");
        }
        return attributes;
    }

    private static String extractInnerText(String raw) {
        if (raw.indexOf('>') < 0) {
            return "";
        }
        int start = raw.indexOf('>') + 1;
        int end = raw.lastIndexOf('<');
        if (end <= start) {
            return "";
        }
        String text = raw.substring(start, end);
        text = text.replaceAll("<[^>]+>", " ");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    /** Escapes a string for safe interpolation into a locator/selector string. */
    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
