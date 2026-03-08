package com.insureflow.domain.model.enums;

/**
 * The lifecycle of a claim, from submission to final decision.
 *
 * SUBMITTED      → claim just arrived, saved to DB, event published to RabbitMQ
 * ROUTING        → RouterAgent is classifying the claim type
 * VALIDATING     → ValidatorAgent is checking coverage against the contract
 * ESTIMATING     → EstimatorAgent is analyzing damage photos
 * FRAUD_CHECK    → FraudAgent is cross-examining text vs photo results
 * PENDING_REVIEW → orchestrator flagged it, waiting for human adjuster
 * APPROVED       → auto-approved by DecisionMatrix or adjuster
 * REJECTED       → not covered or adjuster rejected it
 */
public enum ClaimStatus {
    SUBMITTED,
    ROUTING,
    VALIDATING,
    ESTIMATING,
    FRAUD_CHECK,
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}