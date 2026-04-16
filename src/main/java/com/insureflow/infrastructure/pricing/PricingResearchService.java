package com.insureflow.infrastructure.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PricingResearchService {

    private static final Logger log = LoggerFactory.getLogger(PricingResearchService.class);
    private static final String SERP_API_URL = "https://serpapi.com/search.json";

    @Value("${serpapi.api-key:}")
    private String serpApiKey;

    private final HttpClient   httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PricingResearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Optional<PriceRange> searchRepairCost(String element, String severity,
                                                 String vehicleInfo, String claimType) {
        if (serpApiKey == null || serpApiKey.isBlank()) {
            log.warn("[PRICING] SerpAPI key not configured — skipped");
            return Optional.empty();
        }

        List<String> queries = buildQueries(element, severity, vehicleInfo, claimType);

        for (String query : queries) {
            log.info("[PRICING] SerpAPI search: '{}'", query);
            try {
                Optional<PriceRange> result = searchWithSerpApi(query, severity);
                if (result.isPresent()) {
                    log.info("[PRICING] Found price for '{}' sev={}: {}-{} TND",
                            element, severity, result.get().min(), result.get().max());
                    return result;
                }
            } catch (Exception e) {
                log.warn("[PRICING] SerpAPI failed for '{}': {}", query, e.getMessage());
            }
        }

        log.warn("[PRICING] All queries exhausted for '{}' sev={}", element, severity);
        return Optional.empty();
    }

    // ── Query builder — severity-aware ────────────────────────────────────────

    private List<String> buildQueries(String element, String severity,
                                      String vehicleInfo, String claimType) {
        List<String> queries = new ArrayList<>();
        String el = translateElement(element);

        if ("VEHICLE_DAMAGE".equals(claimType)) {
            switch (severity) {
                case "MINOR" -> {
                    // Scratch/cosmetic — search for polish, touch-up, scratch repair
                    if (vehicleInfo != null)
                        queries.add(String.format("prix retouche rayure %s %s Tunisie TND", el, vehicleInfo));
                    queries.add(String.format("prix réparation rayure %s carrosserie Tunisie dinars", el));
                    queries.add(String.format("retouche peinture %s Tunisie garage TND", el));
                    queries.add(String.format("scratch repair %s Tunisia TND price", el));
                    queries.add(String.format("prix polish rayure voiture Tunisie 2024 2025", el));
                }
                case "MODERATE" -> {
                    if (vehicleInfo != null)
                        queries.add(String.format("prix réparation bosse %s %s Tunisie TND", el, vehicleInfo));
                    queries.add(String.format("prix débosselage %s carrosserie Tunisie dinars", el));
                    queries.add(String.format("réparation %s voiture Tunisie garage TND 2025", el));
                    queries.add(String.format("dent repair %s Tunisia TND", el));
                    queries.add(String.format("prix peinture %s Tunisie carrossier", el));
                }
                case "SEVERE" -> {
                    if (vehicleInfo != null)
                        queries.add(String.format("prix remplacement %s %s Tunisie TND", el, vehicleInfo));
                    queries.add(String.format("prix pièce remplacement %s Tunisie garage dinars", el));
                    queries.add(String.format("remplacement %s voiture Tunisie 2024 2025 TND", el));
                    queries.add(String.format("%s replacement cost Tunisia TND", el));
                    queries.add(String.format("prix pièce détachée %s Tunisie", el));
                }
                case "TOTAL_LOSS" -> {
                    if (vehicleInfo != null)
                        queries.add(String.format("valeur vénale %s Tunisie TND 2025", vehicleInfo));
                    queries.add(String.format("prix véhicule occasion %s Tunisie TND", vehicleInfo != null ? vehicleInfo : ""));
                    queries.add(String.format("indemnisation perte totale voiture Tunisie assurance TND"));
                    queries.add(String.format("valeur remplacement véhicule Tunisie dinars 2025"));
                    queries.add(String.format("car total loss value Tunisia TND"));
                }
            }
        } else if ("PROPERTY_DAMAGE".equals(claimType)) {
            queries.add(String.format("prix réparation %s bâtiment Tunisie TND 2025", el));
            queries.add(String.format("coût travaux %s Tunisie dinars entreprise", el));
            queries.add(String.format("%s repair cost building Tunisia TND", el));
            queries.add(String.format("prix rénovation %s Tunisie BTP", el));
        } else {
            queries.add(String.format("coût %s sinistre assurance Tunisie TND", el));
            queries.add(String.format("%s insurance cost Tunisia TND 2025", el));
        }

        return queries;
    }

    // ── SerpAPI call ──────────────────────────────────────────────────────────

    private Optional<PriceRange> searchWithSerpApi(String query, String severity) {
        try {
            String url = SERP_API_URL
                    + "?q="       + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&api_key=" + serpApiKey
                    + "&num=5"
                    + "&hl=fr"
                    + "&gl=tn";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[SERP] HTTP {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root    = mapper.readTree(response.body());
            JsonNode organic = root.path("organic_results");

            if (!organic.isArray() || organic.isEmpty()) return Optional.empty();

            StringBuilder ctx = new StringBuilder();

            JsonNode answerBox = root.path("answer_box");
            if (!answerBox.isMissingNode()) {
                ctx.append(answerBox.path("answer").asText("")).append("\n");
                ctx.append(answerBox.path("snippet").asText("")).append("\n");
            }

            for (JsonNode r : organic) {
                ctx.append(r.path("title").asText("")).append("\n");
                ctx.append(r.path("snippet").asText("")).append("\n\n");
            }

            return extractPrices(ctx.toString(), "SerpAPI", severity);

        } catch (Exception e) {
            log.warn("[SERP] Call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── Price extraction — severity-bounded ──────────────────────────────────

    private Optional<PriceRange> extractPrices(String text, String source, String severity) {
        if (text == null || text.isBlank()) return Optional.empty();

        // Per-severity bounds — what makes sense for this damage level in Tunisia
        long minAllowed = switch (severity) {
            case "MINOR"      ->    30L;
            case "MODERATE"   ->   150L;
            case "SEVERE"     ->   400L;
            case "TOTAL_LOSS" ->  5000L;
            default           ->    30L;
        };
        long maxAllowed = switch (severity) {
            case "MINOR"      ->    800L;
            case "MODERATE"   ->   5000L;
            case "SEVERE"     ->  30000L;
            case "TOTAL_LOSS" -> 500000L;
            default           -> 500000L;
        };

        // Match price ranges: X-Y, X–Y, X à Y
        Pattern pattern = Pattern.compile(
                "(\\d{2,7}(?:[\\s,]\\d{3})*)\\s*[-–—à]\\s*(\\d{2,7}(?:[\\s,]\\d{3})*)");
        Matcher matcher = pattern.matcher(text);

        List<long[]> candidates = new ArrayList<>();
        while (matcher.find()) {
            try {
                long min = Long.parseLong(matcher.group(1).replaceAll("[\\s,]", ""));
                long max = Long.parseLong(matcher.group(2).replaceAll("[\\s,]", ""));

                // Skip year numbers
                if (min >= 1990 && min <= 2030) continue;
                if (max >= 1990 && max <= 2030) continue;

                // Skip if ratio is absurd
                if (max <= min) continue;
                if ((double) max / min > 20.0) continue;

                // Only accept if within severity bounds
                if (min >= minAllowed && max <= maxAllowed) {
                    candidates.add(new long[]{min, max});
                }
            } catch (NumberFormatException ignored) {}
        }

        if (candidates.isEmpty()) return Optional.empty();

        // Pick the median range to avoid extreme outliers
        candidates.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] chosen = candidates.get(candidates.size() / 2);

        BigDecimal min = BigDecimal.valueOf(chosen[0]);
        BigDecimal max = BigDecimal.valueOf(chosen[1]);
        BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        log.info("[PRICING] Extracted {}-{} TND from {} [sev={}]", min, max, source, severity);
        return Optional.of(new PriceRange(min, max, mid, "pièces + main d'œuvre", source));
    }



    // ── Translation helper ────────────────────────────────────────────────────

    private String translateElement(String element) {
        String el = element.toLowerCase().trim();
        return switch (el) {
            case "front bumper"      -> "pare-choc avant";
            case "rear bumper"       -> "pare-choc arrière";
            case "bumper"            -> "pare-choc";
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
            case "ceiling"           -> "plafond";
            case "plumbing"          -> "plomberie";
            default                  -> element;
        };
    }

    // ── Public record ─────────────────────────────────────────────────────────

    public record PriceRange(
            BigDecimal min,
            BigDecimal max,
            BigDecimal midpoint,
            String     includes,
            String     source) {}
}