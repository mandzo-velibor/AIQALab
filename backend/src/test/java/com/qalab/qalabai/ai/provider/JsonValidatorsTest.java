package com.qalab.qalabai.ai.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validator and the parser must agree on what the JSON <em>is</em>.
 *
 * <p>They used to be separate implementations of the same idea, which is how they
 * drifted: {@code JsonValidators} had its own fence-stripper. A response the validator
 * accepted could then be rejected by the parser downstream, wasting a provider call and
 * — because the validators drive the cascade's fallback decision — spending a fallback
 * on a response that was never actually malformed. Both now call {@link LlmJson}.</p>
 */
class JsonValidatorsTest {

    @Test
    void proseWrappedJsonNowPassesValidation() {
        // This is the case that used to fail: the validator saw "Here is …" and
        // rejected a perfectly good response, which sent the cascade off to a
        // fallback provider for no reason.
        assertNull(JsonValidators.isJsonObject().validate(
                        "Here is the JSON you asked for:\n{\"ok\": true}"),
                "prose around a valid object must not fail validation");
    }

    @Test
    void fencedJsonPassesValidation() {
        assertNull(JsonValidators.isJsonObject().validate("```json\n{\"ok\": true}\n```"));
        assertNull(JsonValidators.isJsonObject().validate("```\n{\"ok\": true}\n```"));
    }

    @Test
    void trailingCommasPassValidation() {
        assertNull(JsonValidators.isJsonObject().validate("{\"ok\": true,}"));
    }

    @Test
    void aGenuinelyInvalidResponseStillFailsValidation() {
        String reason = JsonValidators.isJsonObject().validate("I cannot help with that.");

        assertNotNull(reason, "prose with no JSON must still be rejected");
        assertTrue(reason.contains("No JSON value found"), reason);
    }

    @Test
    void theFieldChecksStillWorkThroughTheSharedExtractor() {
        var validator = JsonValidators.hasArrayField("scenarios");

        assertNull(validator.validate("Here you go:\n```json\n{\"scenarios\": [{\"name\": \"a\"}]}\n```"));
        assertNotNull(validator.validate("{\"scenarios\": \"not an array\"}"));
        assertNotNull(validator.validate("{\"other\": []}"));
    }

    @Test
    void theValidationReasonReachesTheProvider() {
        // The reason is what the cascade logs when it moves on, so it has to describe
        // the actual problem rather than a generic parse failure.
        String reason = JsonValidators.hasArrayField("scenarios").validate("no json at all");

        assertNotNull(reason);
        assertTrue(reason.startsWith("invalid JSON: No JSON value found"), reason);
    }
}
