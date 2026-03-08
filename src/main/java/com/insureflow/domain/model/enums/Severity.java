package com.insureflow.domain.model.enums;

/**
 * How severe the damage is, assessed by the EstimatorAgent from photos.
 *
 * MINOR      → scratches, dents, cosmetic damage only
 * MODERATE   → functional damage, needs repair but car still drivable
 * SEVERE     → major structural damage, not drivable
 * TOTAL_LOSS → repair cost exceeds vehicle value, write-off
 *
 * Important: TOTAL_LOSS always triggers human review in DecisionMatrix,
 * regardless of confidence score or estimated cost.
 */
public enum Severity {
    MINOR,
    MODERATE,
    SEVERE,
    TOTAL_LOSS
}