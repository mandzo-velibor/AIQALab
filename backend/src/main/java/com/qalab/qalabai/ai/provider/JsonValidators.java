package com.qalab.qalabai.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonValidators {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonValidators() {
    }

    public static ResponseValidator hasArrayField(String field) {
        return response -> {
            try {
                JsonNode root = MAPPER.readTree(extractJson(response));
                return root.has(field) && root.path(field).isArray();
            } catch (Exception e) {
                return false;
            }
        };
    }

    public static ResponseValidator isJsonObject() {
        return response -> {
            try {
                return MAPPER.readTree(extractJson(response)).isObject();
            } catch (Exception e) {
                return false;
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
