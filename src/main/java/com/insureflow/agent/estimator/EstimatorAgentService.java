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
import com.insureflow.infrastructure.persistence.repository.RepairCostJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * EstimatorAgentService — traite la queue claim.estimated.
 *
 * Stratégie d'analyse :
 * - Si photos disponibles → llama3.2-vision analyse les images réelles (VisionAnalysisService)
 * - Si pas de photos ou vision échoue → llama3.1:8b raisonne sur la description textuelle
 *
 * Dans tous les cas : le LLM identifie les dommages, la DB fournit les prix.
 */
@Service
public class EstimatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(EstimatorAgentService.class);

    private final EstimatorAgent          estimatorAgent;
    private final GeminiVisionService     geminiVisionService;
    private final RepairCostJpaRepository repairCostRepo;
    private final ClaimRepository         claimRepository;
    private final ImageQualityService     imageQualityService;
    private final RabbitTemplate          rabbitTemplate;
    private final ObjectMapper            mapper = new ObjectMapper();

    public EstimatorAgentService(EstimatorAgent estimatorAgent,
                                 GeminiVisionService geminiVisionService,                                 RepairCostJpaRepository repairCostRepo,
                                 ClaimRepository claimRepository,
                                 ImageQualityService imageQualityService,
                                 RabbitTemplate rabbitTemplate) {
        this.estimatorAgent       = estimatorAgent;
        this.geminiVisionService = geminiVisionService;  // ← ici
        this.repairCostRepo       = repairCostRepo;
        this.claimRepository      = claimRepository;
        this.imageQualityService  = imageQualityService;
        this.rabbitTemplate       = rabbitTemplate;
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

            log.debug("[ESTIMATOR] claimType={} imageScore={} photos={}",
                    claimType, imageScore, photoUrls == null ? 0 : photoUrls.size());

            String raw = null;

            // Stratégie 1 — Gemini Vision si photos disponibles
            boolean hasPhotos = photoUrls != null && !photoUrls.isEmpty();
            if (hasPhotos) {
                log.info("[ESTIMATOR] Photos détectées — appel Gemini 1.5 Flash");
                raw = geminiVisionService.analysePhotos(photoUrls, claimType);
                if (raw != null) {
                    log.info("[ESTIMATOR] Gemini Vision réussie pour claimId={}", event.getClaimId());
                }
            }

// Stratégie 2 — llama3.1:8b sur description textuelle (fallback si pas de photos ou Gemini échoue)
            if (raw == null) {
                log.info("[ESTIMATOR] Fallback sur analyse textuelle llama3.1:8b");
                String photoUrlsStr = hasPhotos
                        ? String.join("\n", photoUrls)
                        : "Aucune photo fournie";
                raw = estimatorAgent.analyse(claimType, event.getDescription(), photoUrlsStr);
            }

            String json = ResponseParser.extractJson(raw);
            log.debug("[ESTIMATOR] Raw response: {}", raw);

            List<DamagedElement> elements = parseDamagedElements(json);
            CostEstimate         costs    = lookupCosts(elements);
            String               enriched = buildResultJson(json, costs, imageScore, claimType);
            double               conf     = ResponseParser.getDouble(json, "confidence", 0.5);

            return AgentResult.success(enriched, conf, "");

        } catch (Exception e) {
            log.error("[ESTIMATOR] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveClaimType(ClaimEvent event) {
        // Retry up to 5 times with 1s wait — Router may not have written yet
        for (int i = 0; i < 5; i++) {
            var claim = claimRepository.findById(event.getClaimId());
            if (claim.isPresent() && claim.get().getType() != null) {
                return claim.get().getType().name();
            }
            try {
                log.debug("[ESTIMATOR] Waiting for RouterAgent to set claimType, attempt {}/5", i + 1);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Final fallback — read from routerResult JSON directly
        return claimRepository.findById(event.getClaimId())
                .map(c -> ResponseParser.getString(c.getRouterResult(), "claimType", "UNKNOWN"))
                .orElse("UNKNOWN");
    }

    private List<DamagedElement> parseDamagedElements(String json) {
        List<DamagedElement> elements = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr  = root.has("damagedElements")
                    ? root.get("damagedElements")
                    : root.get("damagedParts");

            if (arr == null || !arr.isArray()) return elements;

            for (JsonNode item : arr) {
                String name = item.has("element")
                        ? item.path("element").asText("")
                        : item.path("part").asText("");
                String severityStr = item.path("severity").asText("MINOR");
                Severity severity  = parseSeverity(severityStr);
                if (!name.isBlank()) elements.add(new DamagedElement(name, severity));
            }
        } catch (Exception e) {
            log.warn("[ESTIMATOR] Could not parse elements: {}", e.getMessage());
        }
        return elements;
    }

    private CostEstimate lookupCosts(List<DamagedElement> elements) {
        BigDecimal   totalMin  = BigDecimal.ZERO;
        BigDecimal   totalMax  = BigDecimal.ZERO;
        List<String> breakdown = new ArrayList<>();

        for (DamagedElement el : elements) {
            String nameFr     = translatePart(el.name());
            String severityFr = translateSeverity(el.severity().name());

            var found = repairCostRepo
                    .findByPartNameIgnoreCaseAndSeverity(el.name(), el.severity());

            if (found.isPresent()) {
                var rc = found.get();
                totalMin = totalMin.add(rc.getMinCost());
                totalMax = totalMax.add(rc.getMaxCost());
                breakdown.add(String.format("%s (%s) : %s–%s DT",
                        nameFr, severityFr, rc.getMinCost(), rc.getMaxCost()));
            } else {
                log.warn("[ESTIMATOR] Aucun prix pour '{}' {}", el.name(), el.severity());
                breakdown.add(String.format("%s (%s) : coût non référencé", nameFr, severityFr));
            }
        }

        BigDecimal midpoint = totalMin.add(totalMax)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        return new CostEstimate(totalMin, totalMax, midpoint, breakdown);
    }

    private String buildResultJson(String llmJson, CostEstimate costs,
                                   double imageQuality, String claimType) {
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
                            .put("analysisMethod", "gemini-vision")
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
            if (cost == null || cost.equals("null")) return null;
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

    // ── Traductions ───────────────────────────────────────────────────────────

    private static final java.util.Map<String, String> PART_TRANSLATIONS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("front bumper",       "Pare-choc avant"),
                    java.util.Map.entry("rear bumper",        "Pare-choc arrière"),
                    java.util.Map.entry("hood",               "Capot"),
                    java.util.Map.entry("trunk",              "Coffre"),
                    java.util.Map.entry("door",               "Portière"),
                    java.util.Map.entry("windshield",         "Pare-brise"),
                    java.util.Map.entry("rear window",        "Lunette arrière"),
                    java.util.Map.entry("side mirror",        "Rétroviseur"),
                    java.util.Map.entry("headlight",          "Phare avant"),
                    java.util.Map.entry("taillight",          "Feu arrière"),
                    java.util.Map.entry("wheel",              "Roue"),
                    java.util.Map.entry("roof",               "Toit"),
                    java.util.Map.entry("engine",             "Moteur"),
                    java.util.Map.entry("chassis",            "Châssis"),
                    java.util.Map.entry("wall",               "Mur"),
                    java.util.Map.entry("floor",              "Sol"),
                    java.util.Map.entry("window",             "Fenêtre"),
                    java.util.Map.entry("kitchen",            "Cuisine"),
                    java.util.Map.entry("bathroom",           "Salle de bain"),
                    java.util.Map.entry("electrical system",  "Installation électrique"),
                    java.util.Map.entry("furniture",          "Mobilier"),
                    java.util.Map.entry("appliances",         "Appareils électroménagers"),
                    java.util.Map.entry("facade",             "Façade"),
                    java.util.Map.entry("ceiling",            "Plafond"),
                    java.util.Map.entry("foundation",         "Fondations"),
                    java.util.Map.entry("plumbing",           "Plomberie"),
                    java.util.Map.entry("hospitalization",    "Hospitalisation"),
                    java.util.Map.entry("surgery",            "Chirurgie"),
                    java.util.Map.entry("medication",         "Médicaments"),
                    java.util.Map.entry("rehabilitation",     "Rééducation"),
                    java.util.Map.entry("laptop",             "Ordinateur portable"),
                    java.util.Map.entry("phone",              "Téléphone"),
                    java.util.Map.entry("jewelry",            "Bijoux"),
                    java.util.Map.entry("vehicle",            "Véhicule"),
                    java.util.Map.entry("bicycle",            "Vélo"),
                    java.util.Map.entry("tools",              "Outils")
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

    // ── Inner records ─────────────────────────────────────────────────────────

    private record DamagedElement(String name, Severity severity) {}

    private record CostEstimate(BigDecimal min, BigDecimal max,
                                BigDecimal midpoint, List<String> breakdown) {}
}