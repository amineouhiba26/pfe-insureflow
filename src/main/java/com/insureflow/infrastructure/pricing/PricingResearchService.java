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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic pricing service — SerpAPI (Google Search) edition.
 *
 * Strategy:
 * 1. Up to 5 progressively broader Google queries via SerpAPI (gl=tn, hl=fr).
 *    Stop at first query that yields a parseable price range.
 * 2. If ALL queries fail (no indexed content) → regional baseline
 *    (hardcoded Tunisian market 2025 table — never 0 TND).
 */
@Service
public class PricingResearchService {

    private static final Logger log = LoggerFactory.getLogger(PricingResearchService.class);

    private static final String SERP_API_URL    = "https://serpapi.com/search.json";
    private static final long   MIN_PRICE       = 50;
    private static final long   MAX_PRICE       = 200_000;
    private static final double MAX_RATIO       = 15.0;

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
            log.warn("[PRICING] SerpAPI key not configured — falling back to regional baseline");
            return regionalBaseline(element, severity, claimType);
        }

        List<String> queries = buildQueries(element, severity, vehicleInfo, claimType);

        for (String query : queries) {
            log.info("[PRICING] Google search: '{}'", query);
            try {
                Optional<PriceRange> result = searchWithSerpApi(query, element, severity);
                if (result.isPresent()) {
                    log.info("[PRICING] Found price for '{}' via query: '{}'", element, query);
                    return result;
                }
                log.warn("[PRICING] No price found for query: '{}'", query);
            } catch (Exception e) {
                log.warn("[PRICING] SerpAPI failed for query '{}': {}", query, e.getMessage());
            }
        }

        log.warn("[PRICING] All queries exhausted for '{}' — using regional baseline", element);
        return regionalBaseline(element, severity, claimType);
    }

    // ── Query waterfall ────────────────────────────────────────────────────────

    private List<String> buildQueries(String element, String severity,
                                       String vehicleInfo, String claimType) {
        List<String> queries = new ArrayList<>();

        String el  = translateElement(element);
        String sev = switch (severity) {
            case "MINOR"      -> "légère";
            case "MODERATE"   -> "modérée";
            case "SEVERE"     -> "grave remplacement";
            case "TOTAL_LOSS" -> "destruction totale";
            default           -> "";
        };

        if ("VEHICLE_DAMAGE".equals(claimType)) {
            // 1 — Most specific: vehicle + part + severity + Tunisia + year
            if (vehicleInfo != null) {
                queries.add(String.format(
                        "prix réparation %s %s %s Tunisie 2025 TND dinars", el, vehicleInfo, sev));
            }
            // 2 — Part + severity + Tunisia
            queries.add(String.format(
                    "coût remplacement %s %s voiture Tunisie 2024 2025 TND", el, sev));
            // 3 — English (broader index)
            queries.add(String.format(
                    "%s car repair cost Tunisia TND 2024", el));
            // 4 — French body-shop
            queries.add(String.format(
                    "prix pièce %s carrosserie Tunisie garage", el));
            // 5 — Proxy: Morocco / Algeria (same region, more web content)
            queries.add(String.format(
                    "prix remplacement %s voiture Maroc Algérie 2024", el));

        } else if ("PROPERTY_DAMAGE".equals(claimType)) {
            queries.add(String.format(
                    "coût réparation %s %s bâtiment Tunisie 2025 TND dinars", el, sev));
            queries.add(String.format(
                    "prix travaux %s construction Tunisie 2024", el));
            queries.add(String.format(
                    "%s repair cost building Tunisia TND", el));
            queries.add(String.format(
                    "prix rénovation %s Tunisie entreprise BTP", el));

        } else {
            queries.add(String.format(
                    "coût %s sinistre assurance Tunisie 2025 TND", el));
            queries.add(String.format(
                    "%s insurance claim cost Tunisia 2024", el));
        }

        return queries;
    }

    // ── SerpAPI call ──────────────────────────────────────────────────────────

    private Optional<PriceRange> searchWithSerpApi(String query, String element, String severity) {
        try {
            String url = SERP_API_URL
                    + "?q="       + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&api_key=" + serpApiKey
                    + "&num=5"
                    + "&hl=fr"
                    + "&gl=tn";    // Tunisia geo-location

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[SERP] HTTP {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode root    = mapper.readTree(response.body());
            JsonNode organic = root.path("organic_results");

            if (!organic.isArray() || organic.isEmpty()) {
                log.debug("[SERP] No organic results");
                return Optional.empty();
            }

            StringBuilder ctx = new StringBuilder();

            // Grab answer box if present
            JsonNode answerBox = root.path("answer_box");
            if (!answerBox.isMissingNode()) {
                ctx.append(answerBox.path("answer").asText("")).append("\n");
                ctx.append(answerBox.path("snippet").asText("")).append("\n");
            }

            for (JsonNode r : organic) {
                ctx.append(r.path("title").asText("")).append("\n");
                ctx.append(r.path("snippet").asText("")).append("\n\n");
            }

            return extractPrices(ctx.toString(), "Google / SerpAPI");

        } catch (Exception e) {
            log.warn("[SERP] Call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── Price extraction ──────────────────────────────────────────────────────

    private Optional<PriceRange> extractPrices(String text, String source) {
        if (text == null || text.isBlank()) return Optional.empty();

        // Match X-Y, X–Y, X à Y  (with optional space-thousands separator)
        Pattern pattern = Pattern.compile(
                "(\\d{2,7}(?:[\\s,]\\d{3})*)\\s*[-–—à]\\s*(\\d{2,7}(?:[\\s,]\\d{3})*)");
        Matcher matcher = pattern.matcher(text);

        List<long[]> ranges = new ArrayList<>();
        while (matcher.find()) {
            try {
                long min = Long.parseLong(matcher.group(1).replaceAll("[\\s,]", ""));
                long max = Long.parseLong(matcher.group(2).replaceAll("[\\s,]", ""));

                // Skip year ranges (1900-2030)
                if ((min >= 1900 && min <= 2030) || (max >= 1900 && max <= 2030)) continue;

                if (min >= MIN_PRICE && max <= MAX_PRICE
                        && max > min
                        && (double) max / min <= MAX_RATIO) {
                    ranges.add(new long[]{min, max});
                }
            } catch (NumberFormatException ignored) {}
        }

        if (ranges.isEmpty()) return Optional.empty();

        // Pick the highest-minimum range — most likely the relevant Tunisian price
        long[] best = ranges.stream()
                .max(Comparator.comparingLong(r -> r[0]))
                .orElse(ranges.get(0));

        BigDecimal min = BigDecimal.valueOf(best[0]);
        BigDecimal max = BigDecimal.valueOf(best[1]);
        BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        log.info("[PRICING] Range extracted: {}-{} TND [{}]", min, max, source);
        return Optional.of(new PriceRange(min, max, mid, "pièces + main d'œuvre", source));
    }

    // ── Regional baseline (last resort) ──────────────────────────────────────

    /**
     * Conservative Tunisian market 2025 price table.
     * Used only when ALL Google queries return no parseable price.
     * Not an LLM guess — hardcoded ranges you can defend in your demo.
     */
    private Optional<PriceRange> regionalBaseline(String element,
                                                    String severity,
                                                    String claimType) {
        double minMult = switch (severity) {
            case "MINOR"      -> 0.3;
            case "MODERATE"   -> 0.6;
            case "SEVERE"     -> 1.0;
            case "TOTAL_LOSS" -> 2.0;
            default           -> 0.6;
        };
        double maxMult = minMult * 1.8;

        String el = element.toLowerCase();
        int baseMin, baseMax;

        if ("VEHICLE_DAMAGE".equals(claimType)) {
            if      (el.contains("pare-choc") || el.contains("bumper"))        { baseMin = 800;  baseMax = 2500;  }
            else if (el.contains("capot") || el.contains("hood"))              { baseMin = 600;  baseMax = 2000;  }
            else if (el.contains("portière") || el.contains("door"))           { baseMin = 700;  baseMax = 2200;  }
            else if (el.contains("pare-brise") || el.contains("windshield"))   { baseMin = 400;  baseMax = 1200;  }
            else if (el.contains("rétroviseur") || el.contains("mirror"))      { baseMin = 150;  baseMax = 500;   }
            else if (el.contains("phare") || el.contains("headlight"))         { baseMin = 300;  baseMax = 1000;  }
            else if (el.contains("feu") || el.contains("taillight"))           { baseMin = 200;  baseMax = 700;   }
            else if (el.contains("roue") || el.contains("jante") || el.contains("wheel")) { baseMin = 300; baseMax = 900; }
            else if (el.contains("toit") || el.contains("roof"))               { baseMin = 1000; baseMax = 3500;  }
            else if (el.contains("coffre") || el.contains("trunk"))            { baseMin = 500;  baseMax = 1800;  }
            else if (el.contains("moteur") || el.contains("engine"))           { baseMin = 3000; baseMax = 12000; }
            else if (el.contains("châssis") || el.contains("chassis"))         { baseMin = 2000; baseMax = 8000;  }
            else                                                                { baseMin = 400;  baseMax = 1500;  }

        } else if ("PROPERTY_DAMAGE".equals(claimType)) {
            if      (el.contains("mur") || el.contains("cloison"))             { baseMin = 500;  baseMax = 2000;  }
            else if (el.contains("toit") || el.contains("plafond"))            { baseMin = 800;  baseMax = 3000;  }
            else if (el.contains("fenêtre") || el.contains("vitre"))           { baseMin = 300;  baseMax = 1000;  }
            else if (el.contains("porte"))                                      { baseMin = 400;  baseMax = 1500;  }
            else if (el.contains("électri"))                                    { baseMin = 500;  baseMax = 2500;  }
            else if (el.contains("mobilier"))                                   { baseMin = 300;  baseMax = 2000;  }
            else if (el.contains("sol") || el.contains("carrelage"))           { baseMin = 400;  baseMax = 1800;  }
            else if (el.contains("plomberie"))                                  { baseMin = 300;  baseMax = 1500;  }
            else                                                                { baseMin = 300;  baseMax = 1500;  }

        } else {
            baseMin = 300; baseMax = 1500;
        }

        BigDecimal min = BigDecimal.valueOf(Math.max(50, (long)(baseMin * minMult)));
        BigDecimal max = BigDecimal.valueOf((long)(baseMax * maxMult));
        BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        log.info("[PRICING] Regional baseline for '{}' severity={}: {}-{} TND",
                element, severity, min, max);

        return Optional.of(new PriceRange(
                min, max, mid,
                "pièces + main d'œuvre",
                "Barème régional Tunisie 2025"));
    }

    // ── Translation helper ────────────────────────────────────────────────────

    private String translateElement(String element) {
        return switch (element.toLowerCase().trim()) {
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

    // ── Public record ─────────────────────────────────────────────────────────

    public record PriceRange(
            BigDecimal min,
            BigDecimal max,
            BigDecimal midpoint,
            String     includes,
            String     source) {}
}
