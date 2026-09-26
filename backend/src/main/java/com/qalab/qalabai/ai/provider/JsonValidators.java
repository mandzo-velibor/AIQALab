package com.qalab.qalabai.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonValidators {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonValidators() {
    }

    public static ResponseValidator hasArrayField(String field) {
        return response -> {
            JsonNode root;
            try {
                root = MAPPER.readTree(LlmJson.extract(response));
            } catch (Exception e) {
                return "invalid JSON: " + e.getMessage();
            }
            if (!root.has(field)) {
                return "valid JSON but missing field \"" + field + "\"";
            }
            if (!root.path(field).isArray()) {
                return "field \"" + field + "\" is present but not an array";
            }
            return null;
        };
    }

    public static ResponseValidator isJsonObject() {
        return response -> {
            try {
                JsonNode root = MAPPER.readTree(LlmJson.extract(response));
                if (!root.isObject()) {
                    return "valid JSON but not an object";
                }
                return null;
            } catch (Exception e) {
                return "invalid JSON: " + e.getMessage();
            }
        };
    }

}
