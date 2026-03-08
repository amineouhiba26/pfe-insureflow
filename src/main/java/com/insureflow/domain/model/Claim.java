package com.insureflow.domain.model;

import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The central aggregate of the system.
 *
 * A claim is submitted by a client against their policy.
 * It travels through 4 AI agents and ends up APPROVED, REJECTED,
 * or PENDING_REVIEW (waiting for a human adjuster).
 *
 * Agent results explained:
 * Each agent writes its output as a JSON string into one of these fields:
 *   routerResult    → {"claimType":"VEHICLE_DAMAGE","confidence":0.92,"reasoning":"..."}
 *   validatorResult → {"covered":true,"confidence":0.85,"coverageSection":"Art 3.2"}
 *   estimatorResult → {"severity":"SEVERE","affectedParts":[...],"confidence":0.9}
 *   fraudResult     → {"anomalyDetected":false,"anomalyScore":0.1,"anomalyType":"NONE"}
 *
 * We store raw JSON strings instead of complex nested objects because:
 * 1. It's simple — no extra JPA join tables needed
 * 2. Each agent can parse only what it needs
 * 3. The full LLM response is preserved for debugging
 *
 * photoUrls → list of image paths/URLs submitted with the claim.
 *             The EstimatorAgent reads these to analyze damage.
 *
 * confidenceScore → the final composite score (0.0 to 1.0) computed by
 *                   ConfidenceCalculator after all agents finish.
 *                   Below 0.75 → triggers human review.
 */
public class Claim {

    private UUID id;
    private UUID clientId;
    private UUID policyId;
    private ClaimType type;
    private ClaimStatus status;
    private String description;
    private List<String> photoUrls;

    // Agent result snapshots — raw JSON from each agent
    private String routerResult;
    private String validatorResult;
    private String estimatorResult;
    private String fraudResult;

    // Final outputs
    private BigDecimal estimatedCost;
    private BigDecimal finalCost;
    private String rejectionReason;
    private Double confidenceScore;

    private Instant submittedAt;
    private Instant updatedAt;

    public Claim() {}

    /**
     * Factory method — the only correct way to create a new claim.
     * Sets status to SUBMITTED and timestamps automatically.
     * Never call new Claim() and set fields manually for a new submission.
     */
    public static Claim newSubmission(UUID clientId, UUID policyId,
                                      String description, List<String> photoUrls) {
        Claim c = new Claim();
        c.id = UUID.randomUUID();
        c.clientId = clientId;
        c.policyId = policyId;
        c.description = description;
        c.photoUrls = photoUrls;
        c.status = ClaimStatus.SUBMITTED;
        c.submittedAt = Instant.now();
        c.updatedAt = Instant.now();
        return c;
    }

    /**
     * The only correct way to change claim status.
     * Always updates the timestamp so you have an audit trail.
     * Never call setStatus() directly — always use this method.
     */
    public void transitionTo(ClaimStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public ClaimType getType() { return type; }
    public void setType(ClaimType type) { this.type = type; }
    public ClaimStatus getStatus() { return status; }
    public void setStatus(ClaimStatus status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(List<String> photoUrls) { this.photoUrls = photoUrls; }
    public String getRouterResult() { return routerResult; }
    public void setRouterResult(String routerResult) { this.routerResult = routerResult; }
    public String getValidatorResult() { return validatorResult; }
    public void setValidatorResult(String validatorResult) { this.validatorResult = validatorResult; }
    public String getEstimatorResult() { return estimatorResult; }
    public void setEstimatorResult(String estimatorResult) { this.estimatorResult = estimatorResult; }
    public String getFraudResult() { return fraudResult; }
    public void setFraudResult(String fraudResult) { this.fraudResult = fraudResult; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public BigDecimal getFinalCost() { return finalCost; }
    public void setFinalCost(BigDecimal finalCost) { this.finalCost = finalCost; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}