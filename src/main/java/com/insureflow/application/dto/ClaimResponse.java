package com.insureflow.application.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insureflow.domain.model.Claim;
import com.insureflow.domain.model.enums.ClaimStatus;
import com.insureflow.domain.model.enums.ClaimType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * All agent result fields are exposed as Map<String, Object> instead of raw strings.
 * This means the JSON response contains a proper nested object, not an escaped string.
 *
 * Before: "validatorResult": "{\"covered\":true, ...}"   ← ugly escaped string
 * After:  "validatorResult": {"covered": true, ...}      ← clean nested JSON
 */
public class ClaimResponse {

    private static final ObjectMapper mapper = new ObjectMapper();

    private UUID        id;
    private UUID        clientId;
    private UUID        policyId;
    private ClaimType   type;
    private ClaimStatus status;
    private String      description;
    private BigDecimal  estimatedCost;
    private BigDecimal  finalCost;
    private String      rejectionReason;
    private Double      confidenceScore;
    private Instant     submittedAt;
    private Instant     updatedAt;

    // Agent results as proper JSON objects, not escaped strings
    private Map<String, Object> routerResult;
    private Map<String, Object> validatorResult;
    private Map<String, Object> estimatorResult;
    private Map<String, Object> fraudResult;

    public static ClaimResponse fromDomain(Claim c) {
        ClaimResponse r = new ClaimResponse();
        r.id              = c.getId();
        r.clientId        = c.getClientId();
        r.policyId        = c.getPolicyId();
        r.type            = c.getType();
        r.status          = c.getStatus();
        r.description     = c.getDescription();
        r.estimatedCost   = c.getEstimatedCost();
        r.finalCost       = c.getFinalCost();
        r.rejectionReason = c.getRejectionReason();
        r.confidenceScore = c.getConfidenceScore();
        r.submittedAt     = c.getSubmittedAt();
        r.updatedAt       = c.getUpdatedAt();

        // Parse each JSON string into a proper Map
        r.routerResult    = parseJson(c.getRouterResult());
        r.validatorResult = parseJson(c.getValidatorResult());
        r.estimatorResult = parseJson(c.getEstimatorResult());
        r.fraudResult     = parseJson(c.getFraudResult());

        return r;
    }

    /**
     * Safely parses a JSON string into a Map.
     * Returns null if the string is null or parsing fails.
     */
    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // If parsing fails, wrap the raw string so it's still visible
            return Map.of("raw", json);
        }
    }

    public UUID getId()                          { return id; }
    public UUID getClientId()                    { return clientId; }
    public UUID getPolicyId()                    { return policyId; }
    public ClaimType getType()                   { return type; }
    public ClaimStatus getStatus()               { return status; }
    public String getDescription()               { return description; }
    public BigDecimal getEstimatedCost()         { return estimatedCost; }
    public BigDecimal getFinalCost()             { return finalCost; }
    public String getRejectionReason()           { return rejectionReason; }
    public Double getConfidenceScore()           { return confidenceScore; }
    public Instant getSubmittedAt()              { return submittedAt; }
    public Instant getUpdatedAt()                { return updatedAt; }
    public Map<String, Object> getRouterResult() { return routerResult; }
    public Map<String, Object> getValidatorResult() { return validatorResult; }
    public Map<String, Object> getEstimatorResult() { return estimatorResult; }
    public Map<String, Object> getFraudResult()  { return fraudResult; }
}