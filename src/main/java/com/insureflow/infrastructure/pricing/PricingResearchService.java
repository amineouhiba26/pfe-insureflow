package com.insureflow.infrastructure.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic pricing service — zero hardcoded prices.
 *
 * Strategy :
 * 1. Tavily web search  → finds real Tunisian market prices from the web
 * 2. LLM fallback       → llama3.1:8b estimates based on training knowledge
 *
 * Price validation : minimum 100 TND, picks highest range found
 * (avoids partial costs like "labour only: 50 TND").
 */
@Service
public class PricingResearchService {

    private static final Logger log = LoggerFactory.getLogger(PricingResearchService.class);

    private static final long   MIN_REALISTIC_PRICE = 100;
    private static final long   MAX_REALISTIC_PRICE = 150_000;
    private static final double MAX_RATIO           = 10.0;

    @Value("${tavily.api-key:}")
    private String tavilyKey;

    private final ChatLanguageModel llm;
    private final HttpClient        httpClient;
    private final ObjectMapper      mapper = new ObjectMapper();

    public PricingResearchService(
            @Qualifier("chatLanguageModel") ChatLanguageModel llm) {
        this.llm        = llm;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    public Optional<PriceRange> searchRepairCost(String elementName,
                                                 String severity,
                                                 String vehicleInfo,
                                                 String claimType) {
        log.info("[PRICING] Searching cost for '{}' severity={} vehicle='{}'",
                elementName, severity, vehicleInfo);

        // Step 1 — Tavily web search
        Optional<PriceRange> tavilyResult =
                searchWithTavily(elementName, severity, vehicleInfo, claimType);

        if (tavilyResult.isPresent()) {
            log.info("[PRICING] Tavily found price for '{}'", elementName);
            return tavilyResult;
        }

        // Step 2 — LLM fallback
        log.info("[PRICING] Tavily found nothing — LLM estimation for '{}'", elementName);
        return estimateWithLlm(elementName, severity, vehicleInfo, claimType);
    }

    // ── Tavily search ─────────────────────────────────────────────────────────

    private Optional<PriceRange> searchWithTavily(String elementName,
                                                  String severity,
                                                  String vehicleInfo,
                                                  String claimType) {
        if (tavilyKey == null || tavilyKey.isBlank()) {
            log.warn("[TAVILY] API key not configured");
            return Optional.empty();
        }

        String query = buildSearchQuery(elementName, severity, vehicleInfo, claimType);
        log.debug("[TAVILY] Query: '{}'", query);

        try {
            Map<String, Object> body = Map.of(
                    "api_key",        tavilyKey,
                    "query",          query,
                    "search_depth",   "basic",
                    "max_results",    5,
                    "include_answer", true
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[TAVILY] HTTP {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            return parseTavilyResponse(response.body(), elementName, severity);

        } catch (Exception e) {
            log.warn("[TAVILY] Failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<PriceRange> parseTavilyResponse(String responseBody,
                                                     String elementName,
                                                     String severity) {
        try {
            JsonNode root    = mapper.readTree(responseBody);
            String   answer  = root.path("answer").asText("");
            StringBuilder ctx = new StringBuilder();

            if (!answer.isBlank()) ctx.append(answer).append("\n");

            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    String content = r.path("content").asText("");
                    if (!content.isBlank()) {
                        ctx.append(content, 0,
                                Math.min(content.length(), 500)).append("\n");
                    }
                }
            }

            if (ctx.isEmpty()) return Optional.empty();

            // Try direct regex extraction first
            Optional<PriceRange> extracted = extractPrices(ctx.toString(), "Tavily");
            if (extracted.isPresent()) return extracted;

            // If no valid range found, ask LLM to extract from context
            return extractPricesWithLlm(ctx.toString(), elementName, severity);

        } catch (Exception e) {
            log.warn("[TAVILY] Parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── LLM estimation fallback ───────────────────────────────────────────────

    private Optional<PriceRange> estimateWithLlm(String elementName,
                                                 String severity,
                                                 String vehicleInfo,
                                                 String claimType) {
        try {
            String prompt = buildLlmPricingPrompt(elementName, severity, vehicleInfo, claimType);
            dev.langchain4j.data.message.UserMessage msg =
                    dev.langchain4j.data.message.UserMessage.from(prompt);
            String response = llm.generate(msg).content().text().trim();
            log.debug("[LLM PRICING] Response: {}", response);
            return extractPrices(response, "LLM estimation");
        } catch (Exception e) {
            log.warn("[LLM PRICING] Failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<PriceRange> extractPricesWithLlm(String context,
                                                      String elementName,
                                                      String severity) {
        try {
            String prompt = String.format("""
                Voici des informations sur le coût de réparation de '%s' (sévérité: %s) en Tunisie.
                
                Contexte :
                %s
                
                Basé sur ce contexte ET ta connaissance du marché tunisien 2025,
                donne une estimation réaliste du coût TOTAL en TND (pièce + main d'œuvre).
                Prix minimum attendu : 300 TND pour une pièce automobile.
                
                Réponds UNIQUEMENT avec deux nombres séparés par un tiret : MIN-MAX
                Exemple : 800-1500
                """,
                    elementName, severity,
                    context.substring(0, Math.min(context.length(), 600)));

            dev.langchain4j.data.message.UserMessage msg =
                    dev.langchain4j.data.message.UserMessage.from(prompt);
            String response = llm.generate(msg).content().text().trim();
            log.debug("[LLM EXTRACT] Response: {}", response);
            return extractPrices(response, "Tavily + LLM extraction");
        } catch (Exception e) {
            log.warn("[LLM EXTRACT] Failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildLlmPricingPrompt(String elementName, String severity,
                                         String vehicleInfo, String claimType) {
        String elementFr = translateElement(elementName);
        String action    = isReplacement(severity) ? "remplacement complet" : "réparation";

        if ("VEHICLE_DAMAGE".equals(claimType) && vehicleInfo != null) {
            return String.format("""
                Expert en réparation automobile en Tunisie.
                Donne le coût TOTAL réaliste de %s de %s pour un %s en Tunisie en 2025.
                Inclus pièce neuve ou d'occasion + main d'œuvre + peinture si nécessaire.
                Prix minimum attendu : 500 TND. Prix typique : 800-3000 TND selon la pièce.
                Réponds UNIQUEMENT avec deux nombres en TND séparés par un tiret : MIN-MAX
                Exemple : 800-1500
                """, action, elementFr, vehicleInfo);
        } else {
            return String.format("""
                Expert en réparation en Tunisie.
                Donne le coût TOTAL réaliste de %s de %s en Tunisie en 2025.
                Inclus matériaux + main d'œuvre.
                Prix minimum attendu : 300 TND.
                Réponds UNIQUEMENT avec deux nombres en TND séparés par un tiret : MIN-MAX
                Exemple : 500-1200
                """, action, elementFr);
        }
    }

    // ── Price extraction with validation ─────────────────────────────────────

    /**
     * Extracts price range from text.
     * Picks the HIGHEST valid range — avoids partial costs (labour only, paint only).
     * Validates: min >= 100 TND, max <= 150,000 TND, ratio max/min <= 10.
     */
    private Optional<PriceRange> extractPrices(String text, String source) {
        if (text == null || text.isBlank()) return Optional.empty();

        Pattern pattern = Pattern.compile(
                "(\\d{2,6})\\s*[-–—à]\\s*(\\d{2,6})");
        Matcher matcher = pattern.matcher(text);

        List<long[]> ranges = new ArrayList<>();
        while (matcher.find()) {
            try {
                long min = Long.parseLong(matcher.group(1).replace(",", ""));
                long max = Long.parseLong(matcher.group(2).replace(",", ""));

                if (min >= MIN_REALISTIC_PRICE
                        && max <= MAX_REALISTIC_PRICE
                        && max > min
                        && (double) max / min <= MAX_RATIO) {
                    ranges.add(new long[]{min, max});
                }
            } catch (NumberFormatException ignored) {}
        }

        if (ranges.isEmpty()) return Optional.empty();

        // Pick range with highest min — most likely the full repair cost
        long[] best = ranges.stream()
                .max(Comparator.comparingLong(r -> r[0]))
                .orElse(ranges.get(0));

        BigDecimal min = BigDecimal.valueOf(best[0]);
        BigDecimal max = BigDecimal.valueOf(best[1]);
        BigDecimal mid = min.add(max)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        log.info("[PRICING] Range extracted: {}-{} TND [{}]", min, max, source);
        return Optional.of(new PriceRange(min, max, mid, "pièces + main d'œuvre", source));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildSearchQuery(String elementName, String severity,
                                    String vehicleInfo, String claimType) {
        String elementFr = translateElement(elementName);
        String action    = isReplacement(severity) ? "remplacement" : "réparation";

        StringBuilder q = new StringBuilder();
        q.append("coût ").append(action).append(" ").append(elementFr);

        if ("VEHICLE_DAMAGE".equals(claimType)
                && vehicleInfo != null && !vehicleInfo.isBlank()) {
            q.append(" ").append(vehicleInfo);
        }

        q.append(" Tunisie prix TND 2025 garage");
        return q.toString();
    }

    private boolean isReplacement(String severity) {
        return "TOTAL_LOSS".equals(severity) || "SEVERE".equals(severity);
    }

    private String translateElement(String element) {
        return switch (element.toLowerCase().trim()) {
            case "front bumper"      -> "pare-choc avant";
            case "rear bumper"       -> "pare-choc arrière";
            case "hood"              -> "capot";
            case "trunk"             -> "coffre";
            case "door"              -> "portière";
            case "windshield"        -> "pare-brise";
            case "rear window"       -> "vitre arrière";
            case "side mirror"       -> "rétroviseur";
            case "headlight"         -> "phare avant";
            case "taillight"         -> "feu arrière";
            case "wheel"             -> "roue jante";
            case "roof"              -> "toit pavillon";
            case "engine"            -> "moteur";
            case "chassis"           -> "châssis";
            case "wall"              -> "mur";
            case "floor"             -> "carrelage sol";
            case "window"            -> "fenêtre";
            case "electrical system" -> "installation électrique";
            case "furniture"         -> "mobilier";
            case "appliances"        -> "appareils électroménagers";
            case "ceiling"           -> "plafond";
            case "plumbing"          -> "plomberie";
            case "hospitalization"   -> "hospitalisation";
            case "surgery"           -> "chirurgie";
            case "medication"        -> "médicaments";
            case "rehabilitation"    -> "rééducation";
            default                  -> element;
        };
    }

    public record PriceRange(
            BigDecimal min,
            BigDecimal max,
            BigDecimal midpoint,
            String     includes,
            String     source) {}
}