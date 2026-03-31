package com.insureflow.agent.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ResponseParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Extracts JSON from LLM response.
     * Uses FIRST { and LAST } to get the outermost object.
     * llama3.2-vision sometimes wraps JSON in markdown or adds text after it.
     */
    public static String extractJson(String llmResponse) {
        if (llmResponse == null) return "{}";
        String cleaned = llmResponse.trim();

        // Remove markdown code fences
        cleaned = cleaned.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        // Use FIRST { and LAST } — gets the outermost JSON object
        int firstStart = cleaned.indexOf('{');
        int lastEnd    = cleaned.lastIndexOf('}');

        if (firstStart >= 0 && lastEnd > firstStart) {
            return cleaned.substring(firstStart, lastEnd + 1);
        }

        return "{}";
    }

    public static String getString(String json, String field, String defaultValue) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode f = node.get(field);
            return (f != null && !f.isNull()) ? f.asText() : defaultValue;
        } catch (Exception e) {
            log.warn("Could not parse field '{}' from JSON: {}", field, e.getMessage());
            return defaultValue;
        }
    }

    public static double getDouble(String json, String field, double defaultValue) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode f = node.get(field);
            return (f != null && !f.isNull()) ? f.asDouble() : defaultValue;
        } catch (Exception e) {
            log.warn("Could not parse field '{}' from JSON: {}", field, e.getMessage());
            return defaultValue;
        }
    }

    public static boolean getBoolean(String json, String field, boolean defaultValue) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode f = node.get(field);
            return (f != null && !f.isNull()) ? f.asBoolean() : defaultValue;
        } catch (Exception e) {
            log.warn("Could not parse field '{}' from JSON: {}", field, e.getMessage());
            return defaultValue;
        }
    }
}