package com.insureflow.agent.fraud;

import com.insureflow.agent.shared.AgentResult;
import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.infrastructure.messaging.ClaimEvent;
import com.insureflow.infrastructure.messaging.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * FraudAgentService — listens on claim.fraud.checked.
 *
 * Receives ClaimEvent after EstimatorAgent finishes.
 * Calls FraudAgent with description + estimator findings + price comparison.
 * Writes fraudResult to DB.
 * Publishes result back to Orchestrator via claim.fraud.result queue.
 */
@Service
public class FraudAgentService {

    private static final Logger log = LoggerFactory.getLogger(FraudAgentService.class);

    private final FraudAgent      fraudAgent;
    private final ClaimRepository claimRepository;
    private final RabbitTemplate  rabbitTemplate;

    public FraudAgentService(FraudAgent fraudAgent,
                             ClaimRepository claimRepository,
                             RabbitTemplate rabbitTemplate) {
        this.fraudAgent      = fraudAgent;
        this.claimRepository = claimRepository;
        this.rabbitTemplate  = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.Q_FRAUD)
    public void onFraudCheck(ClaimEvent event) {
        log.info("[FRAUD] Processing claimId={}", event.getClaimId());

        claimRepository.updateStatus(event.getClaimId(), ClaimStatus.FRAUD_CHECK);

        AgentResult result = runFraudCheck(event);

        claimRepository.findById(event.getClaimId()).ifPresent(claim -> {
            claim.setFraudResult(result.getResultJson());
            claimRepository.save(claim);
        });

        log.info("[FRAUD] Completed claimId={} anomalyScore={} anomalyType={}",
                event.getClaimId(),
                ResponseParser.getDouble(result.getResultJson(), "anomalyScore", 0.0),
                ResponseParser.getString(result.getResultJson(), "anomalyType", "NONE"));

        // Publish to orchestrator for final decision
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.Q_DECISION,
                event);

        log.info("[FRAUD] Published to decision queue for claimId={}", event.getClaimId());
    }

    public AgentResult runFraudCheck(ClaimEvent event) {
        try {
            var claim = claimRepository.findById(event.getClaimId()).orElse(null);
            if (claim == null) {
                log.error("[FRAUD] Claim not found: {}", event.getClaimId());
                return AgentResult.failure("Claim not found");
            }

            String estimatorResult = claim.getEstimatorResult() != null
                    ? claim.getEstimatorResult() : "{}";

            String systemCostStr = ResponseParser.getString(
                    estimatorResult, "estimatedCost", "0");

            java.math.BigDecimal clientCost = claim.getClientEstimatedCost() != null
                    ? claim.getClientEstimatedCost()
                    : event.getClientEstimatedCost();

            String clientCostStr = clientCost != null
                    ? clientCost.toPlainString() : "non fourni";

            // Pre-compute direction so the LLM cannot make a directional mistake
            String priceDirection = computePriceDirection(clientCost, systemCostStr);

            log.info("[FRAUD] claimId={} client={} system={} direction={}",
                    event.getClaimId(), clientCostStr, systemCostStr, priceDirection);

            String raw  = fraudAgent.detect(
                    event.getDescription(),
                    estimatorResult,
                    clientCostStr,
                    systemCostStr,
                    priceDirection
            );

            String json  = ResponseParser.extractJson(raw);
            double score = ResponseParser.getDouble(json, "anomalyScore", 0.0);
            String type  = ResponseParser.getString(json, "anomalyType", "NONE");

            // Hard safety override — if client < system it CANNOT be PRICE_INFLATION
            if ("PRICE_INFLATION".equals(type) && priceDirection.startsWith("CLIENT_INFERIEUR")) {
                log.warn("[FRAUD] LLM incorrectly flagged PRICE_INFLATION — overriding to NONE");
                json  = json.replace("\"PRICE_INFLATION\"", "\"NONE\"")
                            .replace("\"anomalyDetected\":true", "\"anomalyDetected\":false");
                score = 0.05;
            }

            log.info("[FRAUD] anomalyScore={} anomalyType={} claimId={}",
                    score, type, event.getClaimId());

            return AgentResult.success(json, 1.0 - score, "");

        } catch (Exception e) {
            log.error("[FRAUD] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            String fallback = "{\"anomalyDetected\":false,\"anomalyScore\":0.0," +
                    "\"anomalyType\":\"NONE\",\"reasoning\":\"Agent indisponible\"," +
                    "\"priceAnalysis\":\"N/A\",\"details\":\"Vérification fraude échouée\"}";
            return AgentResult.success(fallback, 1.0, "");
        }
    }

    /**
     * Pre-computes price direction so the LLM cannot misinterpret it.
     * This is passed explicitly to the FraudAgent prompt.
     */
    private String computePriceDirection(java.math.BigDecimal clientCost, String systemCostStr) {
        if (clientCost == null) return "Prix client non fourni — analyse prix non applicable.";

        try {
            java.math.BigDecimal systemCost = new java.math.BigDecimal(systemCostStr);
            if (systemCost.compareTo(java.math.BigDecimal.ZERO) == 0)
                return "Prix système non disponible — analyse prix non applicable.";

            java.math.BigDecimal diff    = clientCost.subtract(systemCost);
            java.math.BigDecimal pct     = diff.abs()
                    .divide(systemCost, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal.valueOf(100));
            int pctInt = pct.intValue();

            if (clientCost.compareTo(systemCost) < 0) {
                // Client asked for LESS — cannot be price inflation
                return String.format(
                        "CLIENT_INFERIEUR: Le client (%s TND) demande MOINS que le système (%s TND). " +
                        "Écart: %d%% en faveur du système. " +
                        "Ce n'est PAS de la fraude — ne pas flaguer PRICE_INFLATION.",
                        clientCost.toPlainString(), systemCostStr, pctInt);
            } else if (clientCost.compareTo(systemCost) == 0) {
                return String.format(
                        "CLIENT_EGAL: Le client (%s TND) = système (%s TND). Aucune anomalie de prix.",
                        clientCost.toPlainString(), systemCostStr);
            } else {
                // Client asked for MORE — possible inflation
                String risk;
                if (pctInt < 20)       risk = "normal, variations de marché → score 0.0-0.1";
                else if (pctInt < 50)  risk = "suspicion modérée → score 0.2-0.4";
                else if (pctInt < 100) risk = "forte suspicion → score 0.5-0.7";
                else                   risk = "fraude très probable → score 0.7-0.95";

                return String.format(
                        "CLIENT_SUPERIEUR: Le client (%s TND) demande PLUS que le système (%s TND). " +
                        "Écart: +%d%%. Risque: %s. " +
                        "Considérer PRICE_INFLATION si l'écart est injustifié.",
                        clientCost.toPlainString(), systemCostStr, pctInt, risk);
            }
        } catch (Exception e) {
            return "Calcul de direction impossible — ignorer l'analyse des prix.";
        }
    }
}