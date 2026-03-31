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

/**
 * EstimatorAgentService — traite la queue claim.estimated.
 *
 * Stratégie d'analyse :
 * - Si photos disponibles → llama3.2-vision analyse les images réelles
 * - Si pas de photos ou vision échoue → llama3.1:8b raisonne sur la description
 *
 * Pricing : PricingResearchService (Tavily + LLM) — zéro prix hardcodé
 */
@Service
public class EstimatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(EstimatorAgentService.class);

    private final EstimatorAgent         estimatorAgent;
    private final GeminiVisionService    geminiVisionService;
    private final PricingResearchService pricingResearchService;  // ← Tavily + LLM
    private final ClaimRepository        claimRepository;
    private final ImageQualityService    imageQualityService;
    private final RabbitTemplate         rabbitTemplate;
    private final ObjectMapper           mapper = new ObjectMapper();

    public EstimatorAgentService(EstimatorAgent estimatorAgent,
                                 GeminiVisionService geminiVisionService,
                                 PricingResearchService pricingResearchService,
                                 ClaimRepository claimRepository,
                                 ImageQualityService imageQualityService,
                                 RabbitTemplate rabbitTemplate) {
        this.estimatorAgent         = estimatorAgent;
        this.geminiVisionService    = geminiVisionService;
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

        log.info("[ESTIMATOR] Completed claimId={} confidence={}",
                event.getClaimId(), result.getConfidence());

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE, RabbitMQConfig.Q_FRAUD, event);

        log.info("[ESTIMATOR] Published to fraud queue for claimId={}", event.getClaimId());
    }

    public AgentResult runEstimator(ClaimEvent event) {
        try {
            List<String> photoUrls  = event.getPhotoUrls();
            double       imageScore = imageQualityService.evaluate(photoUrls);
            String       claimType  = resolveClaimType(event);
            String       vehicleInfo = extractVehicleInfo(event.getDescription(), claimType);

            log.debug("[ESTIMATOR] claimType={} imageScore={} photos={} vehicle='{}'",
                    claimType, imageScore,
                    photoUrls == null ? 0 : photoUrls.size(), vehicleInfo);

            String raw = null;

            // Strategy 1 — vision model if photos available
            boolean hasPhotos = photoUrls != null && !photoUrls.isEmpty();
            if (hasPhotos) {
                log.info("[ESTIMATOR] Photos détectées — appel llama3.2-vision");
                raw = geminiVisionService.analysePhotos(photoUrls, claimType);
                if (raw != null) {
                    log.info("[ESTIMATOR] Vision réussie claimId={}", event.getClaimId());
                }
            }

            // Strategy 2 — textual fallback
            if (raw == null) {
                log.info("[ESTIMATOR] Fallback analyse textuelle llama3.1:8b");
                String photoUrlsStr = hasPhotos
                        ? String.join("\n", photoUrls)
                        : "Aucune photo fournie";
                raw = estimatorAgent.analyse(claimType, event.getDescription(), photoUrlsStr);
            }

            String json = ResponseParser.extractJson(raw);
            log.debug("[ESTIMATOR] Extracted JSON: {}", json);

            List<DamagedElement> elements = parseDamagedElements(json);
            log.info("[ESTIMATOR] {} damaged elements identified", elements.size());

            // Pricing via Tavily + LLM — zero hardcoded prices
            CostEstimate costs = lookupCosts(elements, claimType, vehicleInfo);

            boolean geminiUsed = hasPhotos && raw != null;
            String  enriched   = buildResultJson(json, costs, imageScore, claimType, geminiUsed);
            double  conf       = ResponseParser.getDouble(json, "confidence", 0.5);

            return AgentResult.success(enriched, conf, "");

        } catch (Exception e) {
            log.error("[ESTIMATOR] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    // ── Pricing — Tavily + LLM, zero DB ──────────────────────────────────────

    private CostEstimate lookupCosts(List<DamagedElement> elements,
                                     String claimType,
                                     String vehicleInfo) {
        BigDecimal   totalMin  = BigDecimal.ZERO;
        BigDecimal   totalMax  = BigDecimal.ZERO;
        List<String> breakdown = new ArrayList<>();

        for (DamagedElement el : elements) {
            String nameFr     = translatePart(el.name());
            String severityFr = translateSeverity(el.severity().name());

            log.info("[ESTIMATOR] Searching price for '{}' severity={}", el.name(), el.severity());

            Optional<PricingResearchService.PriceRange> price =
                    pricingResearchService.searchRepairCost(
                            el.name(),
                            el.severity().name(),
                            vehicleInfo,
                            claimType
                    );

            if (price.isPresent()) {
                var p = price.get();
                totalMin = totalMin.add(p.min());
                totalMax = totalMax.add(p.max());
                breakdown.add(String.format(
                        "%s (%s) : %s–%s TND [%s — %s]",
                        nameFr, severityFr,
                        p.min(), p.max(),
                        p.includes(), p.source()));
                log.info("[ESTIMATOR] Price found: {}-{} TND for '{}'",
                        p.min(), p.max(), el.name());
            } else {
                log.warn("[ESTIMATOR] No price found for '{}' {}", el.name(), el.severity());
                breakdown.add(String.format("%s (%s) : prix non disponible",
                        nameFr, severityFr));
            }
        }

        BigDecimal midpoint = totalMin.add(totalMax)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        return new CostEstimate(totalMin, totalMax, midpoint, breakdown);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveClaimType(ClaimEvent event) {
        for (int i = 0; i < 5; i++) {
            var claim = claimRepository.findById(event.getClaimId());
            if (claim.isPresent() && claim.get().getType() != null) {
                return claim.get().getType().name();
            }
            try {
                log.debug("[ESTIMATOR] Waiting for RouterAgent attempt {}/5", i + 1);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return claimRepository.findById(event.getClaimId())
                .map(c -> ResponseParser.getString(c.getRouterResult(), "claimType", "UNKNOWN"))
                .orElse("UNKNOWN");
    }

    private String extractVehicleInfo(String description, String claimType) {
        if (!"VEHICLE_DAMAGE".equals(claimType) || description == null) return null;
        String[] brands = {"Ford", "Toyota", "Peugeot", "Renault", "Volkswagen",
                "Hyundai", "Kia", "Fiat", "Opel", "Citroën",
                "Mercedes", "BMW", "Audi", "Nissan", "Mitsubishi"};
        for (String brand : brands) {
            int idx = description.indexOf(brand);
            if (idx >= 0) {
                String after = description.substring(idx).split("[,\\.\\n]")[0];
                after = after.replaceAll("\\b\\d+[A-Z]+\\d+\\b", "").trim();
                if (after.length() > 3) return after;
            }
        }
        return null;
    }

    private List<DamagedElement> parseDamagedElements(String json) {
        List<DamagedElement> elements = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr  = root.has("damagedElements")
                    ? root.get("damagedElements")
                    : root.get("damagedParts");

            if (arr == null || !arr.isArray()) {
                log.warn("[ESTIMATOR] No damagedElements array in JSON: {}", json);
                return elements;
            }

            for (JsonNode item : arr) {
                String name = item.has("element")
                        ? item.path("element").asText("")
                        : item.path("part").asText("");
                String severityStr = item.path("severity").asText("MINOR");
                Severity severity  = parseSeverity(severityStr);
                if (!name.isBlank()) {
                    elements.add(new DamagedElement(name.toLowerCase().trim(), severity));
                }
            }
        } catch (Exception e) {
            log.warn("[ESTIMATOR] Could not parse elements from JSON: {} — error: {}",
                    json, e.getMessage());
        }
        return elements;
    }

    private String buildResultJson(String llmJson, CostEstimate costs,
                                   double imageQuality, String claimType,
                                   boolean visionUsed) {
        try {
            JsonNode llm           = mapper.readTree(llmJson);
            String overallSeverity = llm.path("overallSeverity").asText("MODERATE");
            String reasoning       = llm.path("reasoning").asText("");
            double confidence      = llm.path("confidence").asDouble(0.5);

            return mapper.writeValueAsString(
                    mapper.createObjectNode()
                            .put("claimType",         claimType)
                            .put("overallSeverity",   overallSeverity)
                            .put("estimatedCostMin",  costs.min().toString())
                            .put("estimatedCostMax",  costs.max().toString())
                            .put("estimatedCost",     costs.midpoint().toString())
                            .put("imageQualityScore", imageQuality)
                            .put("analysisMethod",    visionUsed ? "llama3.2-vision" : "llama3.1-textual")
                            .put("pricingSource",     "Tavily web search + LLM estimation")
                            .put("confidence",        confidence)
                            .put("reasoning",         reasoning)
                            .set("costBreakdown",     mapper.valueToTree(costs.breakdown()))
            );
        } catch (Exception e) {
            log.warn("[ESTIMATOR] buildResultJson failed: {}", e.getMessage());
            return llmJson;
        }
    }

    private BigDecimal parseTotalCost(String json) {
        try {
            String cost = mapper.readTree(json).path("estimatedCost").asText(null);
            if (cost == null || cost.equals("null") || cost.equals("0")) return null;
            return new BigDecimal(cost);
        } catch (Exception e) {
            return null;
        }
    }

    private Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return Severity.MINOR;
        }
    }

    // ── Translations ──────────────────────────────────────────────────────────

    private static final java.util.Map<String, String> PART_TRANSLATIONS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("front bumper",      "Pare-choc avant"),
                    java.util.Map.entry("rear bumper",       "Pare-choc arrière"),
                    java.util.Map.entry("hood",              "Capot"),
                    java.util.Map.entry("trunk",             "Coffre"),
                    java.util.Map.entry("door",              "Portière"),
                    java.util.Map.entry("windshield",        "Pare-brise"),
                    java.util.Map.entry("rear window",       "Lunette arrière"),
                    java.util.Map.entry("side mirror",       "Rétroviseur"),
                    java.util.Map.entry("headlight",         "Phare avant"),
                    java.util.Map.entry("taillight",         "Feu arrière"),
                    java.util.Map.entry("wheel",             "Roue"),
                    java.util.Map.entry("roof",              "Toit"),
                    java.util.Map.entry("engine",            "Moteur"),
                    java.util.Map.entry("chassis",           "Châssis"),
                    java.util.Map.entry("wall",              "Mur"),
                    java.util.Map.entry("floor",             "Sol"),
                    java.util.Map.entry("window",            "Fenêtre"),
                    java.util.Map.entry("kitchen",           "Cuisine"),
                    java.util.Map.entry("bathroom",          "Salle de bain"),
                    java.util.Map.entry("electrical system", "Installation électrique"),
                    java.util.Map.entry("furniture",         "Mobilier"),
                    java.util.Map.entry("appliances",        "Appareils électroménagers"),
                    java.util.Map.entry("facade",            "Façade"),
                    java.util.Map.entry("ceiling",           "Plafond"),
                    java.util.Map.entry("foundation",        "Fondations"),
                    java.util.Map.entry("plumbing",          "Plomberie"),
                    java.util.Map.entry("hospitalization",   "Hospitalisation"),
                    java.util.Map.entry("surgery",           "Chirurgie"),
                    java.util.Map.entry("medication",        "Médicaments"),
                    java.util.Map.entry("rehabilitation",    "Rééducation"),
                    java.util.Map.entry("laptop",            "Ordinateur portable"),
                    java.util.Map.entry("phone",             "Téléphone"),
                    java.util.Map.entry("jewelry",           "Bijoux"),
                    java.util.Map.entry("vehicle",           "Véhicule"),
                    java.util.Map.entry("bicycle",           "Vélo"),
                    java.util.Map.entry("tools",             "Outils")
            );

    private static final java.util.Map<String, String> SEVERITY_TRANSLATIONS =
            java.util.Map.of(
                    "MINOR",      "Mineur",
                    "MODERATE",   "Modéré",
                    "SEVERE",     "Grave",
                    "TOTAL_LOSS", "Perte totale"
            );

    private String translatePart(String name) {
        return PART_TRANSLATIONS.getOrDefault(name.toLowerCase().trim(), name);
    }

    private String translateSeverity(String severity) {
        return SEVERITY_TRANSLATIONS.getOrDefault(severity, severity);
    }

    private record DamagedElement(String name, Severity severity) {}

    private record CostEstimate(BigDecimal min, BigDecimal max,
                                BigDecimal midpoint, List<String> breakdown) {}
}