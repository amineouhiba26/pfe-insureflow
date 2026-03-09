// ClaimResponse.java
package com.insureflow.application.dto;

import com.insureflow.domain.model.Claim;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What the API returns when a claim is queried.
 * Notice it has a static factory method fromDomain() —
 * the controller calls this to convert a domain Claim into
 * a ClaimResponse without knowing the internals.
 */
public class ClaimResponse {

    private UUID id;
    private UUID clientId;
    private UUID policyId;
    private ClaimType type;
    private ClaimStatus status;
    private String description;
    private BigDecimal estimatedCost;
    private String rejectionReason;
    private Double confidenceScore;
    private Instant submittedAt;
    private Instant updatedAt;

    public ClaimResponse() {}

    public static ClaimResponse fromDomain(Claim c) {
        ClaimResponse r = new ClaimResponse();
        r.id = c.getId();
        r.clientId = c.getClientId();
        r.policyId = c.getPolicyId();
        r.type = c.getType();
        r.status = c.getStatus();
        r.description = c.getDescription();
        r.estimatedCost = c.getEstimatedCost();
        r.rejectionReason = c.getRejectionReason();
        r.confidenceScore = c.getConfidenceScore();
        r.submittedAt = c.getSubmittedAt();
        r.updatedAt = c.getUpdatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public UUID getPolicyId() { return policyId; }
    public ClaimType getType() { return type; }
    public ClaimStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public String getRejectionReason() { return rejectionReason; }
    public Double getConfidenceScore() { return confidenceScore; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}