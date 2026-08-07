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
                root = MAPPER.readTree(extractJson(response));
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
                JsonNode root = MAPPER.readTree(extractJson(response));
                if (!root.isObject()) {
                    return "valid JSON but not an object";
                }
                return null;
            } catch (Exception e) {
                return "invalid JSON: " + e.getMessage();
            }
        };
    }

    private static String extractJson(String response) {
        if (response == null) {
            return "";
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
