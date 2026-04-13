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

            String json     = ResponseParser.extractJson(raw);
            List<DamagedElement> elements = parseDamagedElements(json);
            CostEstimate costs = lookupCosts(elements, claimType, vehicleInfo);
            String enriched   = buildResultJson(json, costs, imageScore, claimType, hasPhotos && raw != null);
            double conf       = ResponseParser.getDouble(json, "confidence", 0.5);

            return AgentResult.success(enriched, conf, "");

        } catch (Exception e) {
            log.error("[ESTIMATOR] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    // ── Pricing ────────────────────────────────────────────────────────────────

    private CostEstimate lookupCosts(List<DamagedElement> elements,
                                     String claimType,
                                     String vehicleInfo) {
        BigDecimal   totalMin  = BigDecimal.ZERO;
        BigDecimal   totalMax  = BigDecimal.ZERO;
        List<String> breakdown = new ArrayList<>();
        String       pricingSource = "Tavily web search";

        for (DamagedElement el : elements) {
            log.info("[ESTIMATOR] Pricing '{}' severity={}", el.name(), el.severity());

            Optional<PricingResearchService.PriceRange> price =
                    pricingResearchService.searchRepairCost(
                            el.name(), el.severity().name(), vehicleInfo, claimType);

            if (price.isPresent()) {
                var p = price.get();
                totalMin = totalMin.add(p.min());
                totalMax = totalMax.add(p.max());
                breakdown.add(String.format("%s (%s) : %s–%s TND [%s — %s]",
                        el.name(), el.severity().name(),
                        p.min(), p.max(), p.includes(), p.source()));
                // Track source — prefer real Tavily over baseline
                if (p.source() != null && !p.source().contains("Barème")) {
                    pricingSource = "Tavily web search";
                } else if (p.source() != null) {
                    pricingSource = p.source();
                }
            } else {
                breakdown.add(String.format("%s (%s) : prix non disponible",
                        el.name(), el.severity().name()));
            }
        }

        BigDecimal midpoint = totalMin.add(totalMax)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return new CostEstimate(totalMin, totalMax, midpoint, breakdown, pricingSource);
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

    private record DamagedElement(String name, Severity severity) {}
    private record CostEstimate(BigDecimal min, BigDecimal max,
                                BigDecimal midpoint, List<String> breakdown,
                                String pricingSource) {}
}