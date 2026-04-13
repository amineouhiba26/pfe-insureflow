package com.insureflow.agent.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ResponseParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Extracts the first valid JSON object from an LLM response.
     * Handles markdown fences, leading text, and nested objects correctly.
     */
    public static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";

        // Strip markdown fences
        raw = raw.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        // Find first { and matching }
        int start = raw.indexOf('{');
        if (start < 0) return "{}";

        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = raw.substring(start, i + 1);
                    // Validate it's actually parseable
                    try {
                        mapper.readTree(candidate);
                        return candidate;
                    } catch (Exception e) {
                        log.warn("[PARSER] Invalid JSON candidate, continuing search");
                    }
                }
            }
        }
        return "{}";
    }

    public static String getString(String json, String field, String defaultValue) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asText() : defaultValue;
        } catch (Exception e) { return defaultValue; }
    }

    public static double getDouble(String json, String field, double defaultValue) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asDouble() : defaultValue;
        } catch (Exception e) { return defaultValue; }
    }

    public static boolean getBoolean(String json, String field, boolean defaultValue) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode val  = node.get(field);
            return (val != null && !val.isNull()) ? val.asBoolean() : defaultValue;
        } catch (Exception e) { return defaultValue; }
    }
}