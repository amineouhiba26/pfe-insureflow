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
 * EstimatorAgentService — processes claim.estimated queue.
 *
 * Full workflow:
 * 1. Receive ClaimEvent from claim.estimated
 * 2. Update claim status → ESTIMATING
 * 3. Retrieve claimType from DB (set by RouterAgent)
 * 4. Evaluate image quality score
 * 5. Call EstimatorAgent with claimType + description + photoUrls
 * 6. Parse damaged elements from LLM response
 * 7. For each element+severity → query repair_costs table for price
 * 8. Sum costs, build enriched result JSON
 * 9. Write estimatorResult + estimatedCost to DB
 * 10. Publish to claim.fraud.checked to trigger FraudAgent
 *
 * Key principle: LLM identifies WHAT is damaged and HOW SEVERELY.
 * Numbers always come from repair_costs table — never from the LLM.
 */
@Service
public class EstimatorAgentService {

    private static final Logger log = LoggerFactory.getLogger(EstimatorAgentService.class);

    private final EstimatorAgent          estimatorAgent;
    private final RepairCostJpaRepository repairCostRepo;
    private final ClaimRepository         claimRepository;
    private final ImageQualityService     imageQualityService;
    private final RabbitTemplate          rabbitTemplate;
    private final ObjectMapper            mapper = new ObjectMapper();

    public EstimatorAgentService(EstimatorAgent estimatorAgent,
                                 RepairCostJpaRepository repairCostRepo,
                                 ClaimRepository claimRepository,
                                 ImageQualityService imageQualityService,
                                 RabbitTemplate rabbitTemplate) {
        this.estimatorAgent      = estimatorAgent;
        this.repairCostRepo      = repairCostRepo;
        this.claimRepository     = claimRepository;
        this.imageQualityService = imageQualityService;
        this.rabbitTemplate      = rabbitTemplate;
    }

    // ── RabbitMQ listener ─────────────────────────────────────────────────────

    @RabbitListener(queues = RabbitMQConfig.Q_ESTIMATED)
    public void onEstimated(ClaimEvent event) {
        log.info("[ESTIMATOR] Processing claimId={}", event.getClaimId());

        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.ESTIMATING);

        AgentResult result = runEstimator(event);

        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setEstimatorResult(result.getResultJson());
            BigDecimal cost = parseTotalCost(result.getResultJson());
            if (cost != null) {
                claim.setEstimatedCost(cost);
            }
            claimRepository.save(claim);
        });

        log.info("[ESTIMATOR] Completed claimId={} confidence={}",
                event.getClaimId(), result.getConfidence());

        // Estimator always triggers Fraud — Fraud needs estimator findings
        // to compare against the client's written description
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.Q_FRAUD,
                event
        );

        log.info("[ESTIMATOR] Published to fraud queue for claimId={}", event.getClaimId());
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    public AgentResult runEstimator(ClaimEvent event) {
        try {
            List<String> photoUrls = event.getPhotoUrls();

            // Step 1: image quality score — feeds into confidence formula later
            double imageQuality = imageQualityService.evaluate(photoUrls);
            log.debug("[ESTIMATOR] Image quality={} claimId={}", imageQuality, event.getClaimId());

            // Step 2: get claimType already set by RouterAgent
            // RouterAgent runs in parallel — by the time Estimator runs
            // (triggered by Orchestrator fan-out + Estimator is slower than Router),
            // the type is usually already set. Fallback to UNKNOWN if not yet.
            String claimType = resolveClaimType(event);

            // Step 3: format photo URLs for the prompt
            String photoUrlsStr = (photoUrls == null || photoUrls.isEmpty())
                    ? "Aucune photo fournie"
                    : String.join("\n", photoUrls);

            // Step 4: call LLM — identifies elements and severity, never prices
            String raw  = estimatorAgent.analyse(claimType, event.getDescription(), photoUrlsStr);
            String json = ResponseParser.extractJson(raw);
            log.debug("[ESTIMATOR] Raw LLM response: {}", raw);

            // Step 5: look up prices from DB for each identified element
            List<DamagedElement> elements = parseDamagedElements(json);
            CostEstimate         costs    = lookupCosts(elements);

            // Step 6: build enriched result merging LLM output + DB costs + image quality
            String enrichedJson  = buildResultJson(json, costs, imageQuality);
            double llmConfidence = ResponseParser.getDouble(json, "confidence", 0.5);

            return AgentResult.success(enrichedJson, llmConfidence, "");

        } catch (Exception e) {
            log.error("[ESTIMATOR] Agent failed claimId={}: {}",
                    event.getClaimId(), e.getMessage());
            return AgentResult.failure(e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reads the claimType already set by RouterAgent from DB.
     * Falls back to "UNKNOWN" if RouterAgent hasn't finished yet.
     */
    private String resolveClaimType(ClaimEvent event) {
        return claimRepository.findById(event.getClaimId())
                .filter(c -> c.getType() != null)
                .map(c -> c.getType().name())
                .orElse("UNKNOWN");
    }

    /**
     * Parses the damagedElements array from the LLM JSON response.
     * Supports both "damagedElements" and "damagedParts" keys for compatibility.
     * Supports both "element" and "part" as the item key.
     */
    private List<DamagedElement> parseDamagedElements(String json) {
        List<DamagedElement> elements = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);

            // Support both field names
            JsonNode arr = root.has("damagedElements")
                    ? root.get("damagedElements")
                    : root.get("damagedParts");

            if (arr == null || !arr.isArray()) return elements;

            for (JsonNode item : arr) {
                // Support both "element" and "part" keys
                String name = item.has("element")
                        ? item.path("element").asText("")
                        : item.path("part").asText("");

                String severityStr = item.path("severity").asText("MINOR");
                Severity severity  = parseSeverity(severityStr);

                if (!name.isBlank()) {
                    elements.add(new DamagedElement(name, severity));
                }
            }
        } catch (Exception e) {
            log.warn("[ESTIMATOR] Could not parse damaged elements: {}", e.getMessage());
        }
        return elements;
    }

    /**
     * Looks up the repair_costs table for each damaged element.
     * Sums min and max costs across all elements.
     *
     * If an element has no match in repair_costs (e.g. "hospitalization"),
     * it logs a warning and skips — the claim still processes normally.
     * The cost will be 0 for unmatched elements.
     */
    private CostEstimate lookupCosts(List<DamagedElement> elements) {
        BigDecimal   totalMin  = BigDecimal.ZERO;
        BigDecimal   totalMax  = BigDecimal.ZERO;
        List<String> breakdown = new ArrayList<>();

        for (DamagedElement el : elements) {
            var found = repairCostRepo
                    .findByPartNameIgnoreCaseAndSeverity(el.name(), el.severity());

            if (found.isPresent()) {
                var rc = found.get();
                // BigDecimal.add() returns a new value — must reassign
                totalMin = totalMin.add(rc.getMinCost());
                totalMax = totalMax.add(rc.getMaxCost());
                breakdown.add(String.format("%s (%s): %s–%s DT",
                        rc.getPartName(), rc.getSeverity(),
                        rc.getMinCost(), rc.getMaxCost()));
                log.debug("[ESTIMATOR] {} {} → {}-{} DT",
                        el.name(), el.severity(), rc.getMinCost(), rc.getMaxCost());
            } else {
                log.warn("[ESTIMATOR] No repair cost entry for element='{}' severity={}",
                        el.name(), el.severity());
                breakdown.add(String.format("%s (%s): coût non référencé",
                        el.name(), el.severity()));
            }
        }

        // Midpoint of the range as the single estimated cost
        BigDecimal midpoint = totalMin.add(totalMax)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        return new CostEstimate(totalMin, totalMax, midpoint, breakdown);
    }

    /**
     * Builds the final estimatorResult JSON stored in the claim row.
     * Merges: LLM identification output + DB cost lookup + image quality score.
     */
    private String buildResultJson(String llmJson, CostEstimate costs, double imageQuality) {
        try {
            JsonNode llm = mapper.readTree(llmJson);

            String claimType       = llm.path("claimType").asText("UNKNOWN");
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
                            .put("confidence",        confidence)
                            .put("reasoning",         reasoning)
                            .set("costBreakdown",     mapper.valueToTree(costs.breakdown()))
            );

        } catch (Exception e) {
            log.warn("[ESTIMATOR] Could not build enriched JSON, returning raw: {}", e.getMessage());
            return llmJson;
        }
    }

    /**
     * Extracts the estimatedCost field from the enriched result JSON.
     * Used to set claim.estimatedCost in the DB for DecisionMatrix rule 5.
     */
    private BigDecimal parseTotalCost(String resultJson) {
        try {
            JsonNode node = mapper.readTree(resultJson);
            String   cost = node.path("estimatedCost").asText(null);
            if (cost == null || cost.equals("null")) return null;
            return new BigDecimal(cost);
        } catch (Exception e) {
            log.warn("[ESTIMATOR] Could not parse estimatedCost: {}", e.getMessage());
            return null;
        }
    }

    private Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("[ESTIMATOR] Unknown severity '{}', defaulting to MINOR", value);
            return Severity.MINOR;
        }
    }

    // ── Inner records ─────────────────────────────────────────────────────────

    private record DamagedElement(String name, Severity severity) {}

    private record CostEstimate(
            BigDecimal   min,
            BigDecimal   max,
            BigDecimal   midpoint,
            List<String> breakdown) {}
}