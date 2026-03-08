package com.insureflow.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Created when the DecisionMatrix decides a claim needs human review.
 *
 * This happens when:
 * - Fraud score > 0.6
 * - Severity is TOTAL_LOSS
 * - Composite confidence < 0.75
 * - Estimated cost > 15,000 TND
 *
 * An adjuster sees this in their queue via GET /api/v1/reviews/pending
 * and resolves it via PUT /api/v1/reviews/{id}/resolve
 *
 * ReviewStatus lifecycle:
 *   PENDING → APPROVED or REJECTED (set by human adjuster)
 */
public class HumanReviewTask {

    public enum ReviewStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    private UUID id;
    private UUID claimId;
    private String reason;         // why it was flagged, e.g. "Fraud score 0.72 exceeds threshold"
    private ReviewStatus status;
    private String assignedTo;     // adjuster username
    private String adjusterNotes;  // adjuster's written justification
    private Instant createdAt;
    private Instant resolvedAt;

    public HumanReviewTask() {}

    /**
     * Factory method — creates a PENDING review task for a claim.
     * The reason explains exactly why the DecisionMatrix flagged it.
     */
    public static HumanReviewTask createFor(UUID claimId, String reason) {
        HumanReviewTask t = new HumanReviewTask();
        t.id = UUID.randomUUID();
        t.claimId = claimId;
        t.reason = reason;
        t.status = ReviewStatus.PENDING;
        t.createdAt = Instant.now();
        return t;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID claimId) { this.claimId = claimId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public ReviewStatus getStatus() { return status; }
    public void setStatus(ReviewStatus status) { this.status = status; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public String getAdjusterNotes() { return adjusterNotes; }
    public void setAdjusterNotes(String notes) { this.adjusterNotes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}