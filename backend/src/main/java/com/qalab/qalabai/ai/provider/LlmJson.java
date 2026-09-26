package com.qalab.qalabai.ai.provider;

/**
 * Recovers a JSON value from an LLM response.
 *
 * <p>This existed as nine near-identical private methods that stripped a leading
 * {@code ```} fence and nothing else. Every one of them then handed the remainder
 * straight to Jackson, so any prose around the JSON killed the parse. The failure was
 * visible in the logs:</p>
 *
 * <pre>
 * WARN BugReportService : Failed to parse bug report AI response:
 *       Unrecognized token 'I': was expecting (JSON String, Number, ...)
 * </pre>
 *
 * <p>That {@code 'I'} is the first letter of "Here is the bug report:". The model
 * answered correctly and the pipeline threw the answer away, then reported the run as
 * a provider failure — so a perfectly good response was indistinguishable from a
 * broken provider.</p>
 *
 * <p>What is handled, all of it observed in real responses:</p>
 * <ul>
 *   <li>fenced blocks — {@code ```json … ```}, {@code ``` … ```}, fences with
 *       surrounding prose or trailing commentary</li>
 *   <li>prose before and after the JSON, which is the common case</li>
 *   <li>trailing commas before {@code }} or {@code ]}</li>
 *   <li>a leading byte-order mark</li>
 *   <li>brace-like text in the prose, e.g. "use {@code {placeholder}} here"</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> handled: single-quoted or unquoted keys, unescaped
 * newlines inside strings, or truncated output. Those are genuinely broken responses
 * and silently repairing them would produce a wrong answer that looks right — worse
 * than a clear failure.</p>
 *
 * <p>String manipulation only, no JSON parser. The validators and the parsers must
 * agree on what the JSON <em>is</em>, and they do so by sharing this one method; if the
 * extractor also parsed, the two could disagree about which candidate was the answer.</p>
 */
public final class LlmJson {

    private LlmJson() {
    }

    /** Raised when a response contains nothing that could be JSON. */
    public static class ExtractionException extends RuntimeException {
        public ExtractionException(String message) {
            super(message);
        }
    }

    /**
     * @return the JSON value found in {@code raw}, with fences, prose and trailing
     *         commas removed
     * @throws ExtractionException when no JSON value is present, or the input is null
     */
    public static String extract(String raw) {
        String json = tryExtract(raw);
        if (json == null) {
            throw new ExtractionException(describe(raw));
        }
        return json;
    }

    /**
     * @return the JSON value, or {@code null} when there is none. For callers that
     *         treat an unparseable response as "no result" rather than an error.
     */
    public static String tryExtract(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripBom(raw);

        // A fence is an explicit statement of where the JSON is, so trust it over any
        // brace-shaped text in the surrounding prose.
        String fenced = firstFencedBlock(text);
        if (fenced != null) {
            String candidate = firstJsonValue(fenced);
            if (candidate != null) {
                return candidate;
            }
            // A fence that holds no JSON is a broken response, but the text outside it
            // may still hold a usable value, so fall through rather than give up.
        }
        return firstJsonValue(text);
    }

    /**
     * Scans for the first balanced top-level {@code {} or {@code [] value.
     *
     * <p>Balance is tracked with a depth counter that ignores braces inside string
     * literals, so a value like {@code {"note": "use {x} here"}} is not cut short at
     * the brace in the string. Trailing commas are dropped during the scan, which is
     * why the string is assembled rather than sliced.</p>
     */
    private static String firstJsonValue(String text) {
        int length = text.length();
        for (int start = 0; start < length; start++) {
            char c = text.charAt(start);
            if (c != '{' && c != '[') {
                continue;
            }
            String candidate = balanced(text, start);
            if (candidate != null && looksLikeJson(candidate)) {
                return candidate;
            }
            // Not a usable value at this position. Skip past its extent if it was
            // balanced but rejected, otherwise advance one character and retry.
            if (candidate != null) {
                start += candidate.length() - 1;
            }
        }
        return null;
    }

    /** @return the balanced value starting at {@code from}, or null if unbalanced. */
    private static String balanced(String text, int from) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        boolean sawValue = false;

        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    inString = true;
                    sawValue = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    depth++;
                    sawValue = true;
                    out.append(c);
                }
                case '}', ']' -> {
                    depth--;
                    out.append(c);
                    if (depth == 0) {
                        return sawValue ? out.toString() : null;
                    }
                }
                case ',' -> {
                    // A comma with nothing but whitespace and a closer after it is a
                    // trailing comma. Dropping it here rather than in a second pass
                    // means commas inside strings are never even considered.
                    if (isTrailingComma(text, i)) {
                        continue;
                    }
                    out.append(c);
                }
                default -> {
                    if (!Character.isWhitespace(c)) {
                        sawValue = true;
                    }
                    out.append(c);
                }
            }
        }
        return null;
    }

    /** True when only whitespace separates this comma from a closing brace or bracket. */
    private static boolean isTrailingComma(String text, int commaIndex) {
        for (int i = commaIndex + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '}' || c == ']';
        }
        // A comma at the very end with nothing after it is also trailing.
        return true;
    }

    /**
     * Rejects brace-shaped prose that is not a JSON value.
     *
     * <p>"Use {@code {placeholder}} in the config" is a balanced, string-legal candidate
     * that Jackson would reject with an opaque message. A JSON object must begin with a
     * quoted key, a nested value or the closing brace; anything else is prose. Enough to
     * skip the common case without a parser.</p>
     */
    private static boolean looksLikeJson(String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        char first = candidate.charAt(0);
        if (first == '[') {
            return true;
        }
        if (first != '{') {
            return false;
        }
        for (int i = 1; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            // An object may be empty, or start with a quoted key or a nested value.
            return c == '}' || c == '"' || c == '{' || c == '[';
        }
        return false;
    }

    /**
     * @return the contents of the first fenced block, or {@code null} if there is none.
     *         An unterminated fence runs to the end of the response, which is what a
     *         truncated response looks like.
     */
    private static String firstFencedBlock(String text) {
        int fence = text.indexOf("```");
        if (fence < 0) {
            return null;
        }
        int contentStart = fence + 3;
        // Skip an optional language tag on the opening fence line.
        int lineEnd = text.indexOf('\n', contentStart);
        if (lineEnd < 0) {
            return null;
        }
        String afterFence = text.substring(contentStart, lineEnd).trim();
        if (!afterFence.isEmpty() && !isLanguageTag(afterFence)) {
            // Something other than a language tag shares the fence line, so this is
            // not an opening fence after all.
            return null;
        }
        int close = text.indexOf("```", lineEnd + 1);
        if (close < 0) {
            return text.substring(lineEnd + 1);
        }
        return text.substring(lineEnd + 1, close);
    }

    private static boolean isLanguageTag(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '+') {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    /** A message that says what was actually received, since "invalid JSON" never helps. */
    private static String describe(String raw) {
        if (raw == null) {
            return "LLM response was null; expected a JSON value";
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return "LLM response was empty; expected a JSON value";
        }
        String head = trimmed.length() > 120 ? trimmed.substring(0, 120) + "…" : trimmed;
        return "No JSON value found in the LLM response. First 120 characters were: " + head;
    }
}
