package com.insureflow.infrastructure.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The message payload that travels through RabbitMQ.
 *
 * Why a separate ClaimEvent instead of sending the Claim domain object directly?
 * 1. The domain object might contain fields that aren't needed in messages.
 * 2. If you change the Claim class, you don't want to break message contracts.
 * 3. This class is the public API of your messaging system — keep it stable.
 *
 * correlationId: a UUID that ties all agent responses back to one claim.
 * The Orchestrator creates a ConcurrentHashMap keyed by correlationId.
 * Each agent result arrives with the same correlationId so the Orchestrator
 * knows which claim it belongs to.
 *
 * This class needs a no-arg constructor and getters/setters for Jackson
 * to serialize/deserialize it as JSON automatically.
 */
public class ClaimEvent {

    private UUID   correlationId;  // = claim ID — used to match agent responses
    private UUID   claimId;
    private UUID   clientId;
    private UUID   policyId;
    private String description;
    private List<String> photoUrls;
    private Instant submittedAt;

    public ClaimEvent() {}

    public ClaimEvent(UUID claimId, UUID clientId, UUID policyId,
                      String description, List<String> photoUrls,
                      Instant submittedAt) {
        this.correlationId = claimId; // correlation = claim id for simplicity
        this.claimId       = claimId;
        this.clientId      = clientId;
        this.policyId      = policyId;
        this.description   = description;
        this.photoUrls     = photoUrls;
        this.submittedAt   = submittedAt;
    }

    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID claimId) { this.claimId = claimId; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(List<String> photoUrls) { this.photoUrls = photoUrls; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}