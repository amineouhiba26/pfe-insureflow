// ResponseParser.java
package com.insureflow.agent.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for safely extracting fields from LLM JSON responses.
 *
 * Why do we need this?
 * LLMs sometimes return JSON wrapped in markdown:
 *   ```json
 *   {"claimType":"VEHICLE_DAMAGE"}
 *   ```
 * Or they add extra text before/after the JSON.
 * This class strips all of that and gives you clean field access.
 *
 * We use Jackson's ObjectMapper to parse the JSON tree.
 * All methods are static — no state, no Spring bean needed.
 */
public class ResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ResponseParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Strips markdown code fences and extracts the raw JSON string.
     * Input:  "```json\n{\"key\":\"val\"}\n```"
     * Output: "{\"key\":\"val\"}"
     */
    public static String extractJson(String llmResponse) {
        if (llmResponse == null) return "{}";
        String cleaned = llmResponse.trim();
        // Remove ```json ... ``` fences
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
        }
        // Find first { to last }
        int start = cleaned.indexOf('{');
        int end   = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return "{}";
    }

    /**
     * Safely reads a String field from JSON.
     * Returns defaultValue if the field is missing or JSON is invalid.
     */
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

    /**
     * Safely reads a double field from JSON.
     */
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

    /**
     * Safely reads a boolean field from JSON.
     */
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