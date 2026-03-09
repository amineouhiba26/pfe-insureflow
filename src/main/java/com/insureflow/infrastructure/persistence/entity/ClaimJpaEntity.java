package com.insureflow.infrastructure.persistence.entity;

import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.Convert;


/**
 * JPA entity for the claims table.
 *
 * The 4 agent result fields (routerResult, validatorResult, etc.) are stored
 * as TEXT in Postgres. Each agent writes its full JSON output here.
 * This makes debugging easy — you can query the DB and see exactly what
 * each agent returned for any claim.
 *
 * photoUrls uses columnDefinition = "TEXT[]" because Postgres supports
 * native array columns. We store photo paths as a string array.
 *
 * @UpdateTimestamp means Hibernate automatically updates this field
 * every time the row changes. Gives you a free audit trail.
 */
@Entity
@Table(name = "claims")
public class ClaimJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private ClaimType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Convert(converter = StringListConverter.class)
    @Column(name = "photo_urls", columnDefinition = "TEXT")
    private List<String> photoUrls;

    @Column(name = "router_result", columnDefinition = "TEXT")
    private String routerResult;

    @Column(name = "validator_result", columnDefinition = "TEXT")
    private String validatorResult;

    @Column(name = "estimator_result", columnDefinition = "TEXT")
    private String estimatorResult;

    @Column(name = "fraud_result", columnDefinition = "TEXT")
    private String fraudResult;

    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;

    @Column(name = "final_cost")
    private BigDecimal finalCost;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @CreationTimestamp
    @Column(name = "submitted_at", updatable = false)
    private Instant submittedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ClaimJpaEntity() {}

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
    public void setRouterResult(String r) { this.routerResult = r; }
    public String getValidatorResult() { return validatorResult; }
    public void setValidatorResult(String r) { this.validatorResult = r; }
    public String getEstimatorResult() { return estimatorResult; }
    public void setEstimatorResult(String r) { this.estimatorResult = r; }
    public String getFraudResult() { return fraudResult; }
    public void setFraudResult(String r) { this.fraudResult = r; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal v) { this.estimatedCost = v; }
    public BigDecimal getFinalCost() { return finalCost; }
    public void setFinalCost(BigDecimal v) { this.finalCost = v; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double v) { this.confidenceScore = v; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}