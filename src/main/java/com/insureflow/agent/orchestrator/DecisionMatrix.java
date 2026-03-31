// DecisionMatrix.java
package com.insureflow.agent.orchestrator;

import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.Claim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * DecisionMatrix — applies 6 rules in order to produce final decision.
 *
 * Rules (first match wins):
 * 1. Not covered by policy              → REJECTED
 * 2. Fraud anomaly score > 0.6          → PENDING_REVIEW
 * 3. Severity = TOTAL_LOSS              → PENDING_REVIEW
 * 4. Composite confidence < 0.75        → PENDING_REVIEW
 * 5. Estimated cost > 15,000 TND        → PENDING_REVIEW
 * 6. All rules passed                   → AUTO_APPROVED
 */
@Component
public class DecisionMatrix {

    private static final Logger log = LoggerFactory.getLogger(DecisionMatrix.class);

    private static final double     FRAUD_THRESHOLD  = 0.6;
    private static final BigDecimal COST_THRESHOLD   = BigDecimal.valueOf(15_000);

    public enum Decision { APPROVED, REJECTED, PENDING_REVIEW }

    public record DecisionResult(Decision decision, String reason) {}

    public DecisionResult evaluate(Claim claim, double confidenceScore) {
        log.info("[DECISION] Evaluating claimId={} confidence={}",
                claim.getId(), confidenceScore);

        // Rule 1 — not covered
        boolean covered = ResponseParser.getBoolean(
                claim.getValidatorResult(), "covered", true);
        if (!covered) {
            String reason = ResponseParser.getString(
                    claim.getValidatorResult(), "reasoning", "Non couvert par le contrat");
            log.info("[DECISION] Rule 1 triggered — NOT COVERED → REJECTED");
            return new DecisionResult(Decision.REJECTED, "Non couvert: " + reason);
        }

        // Rule 2 — fraud score
        double anomalyScore = ResponseParser.getDouble(
                claim.getFraudResult(), "anomalyScore", 0.0);
        String anomalyType  = ResponseParser.getString(
                claim.getFraudResult(), "anomalyType", "NONE");
        if (anomalyScore > FRAUD_THRESHOLD) {
            String reason = String.format(
                    "Score de fraude %.2f dépasse le seuil %.1f — anomalie: %s",
                    anomalyScore, FRAUD_THRESHOLD, anomalyType);
            log.info("[DECISION] Rule 2 triggered — FRAUD {} → PENDING_REVIEW", anomalyScore);
            return new DecisionResult(Decision.PENDING_REVIEW, reason);
        }

        // Rule 2b — deterministic price-inflation check (catches cases LLM scored < 0.6)
        // If client declared cost exceeds system estimate by > 30%, flag for human review.
        if (claim.getClientEstimatedCost() != null && claim.getEstimatedCost() != null
                && claim.getEstimatedCost().compareTo(BigDecimal.ZERO) > 0) {
            double clientCost = claim.getClientEstimatedCost().doubleValue();
            double sysCost    = claim.getEstimatedCost().doubleValue();
            double ratio      = clientCost / sysCost;
            if (ratio > 1.30) {
                String reason = String.format(
                        "Inflation de prix détectée : client %.0f TND vs système %.0f TND (ratio %.1fx)",
                        clientCost, sysCost, ratio);
                log.info("[DECISION] Rule 2b triggered — PRICE_INFLATION ratio={} → PENDING_REVIEW", ratio);
                return new DecisionResult(Decision.PENDING_REVIEW, reason);
            }
        }

        // Rule 3 — total loss
        String overallSeverity = ResponseParser.getString(
                claim.getEstimatorResult(), "overallSeverity", "");
        if ("TOTAL_LOSS".equalsIgnoreCase(overallSeverity)) {
            log.info("[DECISION] Rule 3 triggered — TOTAL_LOSS → PENDING_REVIEW");
            return new DecisionResult(Decision.PENDING_REVIEW,
                    "Sévérité TOTAL_LOSS — revue humaine obligatoire");
        }

        // Rule 4 — confidence
        if (confidenceScore < 0.75) {
            String reason = String.format(
                    "Confiance composite %.2f inférieure au seuil 0.75", confidenceScore);
            log.info("[DECISION] Rule 4 triggered — LOW CONFIDENCE {} → PENDING_REVIEW",
                    confidenceScore);
            return new DecisionResult(Decision.PENDING_REVIEW, reason);
        }

        // Rule 5 — cost threshold
        if (claim.getEstimatedCost() != null
                && claim.getEstimatedCost().compareTo(COST_THRESHOLD) > 0) {
            String reason = String.format(
                    "Coût estimé %s TND dépasse le seuil %s TND",
                    claim.getEstimatedCost(), COST_THRESHOLD);
            log.info("[DECISION] Rule 5 triggered — HIGH COST {} → PENDING_REVIEW",
                    claim.getEstimatedCost());
            return new DecisionResult(Decision.PENDING_REVIEW, reason);
        }

        // Rule 6 — all passed
        log.info("[DECISION] All rules passed → AUTO_APPROVED");
        return new DecisionResult(Decision.APPROVED,
                "Toutes les vérifications automatiques sont passées");
    }
}