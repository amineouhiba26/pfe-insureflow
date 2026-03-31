// ConfidenceCalculator.java
package com.insureflow.agent.orchestrator;

import com.insureflow.agent.shared.ResponseParser;
import com.insureflow.domain.model.Claim;
import org.springframework.stereotype.Component;

/**
 * Computes the composite confidence score for a claim.
 *
 * Formula:
 *   composite = (LLM average confidence × 0.4)
 *             + (business rules score   × 0.4)
 *             + (image quality score    × 0.2)
 *
 * Threshold: composite < 0.75 → PENDING_REVIEW
 */
@Component
public class ConfidenceCalculator {

    private static final double THRESHOLD = 0.75;

    public double compute(Claim claim) {
        double llmScore       = computeLlmScore(claim);
        double businessScore  = computeBusinessScore(claim);
        double imageScore     = getImageQualityScore(claim);

        double composite = (llmScore * 0.4)
                + (businessScore * 0.4)
                + (imageScore * 0.2);

        return Math.min(1.0, Math.max(0.0, composite));
    }

    public boolean isBelowThreshold(double score) {
        return score < THRESHOLD;
    }

    private double computeLlmScore(Claim claim) {
        double routerConf    = ResponseParser.getDouble(
                claim.getRouterResult(),    "confidence", 0.5);
        double validatorConf = ResponseParser.getDouble(
                claim.getValidatorResult(), "confidence", 0.5);
        double estimatorConf = ResponseParser.getDouble(
                claim.getEstimatorResult(), "confidence", 0.5);
        double fraudConf     = ResponseParser.getDouble(
                claim.getFraudResult(),     "confidence", 0.5);

        return (routerConf + validatorConf + estimatorConf + fraudConf) / 4.0;
    }

    private double computeBusinessScore(Claim claim) {
        double score = 1.0;

        // Reduce score if validator not fully confident
        double validatorConf = ResponseParser.getDouble(
                claim.getValidatorResult(), "confidence", 1.0);
        if (validatorConf < 0.7) score -= 0.2;

        // Reduce score if fraud anomaly detected
        double anomalyScore = ResponseParser.getDouble(
                claim.getFraudResult(), "anomalyScore", 0.0);
        score -= anomalyScore * 0.4;

        return Math.max(0.0, score);
    }

    private double getImageQualityScore(Claim claim) {
        return ResponseParser.getDouble(
                claim.getEstimatorResult(), "imageQualityScore", 0.5);
    }
}