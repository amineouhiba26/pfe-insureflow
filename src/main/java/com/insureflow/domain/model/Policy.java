package com.insureflow.domain.model;

import com.insureflow.domain.model.enums.ClaimType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An insurance policy purchased by a client.
 *
 * Key fields explained:
 *
 * coverageLimit       → the maximum amount the insurer will pay for a claim.
 *                       If repair costs exceed this, the client pays the difference.
 *
 * deductible          → the amount the client pays out of pocket before insurance
 *                       kicks in. Example: 500 TND deductible means the client pays
 *                       the first 500 TND of any claim.
 *
 * contractDocumentPath → the file name used to filter pgvector chunks.
 *                        When the ValidatorAgent searches for relevant contract
 *                        articles, it filters by this value so it only reads
 *                        THIS client's contract, not someone else's.
 *
 * type                → what kind of claims this policy covers.
 *                       A VEHICLE_DAMAGE policy won't cover HEALTH claims.
 */
public class Policy {

    private UUID id;
    private UUID clientId;
    private String policyNumber;
    private ClaimType type;
    private BigDecimal coverageLimit;
    private BigDecimal deductible;
    private LocalDate startDate;
    private LocalDate endDate;
    private String contractDocumentPath;
    private Instant createdAt;

    public Policy() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public ClaimType getType() { return type; }
    public void setType(ClaimType type) { this.type = type; }
    public BigDecimal getCoverageLimit() { return coverageLimit; }
    public void setCoverageLimit(BigDecimal coverageLimit) { this.coverageLimit = coverageLimit; }
    public BigDecimal getDeductible() { return deductible; }
    public void setDeductible(BigDecimal deductible) { this.deductible = deductible; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getContractDocumentPath() { return contractDocumentPath; }
    public void setContractDocumentPath(String path) { this.contractDocumentPath = path; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}