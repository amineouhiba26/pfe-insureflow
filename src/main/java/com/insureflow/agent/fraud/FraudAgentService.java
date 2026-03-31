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
            // Get estimator result from DB
            String estimatorResult = claimRepository.findById(event.getClaimId())
                    .map(c -> c.getEstimatorResult())
                    .orElse("{}");

            String systemEstimatedCost = ResponseParser.getString(
                    estimatorResult, "estimatedCost", "non disponible");

            String clientEstimatedCost = event.getClientEstimatedCost() != null
                    ? event.getClientEstimatedCost().toPlainString()
                    : "non fourni";

            log.debug("[FRAUD] clientCost={} systemCost={}",
                    clientEstimatedCost, systemEstimatedCost);

            String raw  = fraudAgent.detect(
                    event.getDescription(),
                    estimatorResult,
                    clientEstimatedCost,
                    systemEstimatedCost
            );

            String json = ResponseParser.extractJson(raw);
            double score = ResponseParser.getDouble(json, "anomalyScore", 0.0);

            log.debug("[FRAUD] Raw response: {}", raw);
            return AgentResult.success(json, 1.0 - score, "");

        } catch (Exception e) {
            log.error("[FRAUD] Failed claimId={}: {}", event.getClaimId(), e.getMessage());
            // Fallback — neutral result so claim can still be processed
            String fallback = "{\"anomalyDetected\":false,\"anomalyScore\":0.0," +
                    "\"anomalyType\":\"NONE\",\"reasoning\":\"Agent unavailable\"," +
                    "\"priceAnalysis\":\"N/A\",\"details\":\"Fraud check failed\"}";
            return AgentResult.success(fallback, 1.0, "");
        }
    }
}