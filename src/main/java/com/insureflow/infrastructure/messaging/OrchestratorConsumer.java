package com.insureflow.infrastructure.messaging;

import com.insureflow.agent.orchestrator.ConfidenceCalculator;
import com.insureflow.agent.orchestrator.DecisionMatrix;
import com.insureflow.agent.orchestrator.DecisionMatrix.Decision;
import com.insureflow.agent.orchestrator.DecisionMatrix.DecisionResult;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.model.HumanReviewTask;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.port.out.ClaimRepository;
import com.insureflow.domain.port.out.HumanReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * OrchestratorConsumer — two responsibilities:
 *
 * 1. Listens on claim.intake → fans out to Router, Validator, Estimator in parallel
 * 2. Listens on claim.decision → runs DecisionMatrix after all agents finish
 *
 * The pipeline flow:
 *   claim.intake  → [Router, Validator, Estimator] in parallel
 *   Estimator     → triggers FraudAgent via claim.fraud.checked
 *   FraudAgent    → publishes to claim.decision when done
 *   claim.decision → OrchestratorConsumer reads all results from DB → DecisionMatrix
 */
@Component
public class OrchestratorConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorConsumer.class);

    private final RabbitTemplate        rabbitTemplate;
    private final ClaimRepository       claimRepository;
    private final HumanReviewRepository reviewRepository;
    private final DecisionMatrix        decisionMatrix;
    private final ConfidenceCalculator  confidenceCalculator;

    public OrchestratorConsumer(RabbitTemplate rabbitTemplate,
                                ClaimRepository claimRepository,
                                HumanReviewRepository reviewRepository,
                                DecisionMatrix decisionMatrix,
                                ConfidenceCalculator confidenceCalculator) {
        this.rabbitTemplate      = rabbitTemplate;
        this.claimRepository     = claimRepository;
        this.reviewRepository    = reviewRepository;
        this.decisionMatrix      = decisionMatrix;
        this.confidenceCalculator = confidenceCalculator;
    }

    /**
     * Entry point — fans out to all agent queues in parallel.
     */
    @RabbitListener(queues = RabbitMQConfig.Q_INTAKE)
    public void onClaimIntake(ClaimEvent event) {
        log.info("[ORCHESTRATOR] Received claimId={}", event.getClaimId());

        rabbitTemplate.convertAndSend(EXCHANGE, Q_ROUTED,    event);
        rabbitTemplate.convertAndSend(EXCHANGE, Q_VALIDATED, event);
        rabbitTemplate.convertAndSend(EXCHANGE, Q_ESTIMATED, event);

        log.info("[ORCHESTRATOR] Fanned out claimId={} to router, validator, estimator",
                event.getClaimId());
    }

    /**
     * Final decision point — called after FraudAgent finishes.
     * All 4 agent results are now in the DB.
     * Runs DecisionMatrix and updates claim to final status.
     */
    @RabbitListener(queues = RabbitMQConfig.Q_DECISION)
    public void onDecision(ClaimEvent event) {
        log.info("[ORCHESTRATOR] Running DecisionMatrix for claimId={}", event.getClaimId());

        Claim claim = claimRepository.findById(event.getClaimId())
                .orElse(null);

        if (claim == null) {
            log.error("[ORCHESTRATOR] Claim not found: {}", event.getClaimId());
            return;
        }

        // Compute composite confidence
        double confidence = confidenceCalculator.compute(claim);
        claim.setConfidenceScore(confidence);

        // Run decision matrix
        DecisionResult result = decisionMatrix.evaluate(claim, confidence);

        log.info("[ORCHESTRATOR] Decision for claimId={}: {} — reason: {}",
                event.getClaimId(), result.decision(), result.reason());

        // Update claim status
        ClaimStatus finalStatus = switch (result.decision()) {
            case APPROVED       -> ClaimStatus.APPROVED;
            case REJECTED       -> ClaimStatus.REJECTED;
            case PENDING_REVIEW -> ClaimStatus.PENDING_REVIEW;
        };

        if (result.decision() == Decision.REJECTED) {
            claim.setRejectionReason(result.reason());
        }

        claim.transitionTo(finalStatus);
        claimRepository.save(claim);

        // Create human review task if needed
        if (result.decision() == Decision.PENDING_REVIEW) {
            HumanReviewTask reviewTask = HumanReviewTask.createFor(
                    claim.getId(), result.reason());
            reviewRepository.save(reviewTask);
            log.info("[ORCHESTRATOR] HumanReviewTask created for claimId={}",
                    event.getClaimId());
        }

        log.info("[ORCHESTRATOR] Pipeline complete — claimId={} status={}",
                event.getClaimId(), finalStatus);
    }

    private static final String EXCHANGE    = RabbitMQConfig.EXCHANGE;
    private static final String Q_ROUTED    = RabbitMQConfig.Q_ROUTED;
    private static final String Q_VALIDATED = RabbitMQConfig.Q_VALIDATED;
    private static final String Q_ESTIMATED = RabbitMQConfig.Q_ESTIMATED;
}