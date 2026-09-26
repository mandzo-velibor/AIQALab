package com.qalab.qalabai.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nine copies of this logic existed, each stripping a leading {@code ```} fence and
 * nothing else, and each handing the remainder straight to Jackson. The result was
 * logged in production as:
 *
 * <pre>
 * WARN BugReportService : Failed to parse bug report AI response:
 *       Unrecognized token 'I': was expecting (JSON String, Number, ...)
 * </pre>
 *
 * <p>That {@code 'I'} is the first letter of "Here is…". The model had answered
 * correctly and the pipeline discarded the answer, then reported the run as a provider
 * failure — so a good response was indistinguishable from a broken provider, and the
 * fallback would have spent more money re-asking a question already answered.</p>
 *
 * <p>These cases are the shapes observed in real responses, not invented ones.</p>
 */
class LlmJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- the failure that motivated the whole task ----

    @Test
    void proseBeforeTheJsonIsTolerated() {
        // The exact shape from the log line above.
        String response = "Here is the bug report you asked for:\n"
                + "{\"title\": \"Login fails\", \"severity\": \"HIGH\"}";

        JsonNode node = parse(response);

        assertEquals("Login fails", node.path("title").asText());
        assertEquals("HIGH", node.path("severity").asText());
    }

    @Test
    void proseAfterTheJsonIsTolerated() {
        String response = "{\"ok\": true}\n\nLet me know if you want more detail.";

        assertTrue(parse(response).path("ok").asBoolean());
    }

    @Test
    void proseOnBothSidesIsTolerated() {
        String response = "Sure! Based on the trace, here's the analysis:\n"
                + "{\"classification\": \"ASSERTION_FAILURE\"}\n"
                + "I classified it as an assertion failure because the locator never resolved.";

        assertEquals("ASSERTION_FAILURE", parse(response).path("classification").asText());
    }

    // ---- fenced blocks ----

    @Test
    void aJsonFenceIsStripped() {
        String response = """
                ```json
                {"scenario": "login", "priority": "HIGH"}
                ```""";

        assertEquals("login", parse(response).path("scenario").asText());
    }

    @Test
    void aBareFenceIsStripped() {
        String response = """
                ```
                {"scenario": "login"}
                ```""";

        assertEquals("login", parse(response).path("scenario").asText());
    }

    @Test
    void aFenceWrappedInProseIsStripped() {
        String response = "Here is the plan you requested:\n\n"
                + "```json\n{\"scenarios\": [{\"name\": \"Login\"}]}\n```\n\n"
                + "Note that I prioritised the happy path.";

        assertEquals("Login", parse(response).path("scenarios").get(0).path("name").asText());
    }

    @Test
    void anUnterminatedFenceRunsToTheEndOfTheResponse() {
        // The closing fence never arrived — a stream cut after the JSON. The value
        // itself is complete, so it is still usable.
        String response = "```json\n{\"title\": \"Complete\", \"severity\": \"LOW\"}\n";

        JsonNode node = parse(response);
        assertEquals("Complete", node.path("title").asText());
        assertEquals("LOW", node.path("severity").asText());
    }

    @Test
    void truncatedJsonIsNotCompletedByGuessing() {
        // A cut-off value has no defensible completion: inventing the missing braces
        // would produce an object the model never wrote, and it would look valid.
        String response = "```json\n{\"title\": \"Partial\", \"severity\":";

        assertThrows(LlmJson.ExtractionException.class, () -> LlmJson.extract(response));
    }

    @Test
    void jsonOutsideTheFenceIsPreferredWhenTheFenceHoldsNoJson() {
        String response = "Example of the format:\n```\n{not json}\n```\n"
                + "But here is the real answer: {\"ok\": true}";

        assertTrue(parse(response).path("ok").asBoolean());
    }

    // ---- trailing commas, which Jackson rejects by default ----

    @Test
    void trailingCommasInObjectsAreRemoved() {
        String response = "{\"a\": 1, \"b\": 2,}";

        JsonNode node = parse(response);
        assertEquals(1, node.path("a").asInt());
        assertEquals(2, node.path("b").asInt());
    }

    @Test
    void trailingCommasInArraysAreRemoved() {
        JsonNode node = parse("{\"items\": [1, 2, 3,],}");

        assertEquals(3, node.path("items").size());
        assertEquals(3, node.path("items").get(2).asInt());
    }

    @Test
    void aCommaInsideAStringIsNotMistakenForATrailingComma() {
        String response = "{\"note\": \"a, b, c\", \"count\": 2}";

        JsonNode node = parse(response);
        assertEquals("a, b, c", node.path("note").asText());
        assertEquals(2, node.path("count").asInt());
    }

    // ---- braces in strings and in prose ----

    @Test
    void bracesInsideStringValuesDoNotEndTheValue() {
        String response = "{\"template\": \"user {id} not found\", \"severity\": \"LOW\"}";

        JsonNode node = parse(response);
        assertEquals("user {id} not found", node.path("template").asText());
        assertEquals("LOW", node.path("severity").asText());
    }

    @Test
    void escapedQuotesInsideStringsDoNotConfuseTheScanner() {
        String response = "{\"quote\": \"she said \\\"{ not json }\\\"\", \"ok\": true}";

        JsonNode node = parse(response);
        assertTrue(node.path("ok").asBoolean());
        assertTrue(node.path("quote").asText().contains("not json"));
    }

    @Test
    void nestedObjectsAreKeptWhole() {
        String response = """
                Here you go:
                {"page": {"forms": [{"name": "login", "inputs": ["user", "pass"]}]}}
                """;

        JsonNode node = parse(response);
        assertEquals("login", node.path("page").path("forms").get(0).path("name").asText());
    }

    @Test
    void braceShapedProseIsSkippedInFavourOfRealJson() {
        // "Use {placeholder} in the config" is a balanced candidate that Jackson
        // rejects with an opaque message. Without a shape check it would win.
        String response = "Use {placeholder} for the value. The result is {\"ok\": true}";

        assertTrue(parse(response).path("ok").asBoolean());
    }

    @Test
    void aTopLevelArrayIsAccepted() {
        JsonNode node = parse("The forms I found:\n[{\"name\": \"login\"}, {\"name\": \"search\"}]");

        assertTrue(node.isArray());
        assertEquals(2, node.size());
        assertEquals("search", node.get(1).path("name").asText());
    }

    @Test
    void aByteOrderMarkDoesNotDefeatTheScan() {
        String response = "\uFEFF{\"ok\": true}";

        assertTrue(parse(response).path("ok").asBoolean());
    }

    // ---- genuinely broken responses fail clearly, and are not silently repaired ----

    @ParameterizedTest
    @ValueSource(strings = {
            "I cannot help with that request.",
            "",
            "   \n  ",
            "{\"unterminated\": ",
            "{\"a\": 1",
    })
    void unparseableResponsesAreRejectedRatherThanGuessedAt(String response) {
        assertNull(LlmJson.tryExtract(response),
                "must not invent a value from " + response);
        assertThrows(LlmJson.ExtractionException.class, () -> LlmJson.extract(response));
    }

    @Test
    void nullIsRejectedWithAMessageThatSaysSo() {
        LlmJson.ExtractionException ex =
                assertThrows(LlmJson.ExtractionException.class, () -> LlmJson.extract(null));
        assertTrue(ex.getMessage().contains("null"), ex.getMessage());
    }

    @Test
    void theFailureMessageShowsWhatWasActuallyReceived() {
        // "Invalid JSON" tells nobody anything. The first characters are what make a
        // log line diagnosable.
        LlmJson.ExtractionException ex = assertThrows(LlmJson.ExtractionException.class,
                () -> LlmJson.extract("I'm sorry, but I can't produce that."));

        assertTrue(ex.getMessage().contains("I'm sorry"), ex.getMessage());
    }

    @Test
    void aLongResponseIsTruncatedInTheErrorMessage() {
        String response = "no json here " + "x".repeat(500);
        LlmJson.ExtractionException ex =
                assertThrows(LlmJson.ExtractionException.class, () -> LlmJson.extract(response));

        assertTrue(ex.getMessage().length() < 400, "the message must stay loggable");
    }

    // ---- single quotes and unquoted keys stay broken on purpose ----

    @Test
    void singleQuotedKeysAreNotSilentlyRepaired() {
        // Repairing this would produce a plausible object that is not what the model
        // said. A clear failure is the better outcome.
        assertNull(LlmJson.tryExtract("{'name': 'login'}"),
                "a single-quoted object is a broken response, not one to guess at");
    }

    @Test
    void anUnquotedKeyIsNotSilentlyRepaired() {
        assertNull(LlmJson.tryExtract("{name: \"login\"}"));
    }

    // ---- the shared-extractor contract ----

    @Test
    void extractIsIdempotent() {
        // The validator and the parser must agree, so running the extractor on its own
        // output has to be a no-op — otherwise a validator that runs it twice sees a
        // different string from a parser that runs it once.
        String once = LlmJson.extract("prose {\"a\": 1,} more prose");

        assertEquals(once, LlmJson.extract(once));
    }

    @Test
    void theCanonicalExamples() {
        // An explicit table rather than @CsvSource: these inputs contain newlines and
        // braces, which CSV quoting handles badly and obscures.
        record Case(String raw, String expected) {
        }
        List<Case> cases = List.of(
                new Case("{\"a\":1}", "{\"a\":1}"),
                new Case("```json\n{\"a\":1}\n```", "{\"a\":1}"),
                new Case("text ```json {\"a\":1} ``` more", "{\"a\":1}"),
                new Case("{\"a\":1,}", "{\"a\":1}"),
                new Case("prefix\n{\"a\":1}\nsuffix", "{\"a\":1}"));

        for (Case testCase : cases) {
            assertEquals(testCase.expected(), LlmJson.extract(testCase.raw()),
                    "failed for: " + testCase.raw());
        }
    }

    @Test
    void theFirstOfSeveralJsonValuesIsTaken() {
        String response = "{\"first\": 1}\n{\"second\": 2}";

        assertEquals(1, parse(response).path("first").asInt());
    }

    @Test
    void anEmptyObjectIsAValidValue() {
        assertEquals("{}", LlmJson.extract("Nothing to report: {}"));
        assertEquals(0, parse("Nothing to report: {}").size());
    }

    @Test
    void anEmptyArrayIsAValidValue() {
        assertEquals("[]", LlmJson.extract("No forms found: []"));
    }

    @Test
    void tryExtractReturnsTheSameValueAsExtract() {
        String raw = "Here: {\"ok\": true}";

        assertEquals(LlmJson.extract(raw), LlmJson.tryExtract(raw));
    }

    @Test
    void everyExtractedValueParses() {
        // The point of the class: whatever comes out must be loadable, otherwise the
        // tolerance just moves the parse error somewhere less obvious.
        String[] responses = {
                "{\"a\": 1}",
                "prose {\"a\": 1} prose",
                "```json\n{\"a\": 1,}\n```",
                "[1, 2,]",
                "{\"n\": \"a, b, c\"}",
                "\uFEFF{\"a\": 1}",
        };
        for (String response : responses) {
            String json = LlmJson.extract(response);
            assertFalse(json.isBlank());
            try {
                mapper.readTree(json);
            } catch (Exception e) {
                throw new AssertionError("extracted unparseable JSON from: " + response
                        + "\n  got: " + json, e);
            }
        }
    }

    private JsonNode parse(String response) {
        try {
            return mapper.readTree(LlmJson.extract(response));
        } catch (LlmJson.ExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("extracted unparseable JSON: " + e.getMessage(), e);
        }
    }
}
