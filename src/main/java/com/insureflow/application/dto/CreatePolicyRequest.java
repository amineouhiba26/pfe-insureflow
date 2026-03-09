// CreatePolicyRequest.java
package com.insureflow.application.dto;

import com.insureflow.domain.model.enums.ClaimType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class CreatePolicyRequest {

    @NotNull(message = "Client ID is required")
    private UUID clientId;

    @NotBlank(message = "Policy number is required")
    private String policyNumber;

    @NotNull(message = "Policy type is required")
    private ClaimType type;

    @NotNull(message = "Coverage limit is required")
    private BigDecimal coverageLimit;

    private BigDecimal deductible;
    private LocalDate startDate;
    private LocalDate endDate;

    public CreatePolicyRequest() {}

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
}