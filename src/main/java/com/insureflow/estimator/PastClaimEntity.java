package com.insureflow.estimator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "past_claims")
public class PastClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_name")
    private String clientName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "claim_type")
    private String claimType;

    @Column(name = "parts_damaged")
    private String partsDamaged;

    @Column(name = "estimated_amount")
    private BigDecimal estimatedAmount;

    private String decision;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "contract_type")
    private String contractType;

    @Column(name = "fraud_score")
    private BigDecimal fraudScore;

    @Column(name = "processing_time_seconds")
    private Integer processingTimeSeconds;

    @Column(name = "claim_date")
    private LocalDate claimDate;

    // Stored as TEXT — pgvector is managed via native SQL in SimilarClaimsService
    @Column(name = "embedding", columnDefinition = "vector(768)")
    private String embedding;

    public UUID getId()                       { return id; }
    public String getClientName()             { return clientName; }
    public String getDescription()            { return description; }
    public String getClaimType()              { return claimType; }
    public String getPartsDamaged()           { return partsDamaged; }
    public BigDecimal getEstimatedAmount()    { return estimatedAmount; }
    public String getDecision()               { return decision; }
    public String getRejectionReason()        { return rejectionReason; }
    public String getContractType()           { return contractType; }
    public BigDecimal getFraudScore()         { return fraudScore; }
    public Integer getProcessingTimeSeconds() { return processingTimeSeconds; }
    public LocalDate getClaimDate()           { return claimDate; }
}
