package com.insureflow.domain.model.enums;

/**
 * Type of anomaly detected by the FraudAgent.
 * FraudAgent compares the client's written description against
 * what the EstimatorAgent actually saw in the photos.
 *
 * NONE            → no anomaly, everything is consistent
 * EXAGGERATION    → client described worse damage than photos show
 * UNDERREPORTING  → client described less damage than photos show (rare)
 * INCONSISTENCY   → description mentions different damage than what's visible
 * SUSPICIOUS_MEDIA → photo metadata, lighting, or quality suggests manipulation
 */
public enum AnomalyType {
    NONE,
    EXAGGERATION,
    INCONSISTENCY,
    UNDERREPORTING,
    SUSPICIOUS_MEDIA
}