package com.insureflow.infrastructure.persistence.entity;

import com.insureflow.domain.model.enums.ClaimType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the policies table.
 *
 * @Enumerated(EnumType.STRING) means the enum is stored as a string in the DB
 * ("VEHICLE_DAMAGE") not as a number (0, 1, 2...).
 * Always use STRING — if you reorder the enum values, numbers break silently.
 *
 * clientId is stored as a plain UUID column, not as a @ManyToOne relationship.
 * Why? Because we follow hexagonal architecture — JPA relationships create
 * tight coupling and lazy loading issues. We fetch objects through their
 * own repositories instead.
 */
@Entity
@Table(name = "policies")
public class PolicyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "policy_number", unique = true, nullable = false)
    private String policyNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private ClaimType type;

    @Column(name = "coverage_limit")
    private BigDecimal coverageLimit;

    @Column(name = "deductible")
    private BigDecimal deductible;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "contract_document_path")
    private String contractDocumentPath;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public PolicyJpaEntity() {}

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