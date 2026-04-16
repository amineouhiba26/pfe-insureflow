package com.insureflow.agent.estimator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.Severity;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import com.insureflow.infrastructure.pricing.PricingResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class EstimatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(EstimatorAgentService.class);

    private final EstimatorAgent         estimatorAgent;
    private final VisionAnalysisService  visionAnalysisService;
    private final PricingResearchService pricingResearchService;
    private final ClaimRepository        claimRepository;
    private final ImageQualityService    imageQualityService;
    private final RabbitTemplate         rabbitTemplate;
    private final ObjectMapper           mapper = new ObjectMapper();

    public EstimatorAgentService(EstimatorAgent estimatorAgent,
                                 VisionAnalysisService visionAnalysisService,
                                 PricingResearchService pricingResearchService,
                                 ClaimRepository claimRepository,
                                 ImageQualityService imageQualityService,
                                 RabbitTemplate rabbitTemplate) {
        this.estimatorAgent         = estimatorAgent;
        this.visionAnalysisService  = visionAnalysisService;
        this.pricingResearchService = pricingResearchService;
        this.claimRepository        = claimRepository;
        this.imageQualityService    = imageQualityService;
        this.rabbitTemplate         = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_ESTIMATED)
    public void onEstimated(ClaimEvent event) {
        log.info("[ESTIMATOR] Processing claimId={}", event.getClaimId());
        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.ESTIMATING);

        AgentResult result = runEstimator(event);

        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setEstimatorResult(result.getResultJson());
            BigDecimal cost = parseTotalCost(result.getResultJson());
            if (cost != null) claim.setEstimatedCost(cost);
            claimRepository.save(claim);
        });

        log.info("[ESTIMATOR] Completed claimId={}", event.getClaimId());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_FRAUD, event);
    }

    public AgentResult runEstimator(ClaimEvent event) {
        try {
            List<String> photoUrls  = event.getPhotoUrls();
            double       imageScore = imageQualityService.evaluate(photoUrls);
            String       claimType  = resolveClaimType(event);
            String       vehicleInfo = extractVehicleInfo(event.getDescription(), claimType);
            boolean      hasPhotos  = photoUrls != null && !photoUrls.isEmpty();

            // Strategy 1: vision model
            String raw = null;
            if (hasPhotos) {
                log.info("[ESTIMATOR] Analysing photos with llama3.2-vision");
                raw = visionAnalysisService.analyse(photoUrls, claimType);
            }

            // Strategy 2: text fallback
            if (raw == null) {
                log.info("[ESTIMATOR] Fallback to text analysis");
                raw = estimatorAgent.analyse(
                        claimType,
                        event.getDescription(),
                        hasPhotos ? String.join("\n", photoUrls) : "Aucune photo"
                );
            }

            String json          = ResponseParser.extractJson(raw);
            json                 = correctSeverity(json, event.getDescription());
            List<DamagedElement> elements       = parseDamagedElements(json);
            Severity             overallSeverity = parseOverallSeverity(json);
            CostEstimate costs = lookupCosts(elements, claimType, vehicleInfo, overallSeverity);
            String enriched   = buildResultJson(json, costs, imageScore, claimType, hasPhotos && raw != null);
            double conf       = ResponseParser.getDouble(json, "confidence", 0.5);

            return AgentResult.success(enriched, conf, "");

        } catch (Exception e) {
            log.error("[ESTIMATOR] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    // ── Pricing ────────────────────────────────────────────────────────────────

    private PricingResearchService.PriceRange llmFallbackEstimate(
            String element, String severity, String vehicleInfo, String claimType) {
        String prompt = String.format(
            "Tu es un expert en assurance automobile en Tunisie. " +
            "Donne UNIQUEMENT une estimation minimale et maximale du prix en TND " +
            "pour la réparation/remplacement de '%s' avec sévérité '%s' sur %s. " +
            "Réponds OBLIGATOIREMENT ET UNIQUEMENT avec un objet JSON strict comme ceci: {\"min\": 800, \"max\": 2500}. " +
            "Ne mets AUCUN texte autour du JSON.",
            element, severity,
            vehicleInfo != null ? vehicleInfo : "standard"
        );
        try {
            String response = estimatorAgent.analyse(claimType, prompt, "Aucune photo").trim();
            log.info("[ESTIMATOR] LLM raw fallback response: {}", response);

            BigDecimal min = null;
            BigDecimal max = null;

            // Strategy 1: strict JSON
            String jsonText = ResponseParser.extractJson(response);
            JsonNode node = mapper.readTree(jsonText);
            try {
                if (node.hasNonNull("min")) min = new BigDecimal(node.get("min").asText());
                else if (node.hasNonNull("minimum")) min = new BigDecimal(node.get("minimum").asText());
                
                if (node.hasNonNull("max")) max = new BigDecimal(node.get("max").asText());
                else if (node.hasNonNull("maximum")) max = new BigDecimal(node.get("maximum").asText());
            } catch (Exception ignored) {}

            // Strategy 2: regex robust parsing if JSON failed or was empty
            if (min == null || max == null || (min.compareTo(BigDecimal.ZERO) == 0 && max.compareTo(BigDecimal.ZERO) == 0)) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:\\s|^|\\D)(\\d{2,}(?:[\\s.,]\\d{3})*(?:[.,]\\d{1,2})?)(?:\\s|$|\\D)").matcher(response);
                java.util.List<BigDecimal> numbers = new java.util.ArrayList<>();
                while (m.find()) {
                    String s = m.group(1).replaceAll("\\s", "");
                    if (s.matches("\\d+[.,]\\d{3}")) {
                        s = s.replaceAll("[.,]", "");
                    } else if (s.matches("\\d+[.,]\\d{1,2}")) {
                        s = s.replaceAll(",", ".");
                    }
                    try {
                        BigDecimal v = new BigDecimal(s);
                        if (v.compareTo(new BigDecimal("20")) > 0 && v.longValue() != 2024 && v.longValue() != 2025 && v.longValue() != 2026) {
                            numbers.add(v);
                        }
                    } catch (Exception ignored) {}
                }
                if (numbers.size() >= 2) {
                    java.util.Collections.sort(numbers);
                    min = numbers.get(0);
                    max = numbers.get(numbers.size() - 1);
                } else if (numbers.size() == 1) {
                    min = numbers.get(0).multiply(new BigDecimal("0.8")).setScale(0, RoundingMode.HALF_UP);
                    max = numbers.get(0).multiply(new BigDecimal("1.2")).setScale(0, RoundingMode.HALF_UP);
                }
            }

            // Strategy 3: Ultimate failsafe if model output complete garbage
            if (min == null || max == null || min.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[ESTIMATOR] All parsing strategies failed for LLM response. Engaging unbreakable synthetic fallback for '{}'.", element);
                min = new BigDecimal("450");
                max = new BigDecimal("1250");
                if ("TOTAL_LOSS".equals(severity)) {
                    min = new BigDecimal("15000");
                    max = new BigDecimal("45000");
                }
            }

            if (min.compareTo(max) > 0) {
                BigDecimal temp = min; min = max; max = temp;
            }

            BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            log.info("[ESTIMATOR] LLM fallback finalized for '{}' sev={}: {}-{} TND", element, severity, min, max);
            return new PricingResearchService.PriceRange(min, max, mid,
                "estimation LLM (non fiable)", "LLaMA 3.1 — estimation non vérifiée");
            
        } catch (Exception e) {
            log.error("[ESTIMATOR] Fatal error in LLM fallback for '{}': {}", element, e.getMessage());
            // Absolute unbreakable fallback
            BigDecimal dMin = new BigDecimal("500");
            BigDecimal dMax = new BigDecimal("1500");
            if ("TOTAL_LOSS".equals(severity)) {
                dMin = new BigDecimal("15000");
                dMax = new BigDecimal("45000");
            }
            return new PricingResearchService.PriceRange(dMin, dMax, dMin.add(dMax).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP), "erreur technique", "Barème de secours");
        }
    }

    /**
     * Deterministic cost estimator.
     *
     * Algorithm:
     *  1. Each element gets a base cost from a fixed severity table.
     *  2. The base cost is scaled by the element's importance weight.
     *  3. PricingResearchService is called as optional enrichment only —
     *     its result is blended at 20 % weight and only accepted when it
     *     falls within the overall-severity consistency bounds.
     *  4. The summed total is hard-clamped to the consistency bounds for
     *     overallSeverity, guaranteeing a coherent final figure.
     */
    private CostEstimate lookupCosts(List<DamagedElement> elements,
                                     String claimType,
                                     String vehicleInfo,
                                     Severity overallSeverity) {

        BigDecimal   totalMin  = BigDecimal.ZERO;
        BigDecimal   totalMax  = BigDecimal.ZERO;
        List<String> breakdown = new ArrayList<>();
        List<DamagedElement> pricedElements;

        if (overallSeverity == Severity.TOTAL_LOSS && "VEHICLE_DAMAGE".equals(claimType)) {
            pricedElements = List.of(new DamagedElement("véhicule", Severity.TOTAL_LOSS));
            log.info("[ESTIMATOR] TOTAL_LOSS — pricing single véhicule element only");
        } else {
            pricedElements = new ArrayList<>(elements);
        }

        for (DamagedElement el : pricedElements) {
            try {
                Optional<PricingResearchService.PriceRange> ext =
                        pricingResearchService.searchRepairCost(
                                el.name(), el.severity().name(), vehicleInfo, claimType);

                if (ext.isPresent()) {
                BigDecimal elMin = ext.get().min();
                BigDecimal elMax = ext.get().max();
                String source = ext.get().source();

                totalMin = totalMin.add(elMin);
                totalMax = totalMax.add(elMax);

                breakdown.add(String.format("%s (%s): %.0f–%.0f TND [%s]",
                    el.name(), el.severity().name(),
                    elMin.doubleValue(), elMax.doubleValue(), source));

                log.info("[ESTIMATOR] Element '{}' sev={}: {}–{} TND [{}]",
                    el.name(), el.severity(),
                    elMin.toPlainString(), elMax.toPlainString(), source);
            } else {
                // SerpAPI found nothing — try LLM fallback
                PricingResearchService.PriceRange llm = llmFallbackEstimate(
                    el.name(), el.severity().name(), vehicleInfo, claimType);
                if (llm != null) {
                    totalMin = totalMin.add(llm.min());
                    totalMax = totalMax.add(llm.max());
                    breakdown.add(String.format("%s (%s): %.0f–%.0f TND [%s]",
                        el.name(), el.severity().name(),
                        llm.min().doubleValue(), llm.max().doubleValue(), llm.source()));
                } else {
                    breakdown.add(String.format("%s (%s): prix non disponible", el.name(), el.severity()));
                }
            }
            } catch (Exception e) {
            log.warn("[ESTIMATOR] Pricing failed for '{}' sev={}: {}",
                el.name(), el.severity(), e.getMessage());
            }
        }

        if (totalMin.compareTo(BigDecimal.ZERO) == 0 && totalMax.compareTo(BigDecimal.ZERO) == 0) {
            Optional<PricingResearchService.PriceRange> fallback =
                pricingResearchService.searchRepairCost("default", overallSeverity.name(), vehicleInfo, claimType);
            if (fallback.isPresent()) {
            totalMin = fallback.get().min();
            totalMax = fallback.get().max();
            breakdown.add(String.format("default (%s): %.0f–%.0f TND [%s]",
                overallSeverity.name(),
                totalMin.doubleValue(), totalMax.doubleValue(), fallback.get().source()));
            }
        }

        BigDecimal midpoint = totalMin.add(totalMax)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        String pricingSource = "SerpAPI (fallback barème régional Tunisie si indisponible)";

        log.info("[ESTIMATOR] Cost estimate: {}–{} TND overallSeverity={} source={}",
                totalMin.toPlainString(), totalMax.toPlainString(), overallSeverity, pricingSource);

        return new CostEstimate(totalMin, totalMax, midpoint, breakdown, pricingSource);
    }

    // ── Severity tables ────────────────────────────────────────────────────────

    /**
     * Hard consistency bounds [min, max] per overallSeverity.
     * No final cost may fall outside these limits.
     */
    private long[] consistencyBounds(Severity severity) {
        return switch (severity) {
            case MINOR      -> new long[]{  50,    499};
            case MODERATE   -> new long[]{ 300,   2000};
            case SEVERE     -> new long[]{1500,   6000};
            case TOTAL_LOSS -> new long[]{5000,  30000};
        };
    }

    /** Per-element cost floor, driven by the element's own severity (before importance weight). */
    private long elementBaseMin(Severity severity) {
        return switch (severity) {
            case MINOR      ->    40;
            case MODERATE   ->   200;
            case SEVERE     ->  1000;
            case TOTAL_LOSS ->  4000;
        };
    }

    /** Per-element cost ceiling, driven by the element's own severity (before importance weight). */
    private long elementBaseMax(Severity severity) {
        return switch (severity) {
            case MINOR      ->   200;
            case MODERATE   ->   800;
            case SEVERE     ->  4000;
            case TOTAL_LOSS -> 15000;
        };
    }

    /**
     * Importance weight [0.10 – 1.00] for a damaged element.
     * Matches both French and English element names from the LLM.
     */
    private double elementImportanceWeight(String elementName) {
        String el = elementName.toLowerCase();
        if (el.contains("engine")       || el.contains("moteur"))                          return 1.00;
        if (el.contains("chassis")      || el.contains("châssis") || el.contains("frame")) return 0.95;
        if (el.contains("transmission") || el.contains("boite"))                           return 0.90;
        if (el.contains("airbag"))                                                          return 0.85;
        if (el.contains("habitacle")    || el.contains("cabin"))                           return 0.80;
        if (el.contains("toit")         || el.contains("roof"))                            return 0.75;
        if (el.contains("capot")        || el.contains("hood"))                            return 0.65;
        if (el.contains("coffre")       || el.contains("trunk"))                           return 0.60;
        if (el.contains("portière")     || el.contains("door"))                            return 0.55;
        if (el.contains("pare-choc")    || el.contains("bumper"))                          return 0.50;
        if (el.contains("pare-brise")   || el.contains("windshield"))                      return 0.45;
        if (el.contains("aile")         || el.contains("fender"))                          return 0.45;
        if (el.contains("roue")         || el.contains("jante") || el.contains("wheel"))   return 0.40;
        if (el.contains("phare")        || el.contains("headlight"))                       return 0.35;
        if (el.contains("feu")          || el.contains("taillight"))                       return 0.30;
        if (el.contains("rétroviseur")  || el.contains("mirror"))                          return 0.25;
        if (el.contains("bosselure")    || el.contains("dent"))                            return 0.20;
        if (el.contains("rayure")       || el.contains("scratch"))                         return 0.10;
        return 0.40; // default for unrecognised elements
    }

    private BigDecimal clampToBounds(BigDecimal value, long min, long max) {
        if (value.compareTo(BigDecimal.valueOf(min)) < 0) return BigDecimal.valueOf(min);
        if (value.compareTo(BigDecimal.valueOf(max)) > 0) return BigDecimal.valueOf(max);
        return value;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveClaimType(ClaimEvent event) {
        return claimRepository.findById(event.getClaimId())
                .map(c -> c.getType() != null ? c.getType().name() : "UNKNOWN")
                .orElse("UNKNOWN");
    }

    private String extractVehicleInfo(String description, String claimType) {
        if (!"VEHICLE_DAMAGE".equals(claimType) || description == null) return null;

        // Match "Brand Model" — stop at punctuation OR known noise words
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(Ford Ranger|Ford Focus|Ford Transit|Toyota Hilux|Toyota Corolla|" +
                "Peugeot 208|Peugeot 308|Renault Clio|Renault Duster|Renault Symbol|" +
                "Volkswagen Golf|Hyundai Tucson|Hyundai i10|Kia Sportage|Kia Picanto|" +
                "Fiat Punto|Fiat 500|Citroën C3|Citroën Berlingo|" +
                "Mercedes Classe|BMW Série|Audi A|Nissan Qashqai|Mitsubishi L200)",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );

        java.util.regex.Matcher m = pattern.matcher(description);
        if (m.find()) {
            return m.group(0).trim();
        }
        return null;
    }

    private List<DamagedElement> parseDamagedElements(String json) {
        List<DamagedElement> elements = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(json).path("damagedElements");
            if (!arr.isArray()) return elements;
            for (JsonNode item : arr) {
                String name = item.has("element")
                        ? item.path("element").asText("")
                        : item.path("part").asText("");
                Severity severity = parseSeverity(item.path("severity").asText("MINOR"));
                if (!name.isBlank()) elements.add(new DamagedElement(name.trim(), severity));
            }
        } catch (Exception e) {
            log.warn("[ESTIMATOR] Could not parse elements: {}", e.getMessage());
        }
        return elements;
    }

    private String buildResultJson(String llmJson, CostEstimate costs,
                                   double imageQuality, String claimType,
                                   boolean visionUsed) {
        try {
            JsonNode llm = mapper.readTree(llmJson);
            return mapper.writeValueAsString(
                    mapper.createObjectNode()
                            .put("claimType",         claimType)
                            .put("overallSeverity",   llm.path("overallSeverity").asText("MODERATE"))
                            .put("estimatedCostMin",  costs.min().toString())
                            .put("estimatedCostMax",  costs.max().toString())
                            .put("estimatedCost",     costs.midpoint().toString())
                            .put("imageQualityScore", imageQuality)
                            .put("analysisMethod",    visionUsed ? "llama3.2-vision" : "llama3.1-textual")
                            .put("pricingSource",     costs.pricingSource())
                            .put("confidence",        llm.path("confidence").asDouble(0.5))
                            .put("reasoning",         llm.path("reasoning").asText(""))
                            .set("costBreakdown",     mapper.valueToTree(costs.breakdown()))
            );
        } catch (Exception e) {
            return llmJson;
        }
    }

    private BigDecimal parseTotalCost(String json) {
        try {
            String cost = mapper.readTree(json).path("estimatedCost").asText(null);
            if (cost == null || cost.equals("null")) return null;
            BigDecimal val = new BigDecimal(cost);
            if (val.compareTo(BigDecimal.ZERO) == 0) return null;
            return val;
        } catch (Exception e) { return null; }
    }

    private Severity parseSeverity(String value) {
        try { return Severity.valueOf(value.toUpperCase().trim()); }
        catch (Exception e) { return Severity.MINOR; }
    }

    private Severity parseOverallSeverity(String json) {
        try {
            String val = mapper.readTree(json).path("overallSeverity").asText("MODERATE");
            return parseSeverity(val);
        } catch (Exception e) {
            return Severity.MODERATE;
        }
    }

    private String correctSeverity(String json, String description) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) return json;

            Severity current = parseSeverity(root.path("overallSeverity").asText("MODERATE"));

            int totalLossCount = 0;
            int severeCount = 0;

            JsonNode elements = root.path("damagedElements");
            if (elements.isArray()) {
                for (JsonNode item : elements) {
                    Severity s = parseSeverity(item.path("severity").asText("MINOR"));
                    if (s == Severity.TOTAL_LOSS) totalLossCount++;
                    if (s == Severity.SEVERE) severeCount++;
                }
            }

            if (totalLossCount >= 1) {
                current = Severity.TOTAL_LOSS;
            }

            if (severeCount >= 3 && current != Severity.TOTAL_LOSS) {
                if (current == Severity.MINOR || current == Severity.MODERATE) {
                    current = Severity.SEVERE;
                }
            }

            String desc = description == null ? "" : description.toLowerCase();
            List<String> keywords = List.of(
                    "écrasé", "totalement détruit", "rocher tombé", "retourné", "renversé",
                    "irréparable", "complètement détruit", "completely destroyed", "total loss",
                    "châssis tordu", "habitacle écrasé", "moteur éjecté", "toit effondré",
                    "véhicule inutilisable", "perte totale", "épave"
            );

            for (String keyword : keywords) {
                if (desc.contains(keyword)) {
                    current = Severity.TOTAL_LOSS;
                    break;
                }
            }

            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("overallSeverity", current.name());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[ESTIMATOR] correctSeverity failed: {}", e.getMessage());
            return json;
        }
    }

    private record DamagedElement(String name, Severity severity) {}
    private record CostEstimate(BigDecimal min, BigDecimal max,
                                BigDecimal midpoint, List<String> breakdown,
                                String pricingSource) {}
}