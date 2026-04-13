package com.insureflow.agent.orchestrator;

import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.Claim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * DecisionMatrix — applies rules to produce final decision.
 *
 * IMPORTANT: The system NEVER auto-rejects a claim.
 * Only a human admin can reject after review.
 *
 * Rules (first match wins):
 * 1. Not covered by policy        → PENDING_REVIEW (human decides)
 * 2. Fraud anomaly score > 0.6    → PENDING_REVIEW
 * 2b. Price inflation > 30%       → PENDING_REVIEW (deterministic)
 * 3. Severity = TOTAL_LOSS        → PENDING_REVIEW
 * 4. Composite confidence < 0.75  → PENDING_REVIEW
 * 5. Estimated cost > 15,000 TND  → PENDING_REVIEW
 * 6. All rules passed             → PENDING_REVIEW (human confirmation)
 */
@Component
public class DecisionMatrix {

    private static final Logger log = LoggerFactory.getLogger(DecisionMatrix.class);

    private static final double     FRAUD_THRESHOLD = 0.6;
    private static final BigDecimal COST_THRESHOLD  = BigDecimal.valueOf(15_000);

    public enum Decision { PENDING_REVIEW }

    public record DecisionResult(Decision decision, String reason, String flag) {}

    public DecisionResult evaluate(Claim claim, double confidenceScore) {
        log.info("[DECISION] Evaluating claimId={} confidence={}",
                claim.getId(), confidenceScore);

        // Rule 1 — not covered → send to human review with flag
        boolean covered = ResponseParser.getBoolean(
                claim.getValidatorResult(), "covered", true);
        if (!covered) {
            String reason = ResponseParser.getString(
                    claim.getValidatorResult(), "reasoning",
                    "Couverture non confirmée par le contrat");
            log.info("[DECISION] Rule 1 — NOT COVERED → PENDING_REVIEW");
            return new DecisionResult(Decision.PENDING_REVIEW,
                    "Couverture à vérifier: " + reason,
                    "NON_COUVERT");
        }

        // Rule 2 — fraud score high
        double anomalyScore = ResponseParser.getDouble(
                claim.getFraudResult(), "anomalyScore", 0.0);
        String anomalyType  = ResponseParser.getString(
                claim.getFraudResult(), "anomalyType", "NONE");
        if (anomalyScore > FRAUD_THRESHOLD) {
            String reason = String.format(
                    "Score de fraude %.0f%% — anomalie: %s",
                    anomalyScore * 100, anomalyType);
            log.info("[DECISION] Rule 2 — FRAUD {} → PENDING_REVIEW", anomalyScore);
            return new DecisionResult(Decision.PENDING_REVIEW, reason, "FRAUDE_SUSPECTEE");
        }

        // Rule 2b — deterministic price-inflation check (re-added as safeguard)
        if (claim.getClientEstimatedCost() != null && claim.getEstimatedCost() != null
                && claim.getEstimatedCost().compareTo(BigDecimal.ZERO) > 0) {
            double clientCost = claim.getClientEstimatedCost().doubleValue();
            double sysCost    = claim.getEstimatedCost().doubleValue();
            double ratio      = clientCost / sysCost;
            if (ratio > 1.30) {
                String reason = String.format(
                        "Inflation de prix détectée : client %.0f TND vs système %.0f TND (ratio %.1fx)",
                        clientCost, sysCost, ratio);
                log.info("[DECISION] Rule 2b — PRICE_INFLATION ratio={} → PENDING_REVIEW", ratio);
                return new DecisionResult(Decision.PENDING_REVIEW, reason, "PRIX_GONFLE");
            }
        }

        // Rule 3 — total loss
        String severity = ResponseParser.getString(
                claim.getEstimatorResult(), "overallSeverity", "");
        if ("TOTAL_LOSS".equalsIgnoreCase(severity)) {
            log.info("[DECISION] Rule 3 — TOTAL_LOSS → PENDING_REVIEW");
            return new DecisionResult(Decision.PENDING_REVIEW,
                    "Destruction totale — expertise humaine requise",
                    "PERTE_TOTALE");
        }

        // Rule 4 — low confidence
        if (confidenceScore < 0.75) {
            String reason = String.format(
                    "Confiance système %.0f%% insuffisante", confidenceScore * 100);
            log.info("[DECISION] Rule 4 — LOW CONFIDENCE → PENDING_REVIEW");
            return new DecisionResult(Decision.PENDING_REVIEW, reason, "CONFIANCE_FAIBLE");
        }

        // Rule 5 — high cost
        if (claim.getEstimatedCost() != null
                && claim.getEstimatedCost().compareTo(COST_THRESHOLD) > 0) {
            String reason = String.format(
                    "Montant estimé %s TND dépasse le seuil d'autorisation automatique",
                    claim.getEstimatedCost());
            log.info("[DECISION] Rule 5 — HIGH COST {} → PENDING_REVIEW",
                    claim.getEstimatedCost());
            return new DecisionResult(Decision.PENDING_REVIEW, reason, "MONTANT_ELEVE");
        }

        // Rule 6 — All rules passed
        log.info("[DECISION] All rules passed → PENDING_REVIEW for human confirmation");
        return new DecisionResult(Decision.PENDING_REVIEW,
                "Toutes les vérifications automatiques sont passées — confirmation humaine requise",
                "VERIFICATION_OK");
    }
}