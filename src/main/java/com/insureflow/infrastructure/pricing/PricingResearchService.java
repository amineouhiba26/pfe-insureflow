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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production-grade pricing service.
 *
 * Strategy:
 * 1. SerpAPI — severity-aware multilingual queries, currency detection + TND conversion
 * 2. Returns Optional.empty() if nothing valid found — caller decides fallback (LLM or nothing)
 *
 * NO hardcoded baselines. NO 0 TND. NO silent failures.
 */
@Service
public class PricingResearchService {

    private static final Logger log = LoggerFactory.getLogger(PricingResearchService.class);
    private static final String SERP_API_URL = "https://serpapi.com/search.json";

    private static final Map<String, Double> EXCHANGE_RATES = Map.of(
            "EUR", 3.38, "USD", 3.12, "MAD", 0.31,
            "GBP", 3.95, "DZD", 0.023, "SAR", 0.83,
            "AED", 0.85, "TND", 1.0
    );

    @Value("${serpapi.api-key:}")
    private String serpApiKey;

    @Value("${pricing.bounds.minor.min:15}")          private long minorMin;
    @Value("${pricing.bounds.minor.max:3000}")         private long minorMax;
    @Value("${pricing.bounds.moderate.min:80}")        private long moderateMin;
    @Value("${pricing.bounds.moderate.max:15000}")     private long moderateMax;
    @Value("${pricing.bounds.severe.min:200}")         private long severeMin;
    @Value("${pricing.bounds.severe.max:100000}")      private long severeMax;
    @Value("${pricing.bounds.total-loss.min:10000}")   private long totalLossMin;
    @Value("${pricing.bounds.total-loss.max:9999999}") private long totalLossMax;

    private final HttpClient   httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PricingResearchService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Optional<PriceRange> searchRepairCost(String element, String severity,
                                                 String vehicleInfo, String claimType) {
        if (serpApiKey == null || serpApiKey.isBlank()) {
            log.warn("[PRICING] No SerpAPI key");
            return Optional.empty();
        }

        List<String> queries = buildQueries(element, severity, vehicleInfo, claimType);
        List<PriceRange> hits = new ArrayList<>();

        for (String query : queries) {
            log.info("[PRICING] Query: '{}'", query);
            try {
                Optional<PriceRange> r = callSerpApi(query, severity, claimType);
                if (r.isPresent()) {
                    hits.add(r.get());
                    log.info("[PRICING] Hit: {}-{} TND for '{}'", r.get().min(), r.get().max(), query);
                    if (hits.size() >= 2) break;
                }
            } catch (Exception e) {
                log.warn("[PRICING] Query failed '{}': {}", query, e.getMessage());
            }
        }

        if (hits.isEmpty()) return Optional.empty();

        // Merge hits into a single coherent range
        BigDecimal minVal = hits.stream().map(PriceRange::min).min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal maxVal = hits.stream().map(PriceRange::max).max(Comparator.naturalOrder()).orElseThrow();

        // If merged range is too wide (outlier), use first hit only
        if (hits.size() > 1 && maxVal.divide(minVal.max(BigDecimal.ONE), 2, RoundingMode.HALF_UP)
                .compareTo(BigDecimal.valueOf(8)) > 0) {
            minVal = hits.get(0).min();
            maxVal = hits.get(0).max();
        }

        BigDecimal mid = minVal.add(maxVal).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        String sources = hits.stream().map(PriceRange::source).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("SerpAPI");

        return Optional.of(new PriceRange(minVal, maxVal, mid, "pièces + main d'œuvre", sources, "TND"));
    }

    // ── Query builder — severity-specific ────────────────────────────────────

    private List<String> buildQueries(String element, String severity,
                                      String vehicleInfo, String claimType) {
        List<String> q = new ArrayList<>();
        String el = translateElement(element);
        String v  = vehicleInfo != null ? vehicleInfo : "voiture";

        if ("VEHICLE_DAMAGE".equals(claimType)) {
            switch (severity) {
                case "MINOR" -> {
                    q.add(String.format("prix retouche rayure %s Tunisie TND", el));
                    q.add(String.format("scratch repair %s %s price", el, v));
                    q.add(String.format("prix polish peinture %s Tunisie garage", el));
                    q.add(String.format("car paint touch up %s Tunisia price", el));
                    q.add(String.format("réparation éraflure %s Tunisie dinars", el));
                }
                case "MODERATE" -> {
                    q.add(String.format("prix débosselage %s %s Tunisie TND", el, v));
                    q.add(String.format("réparation carrosserie %s Tunisie coût dinars", el));
                    q.add(String.format("dent repair %s %s Tunisia price", el, v));
                    q.add(String.format("prix peinture remplacement %s Tunisie carrossier", el));
                    q.add(String.format("car body repair %s %s cost", el, v));
                }
                case "SEVERE" -> {
                    q.add(String.format("prix remplacement %s %s Tunisie TND", el, v));
                    q.add(String.format("%s %s replacement price Tunisia", el, v));
                    q.add(String.format("prix pièce détachée %s %s Tunisie", el, v));
                    q.add(String.format("%s %s spare part price", v, el));
                    q.add(String.format("coût remplacement %s voiture Tunisie garage", el));
                }
                case "TOTAL_LOSS" -> {
                    q.add(String.format("prix %s occasion Tunisie TND 2024 2025", v));
                    q.add(String.format("valeur vénale %s Tunisie assurance", v));
                    q.add(String.format("%s used car price Tunisia TND", v));
                    q.add(String.format("indemnisation perte totale %s Tunisie", v));
                    q.add(String.format("%s market value price", v));
                }
            }
        } else if ("PROPERTY_DAMAGE".equals(claimType)) {
            if ("TOTAL_LOSS".equals(severity)) {
                q.add("coût reconstruction bâtiment incendie Tunisie TND 2025");
                q.add("prix reconstruction salle après incendie Tunisie dinars");
                q.add("building fire reconstruction cost Tunisia TND");
                q.add("coût réhabilitation bâtiment sinistre total Tunisie BTP");
                q.add("fire damage total loss building reconstruction price");
            } else if ("SEVERE".equals(severity)) {
                q.add(String.format("coût reconstruction %s Tunisie TND", el));
                q.add(String.format("prix remise en état %s après sinistre Tunisie", el));
                q.add(String.format("%s major damage repair cost Tunisia TND", el));
                q.add(String.format("coût réhabilitation %s Tunisie BTP", el));
                q.add(String.format("%s fire flood damage restoration price", el));
            } else {
                q.add(String.format("prix réparation %s Tunisie TND 2025", el));
                q.add(String.format("coût travaux %s Tunisie dinars", el));
                q.add(String.format("%s repair cost Tunisia TND", el));
                q.add(String.format("prix rénovation %s Tunisie artisan", el));
                q.add(String.format("%s damage repair price", el));
            }
        } else {
            q.add(String.format("coût %s sinistre Tunisie TND", el));
            q.add(String.format("%s repair replacement cost price Tunisia", el));
        }

        return q;
    }

    // ── SerpAPI call ──────────────────────────────────────────────────────────

    private Optional<PriceRange> callSerpApi(String query, String severity, String claimType) throws Exception {
        String url = SERP_API_URL
                + "?q="       + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&api_key=" + serpApiKey
                + "&num=8&hl=fr&gl=tn";

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET()
                        .timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) return Optional.empty();

        JsonNode root    = mapper.readTree(response.body());
        JsonNode organic = root.path("organic_results");
        if (!organic.isArray() || organic.isEmpty()) return Optional.empty();

        StringBuilder ctx = new StringBuilder();

        // Answer box is gold — most structured
        JsonNode ab = root.path("answer_box");
        if (!ab.isMissingNode()) {
            ctx.append(ab.path("answer").asText("")).append(" ");
            ctx.append(ab.path("snippet").asText("")).append("\n");
        }

        for (JsonNode r : organic) {
            String link    = r.path("link").asText("").toLowerCase();
            String title   = r.path("title").asText("");
            String snippet = r.path("snippet").asText("");
            if (scoreSource(link) < 0) continue;
            ctx.append(title).append(" ").append(snippet).append("\n");
        }

        return ctx.length() == 0 ? Optional.empty() : extractPrices(ctx.toString(), "SerpAPI", severity, claimType);
    }

    private int scoreSource(String url) {
        if (url.contains("pieces-auto") || url.contains("oscaro") || url.contains("midas") ||
                url.contains("tayara") || url.contains("jumia") || url.contains("assurance") ||
                url.contains("garage") || url.contains("carrosserie") || url.contains("norauto"))
            return 3;
        if (url.contains("auto") || url.contains("voiture") || url.contains("vehicule") ||
                url.contains("repair") || url.contains("piece") || url.contains("prix"))
            return 2;
        if (url.contains("forum") || url.contains("community") || url.contains("answers"))
            return 1;
        if (url.contains("facebook") || url.contains("instagram")) return 0;
        if (url.contains("pinterest") || url.contains("youtube") || url.contains("tiktok"))
            return -1;
        return 1;
    }

    // ── Price extraction with currency detection ──────────────────────────────

    private Optional<PriceRange> extractPrices(String text, String source, String severity, String claimType) {
        if (text == null || text.isBlank()) return Optional.empty();

        String currency = detectCurrency(text);
        double rate     = EXCHANGE_RATES.getOrDefault(currency, 1.0);

        long minBound = switch (severity) {
            case "MINOR"      -> minorMin;
            case "MODERATE"   -> moderateMin;
            case "SEVERE"     -> severeMin;
            case "TOTAL_LOSS" -> totalLossMin;
            default           -> minorMin;
        };
        long maxBound = switch (severity) {
            case "MINOR"      -> minorMax;
            case "MODERATE"   -> moderateMax;
            case "SEVERE"     -> severeMax;
            case "TOTAL_LOSS" -> totalLossMax;
            default           -> totalLossMax;
        };

        // Extract ranges: X-Y, X–Y, X à Y
        Pattern rangePat = Pattern.compile(
                "(\\d{1,8}(?:[\\s.,]\\d{3})*)\\s*[-–—à]\\s*(\\d{1,8}(?:[\\s.,]\\d{3})*)");
        Matcher rm = rangePat.matcher(text);

        // Extract single prices near currency symbols
        Pattern singlePat = Pattern.compile(
                "(\\d{1,8}(?:[\\s.,]\\d{3})*)\\s*(?:TND|DT|دينار|€|EUR|\\$|USD|MAD|£|GBP|DZD)");
        Matcher sm = singlePat.matcher(text);

        List<long[]> ranges  = new ArrayList<>();
        List<Long>   singles = new ArrayList<>();

        while (rm.find()) {
            try {
                long rawMin = parseNum(rm.group(1));
                long rawMax = parseNum(rm.group(2));
                long min = Math.round(rawMin * rate);
                long max = Math.round(rawMax * rate);
                if (rawMin >= 1990 && rawMin <= 2030) continue;
                if (rawMax >= 1990 && rawMax <= 2030) continue;
                if (max <= min || (double) max / Math.max(min, 1) > 15.0) continue;
                if (min >= minBound && max <= maxBound) ranges.add(new long[]{min, max});
            } catch (NumberFormatException ignored) {}
        }

        while (sm.find()) {
            try {
                long raw = parseNum(sm.group(1));
                long tnd = Math.round(raw * rate);
                if (raw >= 1990 && raw <= 2030) continue;
                if (tnd >= minBound && tnd <= maxBound) singles.add(tnd);
            } catch (NumberFormatException ignored) {}
        }

        if (singles.size() >= 2) {
            Collections.sort(singles);
            long sMin = singles.get(0);
            long sMax = singles.get(singles.size() - 1);
            if (sMax > sMin && (double) sMax / Math.max(sMin, 1) <= 10.0)
                ranges.add(new long[]{sMin, sMax});
        }

        if (ranges.isEmpty()) return Optional.empty();

        // Remove outliers if multiple ranges
        if (ranges.size() > 2) {
            ranges.sort(Comparator.comparingLong(r -> (r[0] + r[1]) / 2));
            long medMid = (ranges.get(ranges.size() / 2)[0] + ranges.get(ranges.size() / 2)[1]) / 2;
            ranges.removeIf(r -> {
                long mid = (r[0] + r[1]) / 2;
                return mid < medMid / 4 || mid > medMid * 4;
            });
        }

        if (ranges.isEmpty()) return Optional.empty();

        long fMin = ranges.stream().mapToLong(r -> r[0]).min().orElse(0);
        long fMax = ranges.stream().mapToLong(r -> r[1]).max().orElse(0);

        BigDecimal min = BigDecimal.valueOf(fMin);
        BigDecimal max = BigDecimal.valueOf(fMax);
        BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        String srcLabel = "TND".equals(currency) ? source
                : String.format("%s (converti %s→TND @%.2f)", source, currency, rate);

        log.info("[PRICING] {}-{} TND [currency={} source={}]", min, max, currency, source);
        return Optional.of(new PriceRange(min, max, mid, "pièces + main d'œuvre", srcLabel, "TND"));
    }

    private String detectCurrency(String text) {
        if (text.contains("€") || text.toUpperCase().contains("EUR")) return "EUR";
        if (text.contains("$") || text.toUpperCase().contains("USD")) return "USD";
        if (text.toUpperCase().contains("MAD") || text.contains("dirham marocain")) return "MAD";
        if (text.contains("£") || text.toUpperCase().contains("GBP")) return "GBP";
        if (text.toUpperCase().contains("DZD") || text.contains("dinar algérien")) return "DZD";
        if (text.toUpperCase().contains("SAR") || text.contains("riyal")) return "SAR";
        if (text.toUpperCase().contains("AED")) return "AED";
        return "TND";
    }

    private long parseNum(String s) {
        return Long.parseLong(s.replaceAll("[\\s,.]", ""));
    }

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
            case "roof"              -> "toit";
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

    public record PriceRange(
            BigDecimal min, BigDecimal max, BigDecimal midpoint,
            String includes, String source, String currency) {}
}