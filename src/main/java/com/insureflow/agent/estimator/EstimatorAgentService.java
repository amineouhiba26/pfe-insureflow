package com.insureflow.agent.estimator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.Severity;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import com.insureflow.infrastructure.pricing.PricingResearchService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EstimatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(EstimatorAgentService.class);

    private final EstimatorAgent         estimatorAgent;
    private final VisionAnalysisService  visionAnalysisService;
    private final PricingResearchService pricingResearchService;
    private final ClaimRepository        claimRepository;
    private final ImageQualityService    imageQualityService;
    private final RabbitTemplate         rabbitTemplate;
    private final ChatLanguageModel      chatModel;
    private final ObjectMapper           mapper = new ObjectMapper();

    public EstimatorAgentService(EstimatorAgent estimatorAgent,
                                 VisionAnalysisService visionAnalysisService,
                                 PricingResearchService pricingResearchService,
                                 ClaimRepository claimRepository,
                                 ImageQualityService imageQualityService,
                                 RabbitTemplate rabbitTemplate,
                                 ChatLanguageModel chatModel) {
        this.estimatorAgent         = estimatorAgent;
        this.visionAnalysisService  = visionAnalysisService;
        this.pricingResearchService = pricingResearchService;
        this.claimRepository        = claimRepository;
        this.imageQualityService    = imageQualityService;
        this.rabbitTemplate         = rabbitTemplate;
        this.chatModel              = chatModel;
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

            // Step 1: Vision analysis (if photos available)
            String raw = null;
            if (hasPhotos) {
                log.info("[ESTIMATOR] Vision analysis with llama3.2-vision");
                raw = visionAnalysisService.analyse(photoUrls, claimType);
            }

            // Step 2: Text fallback if no photos or vision failed
            if (raw == null) {
                log.info("[ESTIMATOR] Text analysis fallback");
                raw = estimatorAgent.analyse(
                        claimType,
                        event.getDescription(),
                        hasPhotos ? String.join("\n", photoUrls) : "Aucune photo"
                );
            }

            String   json           = ResponseParser.extractJson(raw);
            json                    = correctSeverity(json, event.getDescription(), claimType);
            List<DamagedElement> elements       = parseDamagedElements(json);
            Severity             overallSeverity = parseOverallSeverity(json);

            // Step 3: Price each element via SerpAPI
            CostEstimate costs = lookupCosts(elements, claimType, vehicleInfo,
                    overallSeverity, event.getDescription());

            String enriched = buildResultJson(json, costs, imageScore, claimType,
                    hasPhotos && raw != null);
            double conf     = ResponseParser.getDouble(json, "confidence", 0.5);

            return AgentResult.success(enriched, conf, "");

        } catch (Exception e) {
            log.error("[ESTIMATOR] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    // ── Pricing — SerpAPI first, LLM fallback only on total failure ───────────

    private CostEstimate lookupCosts(List<DamagedElement> elements,
                                     String claimType,
                                     String vehicleInfo,
                                     Severity overallSeverity,
                                     String description) {

        BigDecimal   totalMin  = BigDecimal.ZERO;
        BigDecimal   totalMax  = BigDecimal.ZERO;
        List<String> breakdown = new ArrayList<>();
        int          serpHits  = 0;

        // TOTAL_LOSS: price ONLY the replacement — don't stack individual parts
        List<DamagedElement> toPrice;
        if (overallSeverity == Severity.TOTAL_LOSS && "VEHICLE_DAMAGE".equals(claimType)) {
            String vehicleName = vehicleInfo != null ? vehicleInfo : "véhicule";
            toPrice = List.of(new DamagedElement(vehicleName, Severity.TOTAL_LOSS));
            log.info("[ESTIMATOR] TOTAL_LOSS vehicle → pricing single element: '{}'", vehicleName);
        } else if (overallSeverity == Severity.TOTAL_LOSS && "PROPERTY_DAMAGE".equals(claimType)) {
            toPrice = List.of(new DamagedElement("bâtiment", Severity.TOTAL_LOSS));
            log.info("[ESTIMATOR] TOTAL_LOSS property → single bâtiment element");
            log.info("[ESTIMATOR] toPrice set to single bâtiment element, size=1");
        } else {
            toPrice = new ArrayList<>(elements);
        }

        // Price each element via SerpAPI
        for (DamagedElement el : toPrice) {
            Optional<PricingResearchService.PriceRange> found =
                    pricingResearchService.searchRepairCost(
                            el.name(), el.severity().name(), vehicleInfo, claimType);

            if (found.isPresent()) {
                PricingResearchService.PriceRange p = found.get();
                totalMin = totalMin.add(p.min());
                totalMax = totalMax.add(p.max());
                breakdown.add(String.format("%s (%s): %.0f–%.0f TND [%s]",
                        el.name(), el.severity(), p.min(), p.max(), p.source()));
                serpHits++;
                log.info("[ESTIMATOR] SerpAPI hit — '{}' {}: {}-{} TND",
                        el.name(), el.severity(), p.min(), p.max());
            } else {
                breakdown.add(String.format("%s (%s): recherche infructueuse",
                        el.name(), el.severity()));
                log.warn("[ESTIMATOR] No SerpAPI price for '{}' sev={}", el.name(), el.severity());
            }
        }

        // If SerpAPI found NOTHING at all → LLM fallback for the whole claim
        String pricingMethod;
        String pricingConfidence;

        if (serpHits == 0) {
            log.warn("[ESTIMATOR] Zero SerpAPI hits — triggering LLM fallback for entire claim");
            PricingResearchService.PriceRange llm =
                    llmFallback(overallSeverity, claimType, vehicleInfo, description);

            if (llm != null) {
                totalMin = llm.min();
                totalMax = llm.max();
                breakdown.add(String.format("Estimation LLM (%s): %.0f–%.0f TND [NON FIABLE]",
                        overallSeverity, llm.min(), llm.max()));
                pricingMethod     = "llm_fallback";
                pricingConfidence = "low";
                log.info("[ESTIMATOR] LLM fallback: {}-{} TND", llm.min(), llm.max());
            } else {
                breakdown.add(String.format("Estimation impossible (%s): SerpAPI et LLM indisponibles",
                        overallSeverity));
                pricingMethod     = "unavailable";
                pricingConfidence = "none";
                log.warn("[ESTIMATOR] Pricing unavailable — SerpAPI and LLM both failed [claimType={} severity={}]",
                        claimType, overallSeverity);
            }
        } else if (serpHits < toPrice.size()) {
            pricingMethod     = "mixed";
            pricingConfidence = "medium";
        } else {
            pricingMethod     = "serp";
            pricingConfidence = "high";
        }

        BigDecimal midpoint = totalMin.add(totalMax)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        return new CostEstimate(totalMin, totalMax, midpoint, breakdown,
                pricingMethod, pricingConfidence);
    }

    /**
     * LLM fallback — called ONLY when ALL SerpAPI queries failed.
     * Calls the chat model directly (bypassing EstimatorAgent's no-price system message)
     * with a prompt that demands a single MIN–MAX TND range and nothing else.
     */
    private PricingResearchService.PriceRange llmFallback(Severity severity, String claimType,
                                                          String vehicleInfo, String description) {
        try {
            String vehicle = vehicleInfo != null ? vehicleInfo : "véhicule standard";
            String prompt = String.format(
                    "Tu es un expert en coûts de réparation en Tunisie.\n" +
                    "Sinistre: %s. Véhicule: %s. Sévérité: %s.\nDescription: %s\n\n" +
                    "Fournis UNIQUEMENT le coût total estimé en dinars tunisiens (TND) " +
                    "sous la forme MIN–MAX (exemple: 800–2500). Aucun autre texte.",
                    claimType, vehicle, severity.name(),
                    description != null ? description : "Non fournie");

            Response<AiMessage> resp = chatModel.generate(UserMessage.from(prompt));
            String raw = resp.content().text().trim();
            log.info("[ESTIMATOR] LLM fallback raw response: '{}'", raw);

            Matcher rangeMatcher = Pattern.compile("(\\d{2,7})\\s*[-–]\\s*(\\d{2,7})").matcher(raw);
            List<long[]> found = new ArrayList<>();
            while (rangeMatcher.find()) {
                long min = Long.parseLong(rangeMatcher.group(1));
                long max = Long.parseLong(rangeMatcher.group(2));
                if (min >= 1990 && min <= 2030) continue;
                if (max > min && (double) max / min <= 15.0) {
                    found.add(new long[]{min, max});
                }
            }

            if (!found.isEmpty()) {
                long fMin = found.stream().mapToLong(r -> r[0]).min().orElse(0);
                long fMax = found.stream().mapToLong(r -> r[1]).max().orElse(0);
                BigDecimal min = BigDecimal.valueOf(fMin);
                BigDecimal max = BigDecimal.valueOf(fMax);
                BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                log.info("[ESTIMATOR] LLM fallback parsed range: {}-{} TND", min, max);
                return new PricingResearchService.PriceRange(
                        min, max, mid, "estimation LLM",
                        "LLaMA 3.1 — estimation non vérifiée (non fiable)", "TND");
            }

        } catch (Exception e) {
            log.error("[ESTIMATOR] LLM fallback failed: {}", e.getMessage());
        }
        return null;
    }

    // ── Severity post-processing ──────────────────────────────────────────────

    private String correctSeverity(String json, String description, String claimType) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) return json;

            Severity current = parseSeverity(root.path("overallSeverity").asText("MODERATE"));
            int totalLossCount = 0;
            int severeCount    = 0;
            int totalElements  = 0;

            JsonNode elements = root.path("damagedElements");
            if (elements.isArray()) {
                totalElements = elements.size();
                for (JsonNode item : elements) {
                    Severity s = parseSeverity(item.path("severity").asText("MINOR"));
                    if (s == Severity.TOTAL_LOSS) totalLossCount++;
                    if (s == Severity.SEVERE || s == Severity.TOTAL_LOSS) severeCount++;
                }
            }

            // TOTAL_LOSS requires strong evidence — not just one part flagged TOTAL_LOSS
            // A destroyed bumper alone is SEVERE, not TOTAL_LOSS
            // TOTAL_LOSS = majority of vehicle is gone (chassis, cabin, engine destroyed)
            boolean structuralKeywords = false;
            String desc = description == null ? "" : description.toLowerCase();
            for (String kw : List.of(
                    // Vehicle keywords
                    "châssis tordu", "châssis plié", "habitacle écrasé", "moteur éjecté",
                    "toit effondré", "retourné", "renversé", "complètement détruit",
                    "completely destroyed", "perte totale", "épave", "irréparable",
                    "destruction totale", "véhicule inutilisable",
                    // Property damage keywords
                    "incendie", "feu", "brûlé", "brulé", "fire", "burnt", "burned",
                    "court-circuit", "inondation", "effondrement", "salle détruite",
                    "bâtiment détruit", "dégâts importants", "reconstruction nécessaire",
                    "structure compromise", "murs calcinés", "plafond effondré",
                    "électrique", "explosion", "fumée", "soot", "smoke damage")) {
                if (desc.contains(kw)) { structuralKeywords = true; break; }
            }

            // For PROPERTY_DAMAGE: fire/flood/explosion = immediate SEVERE minimum, often TOTAL_LOSS
            boolean isPropertyFire = "PROPERTY_DAMAGE".equals(claimType) &&
                    (desc.contains("incendie") || desc.contains("feu") ||
                            desc.contains("brûlé") || desc.contains("brulé") ||
                            desc.contains("fire") || desc.contains("inondation") ||
                            desc.contains("explosion") || desc.contains("court-circuit"));

            // Fire/disaster on PROPERTY_DAMAGE → unconditional TOTAL_LOSS, ignores vision model severity
            boolean isFireOrDisaster = "PROPERTY_DAMAGE".equals(claimType) &&
                    (desc.contains("incendie") || desc.contains("feu") ||
                     desc.contains("brûlé") || desc.contains("brulé") ||
                     desc.contains("fire") || desc.contains("court-circuit") ||
                     desc.contains("inondation") || desc.contains("explosion") ||
                     desc.contains("dégâts importants") || desc.contains("catastrophe") ||
                     desc.contains("sinistre important"));

            if (isFireOrDisaster) {
                current = Severity.TOTAL_LOSS;
                log.info("[ESTIMATOR] Fire/disaster detected on PROPERTY_DAMAGE → forced TOTAL_LOSS");
            }

            // Vehicle/other: upgrade to TOTAL_LOSS if strong element evidence
            if (!isFireOrDisaster && totalLossCount >= 3) {
                current = Severity.TOTAL_LOSS;
            } else if (!isFireOrDisaster && structuralKeywords && totalLossCount >= 1) {
                current = Severity.TOTAL_LOSS;
            } else if (!isFireOrDisaster && totalElements > 0
                    && (double) severeCount / totalElements > 0.6
                    && totalLossCount >= 2) {
                current = Severity.TOTAL_LOSS;
            } else if (current == Severity.TOTAL_LOSS && totalLossCount < 2
                    && !structuralKeywords && !isPropertyFire && !isFireOrDisaster) {
                // Downgrade spurious TOTAL_LOSS for vehicles only
                if (severeCount >= 2) {
                    current = Severity.SEVERE;
                    log.info("[ESTIMATOR] Downgraded spurious TOTAL_LOSS → SEVERE " +
                            "(only {} TOTAL_LOSS elements, no structural keywords)", totalLossCount);
                }
            }

            // Upgrade MINOR/MODERATE if many SEVERE elements
            if (severeCount >= 3 && current != Severity.TOTAL_LOSS) {
                if (current == Severity.MINOR || current == Severity.MODERATE)
                    current = Severity.SEVERE;
            }

            // Fire/flood always minimum SEVERE
            if (isPropertyFire && (current == Severity.MINOR || current == Severity.MODERATE)) {
                current = Severity.SEVERE;
                log.info("[ESTIMATOR] Upgraded to SEVERE minimum: property fire claim");
            }

            ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                    .put("overallSeverity", current.name());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[ESTIMATOR] correctSeverity failed: {}", e.getMessage());
            return json;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveClaimType(ClaimEvent event) {
        return claimRepository.findById(event.getClaimId())
                .map(c -> c.getType() != null ? c.getType().name() : "UNKNOWN")
                .orElse("UNKNOWN");
    }

    private String extractVehicleInfo(String description, String claimType) {
        if (!"VEHICLE_DAMAGE".equals(claimType) || description == null) return null;
        Matcher m = Pattern.compile(
                "(Ford Ranger|Ford Focus|Ford Transit|Toyota Hilux|Toyota Corolla|" +
                        "Peugeot 208|Peugeot 308|Renault Clio|Renault Duster|Renault Symbol|" +
                        "Volkswagen Golf|Hyundai Tucson|Hyundai i10|Kia Sportage|Kia Picanto|" +
                        "Fiat Punto|Fiat 500|Citroën C3|Citroën Berlingo|" +
                        "Mercedes Classe|BMW Série|Audi A|Nissan Qashqai|Mitsubishi L200)",
                Pattern.CASE_INSENSITIVE).matcher(description);
        return m.find() ? m.group(0).trim() : null;
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

    private Severity parseSeverity(String value) {
        try { return Severity.valueOf(value.toUpperCase().trim()); }
        catch (Exception e) { return Severity.MINOR; }
    }

    private Severity parseOverallSeverity(String json) {
        try { return parseSeverity(mapper.readTree(json).path("overallSeverity").asText("MODERATE")); }
        catch (Exception e) { return Severity.MODERATE; }
    }

    private BigDecimal parseTotalCost(String json) {
        try {
            String cost = mapper.readTree(json).path("estimatedCost").asText(null);
            if (cost == null || cost.equals("null")) return null;
            BigDecimal val = new BigDecimal(cost);
            return val.compareTo(BigDecimal.ZERO) == 0 ? null : val;
        } catch (Exception e) { return null; }
    }

    private String buildResultJson(String llmJson, CostEstimate costs,
                                   double imageQuality, String claimType,
                                   boolean visionUsed) {
        try {
            JsonNode llm = mapper.readTree(llmJson);
            boolean unavailable = "unavailable".equals(costs.pricingMethod());

            ObjectNode node = mapper.createObjectNode()
                    .put("claimType",         claimType)
                    .put("overallSeverity",   llm.path("overallSeverity").asText("MODERATE"))
                    .put("currency",          "TND")
                    .put("pricingMethod",     costs.pricingMethod())
                    .put("pricingConfidence", costs.pricingConfidence())
                    .put("imageQualityScore", imageQuality)
                    .put("analysisMethod",    visionUsed ? "llama3.2-vision" : "llama3.1-textual")
                    .put("confidence",        llm.path("confidence").asDouble(0.5))
                    .put("reasoning",         llm.path("reasoning").asText(""));

            if (unavailable) {
                node.putNull("estimatedCostMin");
                node.putNull("estimatedCostMax");
                node.putNull("estimatedCost");
            } else {
                node.put("estimatedCostMin", costs.min().toString());
                node.put("estimatedCostMax", costs.max().toString());
                node.put("estimatedCost",    costs.midpoint().toString());
            }
            node.set("costBreakdown", mapper.valueToTree(costs.breakdown()));

            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return llmJson;
        }
    }

    private record DamagedElement(String name, Severity severity) {}

    private record CostEstimate(
            BigDecimal min, BigDecimal max, BigDecimal midpoint,
            List<String> breakdown, String pricingMethod, String pricingConfidence) {}
}